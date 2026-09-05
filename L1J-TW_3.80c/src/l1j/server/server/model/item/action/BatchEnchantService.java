package l1j.server.server.model.item.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

import l1j.server.Config;
import l1j.server.server.ClientThread;
import l1j.server.server.GeneralThreadPool;
import l1j.server.server.Opcodes;
import l1j.server.server.datatables.LogEnchantTable;
import l1j.server.server.model.L1PcInventory;
import l1j.server.server.model.Instance.L1ItemInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.identity.L1ItemId;
import l1j.server.server.serverpackets.S_SystemMessage;

/**
 * Server-side batch enchant service.
 *
 * Safe-zone normal-scroll ranges are fast-forwarded as one inventory update.
 * Randomized or risky ranges are executed one scroll at a time through the
 * original Enchant implementation and paced asynchronously.
 */
public final class BatchEnchantService {

	public enum RiskyEnchantResult {
		SUCCESS,
		NO_CHANGE,
		DESTROYED,
		INVALID
	}

	public static final class FastForwardResult {
		private final int _steps;
		private final int _finalLevel;
		private final boolean _reachedTarget;
		private final String _stopReason;

		private FastForwardResult(int steps, int finalLevel, boolean reachedTarget, String stopReason) {
			_steps = steps;
			_finalLevel = finalLevel;
			_reachedTarget = reachedTarget;
			_stopReason = stopReason;
		}

		public int getSteps() {
			return _steps;
		}

		public int getFinalLevel() {
			return _finalLevel;
		}

		public boolean isReachedTarget() {
			return _reachedTarget;
		}

		public String getStopReason() {
			return _stopReason;
		}
	}

	private static final class Session {
		private final int _targetLevel;
		private final int _maxItems;
		private final boolean _useBlessed;
		private final boolean _useCursed;
		private final long _createdAt;

		private Session(int targetLevel, int maxItems, boolean useBlessed, boolean useCursed) {
			_targetLevel = targetLevel;
			_maxItems = maxItems;
			_useBlessed = useBlessed;
			_useCursed = useCursed;
			_createdAt = System.currentTimeMillis();
		}
	}

	private static final class BatchJob implements Runnable {
		private final L1PcInstance _pc;
		private final ClientThread _client;
		private final Session _session;
		private final List<Integer> _candidateIds;
		private final int _normalScrollItemId;
		private final Semaphore _jobSlots;

		private int _candidateIndex;
		private int _attempts;
		private int _processed;
		private int _reached;
		private int _destroyed;
		private int _invalid;
		private int _normalUsed;
		private int _blessedUsed;
		private int _cursedUsed;
		private boolean _currentItemCounted;
		private boolean _cursedRollbackPending;
		private volatile boolean _finished;

		private BatchJob(L1PcInstance pc, ClientThread client, Session session,
				List<Integer> candidateIds, int normalScrollItemId, Semaphore jobSlots) {
			_pc = pc;
			_client = client;
			_session = session;
			_candidateIds = candidateIds;
			_normalScrollItemId = normalScrollItemId;
			_jobSlots = jobSlots;
		}

		@Override
		public void run() {
			if (_finished) {
				return;
			}
			try {
				runStep();
			} catch (Exception e) {
				_log.log(Level.SEVERE, "[BatchEnchant] job failed for " + _pc.getName(), e);
				finish("exception");
			}
		}

		private void runStep() {
			if (isCancelRequested(_pc)) {
				finish("cancelled");
				return;
			}
			if (!isSameActiveCharacter(_pc, _client)) {
				finish("player-offline");
				return;
			}
			if (_candidateIndex >= _candidateIds.size()) {
				finish("completed");
				return;
			}
			if (_attempts >= Config.BATCH_ENCHANT_MAX_ATTEMPTS) {
				finish("attempt-limit");
				return;
			}

			int itemObjectId = _candidateIds.get(_candidateIndex).intValue();
			L1ItemInstance item = getCurrentItem(itemObjectId);
			if (item == null) {
				_invalid++;
				moveToNextItem(0L);
				return;
			}

			int currentLevel = item.getEnchantLevel();
			int safeEnchant = item.getItem().get_safeenchant();
			if (currentLevel >= _session._targetLevel) {
				_reached++;
				moveToNextItem(Config.BATCH_ENCHANT_ITEM_DELAY_MILLIS);
				return;
			}

			if (_cursedRollbackPending) {
				if (currentLevel != safeEnchant) {
					finish("cursed-rollback-state-changed");
					return;
				}
				executeCursedRollback(itemObjectId, item);
				return;
			}

			boolean blessedStrategy = shouldUseBlessedStrategy(safeEnchant);
			int fastForwardEnd = blessedStrategy
					? Math.min(_session._targetLevel, safeEnchant - 1)
					: Math.min(_session._targetLevel, safeEnchant);

			if (currentLevel < fastForwardEnd) {
				executeSafeFastForward(itemObjectId, fastForwardEnd);
				return;
			}

			if (blessedStrategy && currentLevel == safeEnchant - 1) {
				executeBlessedAttempt(itemObjectId, item, safeEnchant);
				return;
			}

			executeNormalRiskyAttempt(itemObjectId, item);
		}

		private boolean shouldUseBlessedStrategy(int safeEnchant) {
			return _session._useBlessed && safeEnchant > 0
					&& _session._targetLevel >= safeEnchant + 1;
		}

		private void executeSafeFastForward(int itemObjectId, int fastForwardEnd) {
			int remainingBudget = Config.BATCH_ENCHANT_MAX_ATTEMPTS - _attempts;
			if (remainingBudget <= 0) {
				finish("attempt-limit");
				return;
			}

			int scrollObjectId = findScrollObjectId(_pc.getInventory(), _normalScrollItemId);
			if (scrollObjectId == 0) {
				finish("normal-scroll-exhausted");
				return;
			}

			FastForwardResult result = fastForwardSafeEnchant(_pc, scrollObjectId, itemObjectId,
					fastForwardEnd, remainingBudget);
			if (result.getSteps() <= 0) {
				if ("target-reached".equals(result.getStopReason())) {
					scheduleNext(0L);
					return;
				}
				if ("scroll-exhausted".equals(result.getStopReason())) {
					finish("normal-scroll-exhausted");
					return;
				}
				finish(result.getStopReason());
				return;
			}

			countCurrentItemIfNeeded();
			_attempts += result.getSteps();
			_normalUsed += result.getSteps();
			if (_attempts >= Config.BATCH_ENCHANT_MAX_ATTEMPTS
					&& result.getFinalLevel() < _session._targetLevel) {
				finish("attempt-limit");
				return;
			}
			if (result.getFinalLevel() >= _session._targetLevel) {
				_reached++;
				moveToNextItem(Config.BATCH_ENCHANT_ITEM_DELAY_MILLIS);
				return;
			}
			scheduleNext(0L);
		}

		private void executeBlessedAttempt(int itemObjectId, L1ItemInstance item, int safeEnchant) {
			int remainingBudget = Config.BATCH_ENCHANT_MAX_ATTEMPTS - _attempts;
			if (remainingBudget <= 0 || (_session._useCursed && remainingBudget < 2)) {
				finish("attempt-limit");
				return;
			}
			if (!Config.BATCH_ENCHANT_ALLOW_BLESSED_SCROLL) {
				finish("blessed-disabled");
				return;
			}
			int blessedItemId = getBlessedScrollItemId(item.getItem().getType2());
			int scrollObjectId = findScrollObjectId(_pc.getInventory(), blessedItemId);
			if (scrollObjectId == 0) {
				finish("blessed-scroll-exhausted");
				return;
			}

			int oldLevel = item.getEnchantLevel();
			RiskyEnchantResult result = enchantRiskyOnce(_pc, scrollObjectId, itemObjectId, _client, oldLevel);
			if (result == RiskyEnchantResult.INVALID) {
				finish("invalid");
				return;
			}

			countCurrentItemIfNeeded();
			_attempts++;
			_blessedUsed++;
			if (result == RiskyEnchantResult.DESTROYED) {
				_destroyed++;
				moveToNextItem(Config.BATCH_ENCHANT_ITEM_DELAY_MILLIS);
				return;
			}

			L1ItemInstance current = getCurrentItem(itemObjectId);
			if (current == null) {
				_destroyed++;
				moveToNextItem(Config.BATCH_ENCHANT_ITEM_DELAY_MILLIS);
				return;
			}
			int newLevel = current.getEnchantLevel();
			if (newLevel >= _session._targetLevel) {
				_reached++;
				moveToNextItem(Config.BATCH_ENCHANT_ITEM_DELAY_MILLIS);
				return;
			}
			if (_session._useCursed && oldLevel == safeEnchant - 1 && newLevel == safeEnchant) {
				_cursedRollbackPending = true;
			}
			scheduleNext(Config.BATCH_ENCHANT_RISKY_ATTEMPT_DELAY_MILLIS);
		}

		private void executeCursedRollback(int itemObjectId, L1ItemInstance item) {
			if (!Config.BATCH_ENCHANT_ALLOW_CURSED_SCROLL) {
				finish("cursed-disabled");
				return;
			}
			int safeEnchant = item.getItem().get_safeenchant();
			int cursedItemId = getCursedScrollItemId(item.getItem().getType2());
			int scrollObjectId = findScrollObjectId(_pc.getInventory(), cursedItemId);
			if (scrollObjectId == 0) {
				finish("cursed-scroll-exhausted");
				return;
			}

			RiskyEnchantResult result = enchantRiskyOnce(_pc, scrollObjectId, itemObjectId, _client, safeEnchant);
			if (result == RiskyEnchantResult.INVALID) {
				finish("invalid");
				return;
			}

			countCurrentItemIfNeeded();
			_attempts++;
			_cursedUsed++;
			if (result == RiskyEnchantResult.DESTROYED) {
				_destroyed++;
				moveToNextItem(Config.BATCH_ENCHANT_ITEM_DELAY_MILLIS);
				return;
			}

			L1ItemInstance current = getCurrentItem(itemObjectId);
			if (current == null) {
				finish("cursed-rollback-item-missing");
				return;
			}
			if (current.getEnchantLevel() != safeEnchant - 1) {
				finish("cursed-rollback-failed");
				return;
			}
			_cursedRollbackPending = false;
			scheduleNext(Config.BATCH_ENCHANT_RISKY_ATTEMPT_DELAY_MILLIS);
		}

		private void executeNormalRiskyAttempt(int itemObjectId, L1ItemInstance item) {
			int scrollObjectId = findScrollObjectId(_pc.getInventory(), _normalScrollItemId);
			if (scrollObjectId == 0) {
				finish("normal-scroll-exhausted");
				return;
			}

			RiskyEnchantResult result = enchantRiskyOnce(_pc, scrollObjectId, itemObjectId, _client,
					item.getEnchantLevel());
			if (result == RiskyEnchantResult.INVALID) {
				finish("invalid");
				return;
			}

			countCurrentItemIfNeeded();
			_attempts++;
			_normalUsed++;
			if (result == RiskyEnchantResult.DESTROYED) {
				_destroyed++;
				moveToNextItem(Config.BATCH_ENCHANT_ITEM_DELAY_MILLIS);
				return;
			}

			L1ItemInstance current = getCurrentItem(itemObjectId);
			if (current == null) {
				_destroyed++;
				moveToNextItem(Config.BATCH_ENCHANT_ITEM_DELAY_MILLIS);
				return;
			}
			if (current.getEnchantLevel() >= _session._targetLevel) {
				_reached++;
				moveToNextItem(Config.BATCH_ENCHANT_ITEM_DELAY_MILLIS);
				return;
			}
			scheduleNext(Config.BATCH_ENCHANT_RISKY_ATTEMPT_DELAY_MILLIS);
		}

		private L1ItemInstance getCurrentItem(int itemObjectId) {
			L1PcInventory inventory = _pc.getInventory();
			synchronized (inventory) {
				L1ItemInstance item = inventory.getItem(itemObjectId);
				if (item == null || !isSupportedBatchEquipment(item)) {
					return null;
				}
				return item;
			}
		}

		private void countCurrentItemIfNeeded() {
			if (!_currentItemCounted) {
				_processed++;
				_currentItemCounted = true;
			}
		}

		private void moveToNextItem(long delayMillis) {
			_candidateIndex++;
			_currentItemCounted = false;
			_cursedRollbackPending = false;
			scheduleNext(delayMillis);
		}

		private void scheduleNext(long delayMillis) {
			if (_finished) {
				return;
			}
			final BatchJob job = this;
			if (delayMillis <= 0L) {
				dispatchNow();
				return;
			}
			if (GeneralThreadPool.getInstance().schedule(new Runnable() {
				@Override
				public void run() {
					job.dispatchNow();
				}
			}, delayMillis) == null) {
				finish("scheduler-rejected");
			}
		}

		private void dispatchNow() {
			if (_finished) {
				return;
			}
			try {
				GeneralThreadPool.getInstance().execute(this);
			} catch (RuntimeException e) {
				_log.log(Level.WARNING, "[BatchEnchant] executor rejected job for " + _pc.getName(), e);
				finish("executor-rejected");
			}
		}

		private synchronized void finish(String stopReason) {
			if (_finished) {
				return;
			}
			_finished = true;
			Integer key = Integer.valueOf(_pc.getId());
			_busyPlayers.remove(key);
			_cancelRequested.remove(key);
			_busyNoticeTimes.remove(key);
			_jobSlots.release();

			String summary = "Batch enchant completed: processed=" + _processed
					+ ", reached=" + _reached
					+ ", destroyed=" + _destroyed
					+ ", invalid=" + _invalid
					+ ", normal=" + _normalUsed
					+ ", blessed=" + _blessedUsed
					+ ", cursed=" + _cursedUsed
					+ ", attempts=" + _attempts
					+ ", stop=" + stopReason + ".";
			if (isSameActiveCharacter(_pc, _client)) {
				_pc.sendPackets(new S_SystemMessage(summary));
			}
			_log.info("[BatchEnchant] done char=" + _pc.getName()
					+ ", processed=" + _processed
					+ ", reached=" + _reached
					+ ", destroyed=" + _destroyed
					+ ", invalid=" + _invalid
					+ ", normal=" + _normalUsed
					+ ", blessed=" + _blessedUsed
					+ ", cursed=" + _cursedUsed
					+ ", attempts=" + _attempts
					+ ", stop=" + stopReason);
		}
	}

	private static final Logger _log = Logger.getLogger(BatchEnchantService.class.getName());
	private static final Map<Integer, Session> _sessions = new ConcurrentHashMap<Integer, Session>();
	private static final Map<Integer, Boolean> _busyPlayers = new ConcurrentHashMap<Integer, Boolean>();
	private static final Map<Integer, Boolean> _cancelRequested = new ConcurrentHashMap<Integer, Boolean>();
	private static final Map<Integer, Long> _busyNoticeTimes = new ConcurrentHashMap<Integer, Long>();
	private static final long BUSY_NOTICE_INTERVAL_MILLIS = 500L;
	private static Semaphore _jobSlots;
	private static int _jobSlotCount;

	private BatchEnchantService() {
	}

	public static void handleCommand(L1PcInstance pc, String arg) {
		if (pc == null) {
			return;
		}
		if (!Config.BATCH_ENCHANT_ENABLED) {
			clear(pc);
			pc.sendPackets(new S_SystemMessage("Batch enchant is disabled by server configuration."));
			return;
		}

		String command = arg == null ? "" : arg.trim();
		if (command.equalsIgnoreCase("off")) {
			boolean running = isInventoryBusy(pc);
			requestCancel(pc);
			pc.sendPackets(new S_SystemMessage(running
					? "Batch enchant cancellation requested."
					: "Batch enchant mode: OFF"));
			return;
		}
		if (command.equalsIgnoreCase("status")) {
			Session session = getValidSession(pc, true);
			if (isInventoryBusy(pc)) {
				pc.sendPackets(new S_SystemMessage("Batch enchant status: RUNNING"));
			} else if (session != null) {
				pc.sendPackets(new S_SystemMessage("Batch enchant status: ARMED, target +"
						+ session._targetLevel + ", max items " + session._maxItems
						+ ", blessed=" + flag(session._useBlessed)
						+ ", cursed=" + flag(session._useCursed)));
			} else {
				pc.sendPackets(new S_SystemMessage("Batch enchant status: OFF"));
			}
			return;
		}

		String[] tokens = command.split("\\s+");
		if (tokens.length != 2 && tokens.length != 4) {
			sendUsage(pc);
			return;
		}

		int targetLevel;
		int maxItems;
		boolean useBlessed = false;
		boolean useCursed = false;
		try {
			targetLevel = Integer.parseInt(tokens[0]);
			maxItems = Integer.parseInt(tokens[1]);
			if (tokens.length == 4) {
				useBlessed = parseFlag(tokens[2]);
				useCursed = parseFlag(tokens[3]);
			}
		} catch (IllegalArgumentException e) {
			sendUsage(pc);
			return;
		}

		if (targetLevel < 1) {
			pc.sendPackets(new S_SystemMessage("Batch enchant target must be +1 or greater."));
			return;
		}
		if (maxItems < 1 || maxItems > Config.BATCH_ENCHANT_MAX_ITEMS) {
			pc.sendPackets(new S_SystemMessage("Batch enchant item count must be between 1 and "
					+ Config.BATCH_ENCHANT_MAX_ITEMS + "."));
			return;
		}
		if (useCursed && !useBlessed) {
			pc.sendPackets(new S_SystemMessage("Cursed rollback requires blessed mode to be enabled."));
			return;
		}
		if (useBlessed && !Config.BATCH_ENCHANT_ALLOW_BLESSED_SCROLL) {
			pc.sendPackets(new S_SystemMessage("Blessed batch enchant is disabled by server configuration."));
			return;
		}
		if (useCursed && !Config.BATCH_ENCHANT_ALLOW_CURSED_SCROLL) {
			pc.sendPackets(new S_SystemMessage("Cursed rollback is disabled by server configuration."));
			return;
		}
		if (isInventoryBusy(pc)) {
			pc.sendPackets(new S_SystemMessage("Batch enchant is already running."));
			return;
		}
		if (pc.getTradeID() != 0 || pc.isPrivateShop()) {
			pc.sendPackets(new S_SystemMessage("Close trading or private shop mode before batch enchanting."));
			return;
		}

		_sessions.put(Integer.valueOf(pc.getId()),
				new Session(targetLevel, maxItems, useBlessed, useCursed));
		pc.sendPackets(new S_SystemMessage("Batch enchant armed: target +" + targetLevel
				+ ", max items " + maxItems
				+ ", blessed=" + flag(useBlessed)
				+ ", cursed=" + flag(useCursed)
				+ ". Use a normal enchant scroll and select one equipment item."));
	}

	public static boolean tryHandle(L1PcInstance pc, L1ItemInstance scroll, L1ItemInstance target,
			ClientThread client) {
		if (pc == null || scroll == null) {
			return false;
		}

		Integer playerKey = Integer.valueOf(pc.getId());
		Session session = _sessions.get(playerKey);
		if (session == null) {
			return false;
		}
		if (!isAnyStandardEnchantScroll(scroll.getItem().getItemId())) {
			return false;
		}
		if (!Config.BATCH_ENCHANT_ENABLED) {
			clear(pc);
			pc.sendPackets(new S_SystemMessage("Batch enchant is disabled by server configuration."));
			return true;
		}
		if (isExpired(session)) {
			clear(pc);
			pc.sendPackets(new S_SystemMessage("Batch enchant request expired. Use eb again."));
			return true;
		}
		if (!isNormalEnchantScroll(scroll.getItem().getItemId())) {
			pc.sendPackets(new S_SystemMessage("Start batch enchant with a normal enchant scroll."));
			return true;
		}
		if (target == null || !scrollMatchesItemType(scroll.getItem().getItemId(), target)) {
			pc.sendPackets(new S_SystemMessage("The selected scroll does not match the target equipment."));
			return true;
		}
		if (!isSupportedBatchEquipment(target)) {
			pc.sendPackets(new S_SystemMessage("The selected equipment cannot be batch enchanted."));
			return true;
		}
		if (session._useBlessed && !Config.BATCH_ENCHANT_ALLOW_BLESSED_SCROLL) {
			clear(pc);
			pc.sendPackets(new S_SystemMessage("Blessed batch enchant is disabled by server configuration."));
			return true;
		}
		if (session._useCursed && !Config.BATCH_ENCHANT_ALLOW_CURSED_SCROLL) {
			clear(pc);
			pc.sendPackets(new S_SystemMessage("Cursed rollback is disabled by server configuration."));
			return true;
		}

		Semaphore jobSlots = getJobSlots();
		if (!jobSlots.tryAcquire()) {
			pc.sendPackets(new S_SystemMessage("Batch enchant server slots are busy. Try the scroll again shortly."));
			return true;
		}

		List<Integer> candidateIds = collectCandidateIds(pc.getInventory(), target.getId(),
				session._targetLevel, session._maxItems);
		if (candidateIds.isEmpty()) {
			jobSlots.release();
			clear(pc);
			pc.sendPackets(new S_SystemMessage("No matching equipment needs batch enchant."));
			return true;
		}

		_sessions.remove(playerKey);
		_cancelRequested.remove(playerKey);
		_busyPlayers.put(playerKey, Boolean.TRUE);
		_log.info("[BatchEnchant] start char=" + pc.getName()
				+ ", target=" + session._targetLevel
				+ ", maxItems=" + session._maxItems
				+ ", candidates=" + candidateIds.size()
				+ ", blessed=" + session._useBlessed
				+ ", cursed=" + session._useCursed);

		BatchJob job = new BatchJob(pc, client, session, candidateIds,
				scroll.getItem().getItemId(), jobSlots);
		job.dispatchNow();
		return true;
	}

	/**
	 * Fast-forward only the deterministic normal-scroll safe zone.
	 * Every consumed scroll still counts as one logical attempt.
	 */
	public static FastForwardResult fastForwardSafeEnchant(L1PcInstance pc, int scrollObjectId,
			int itemObjectId, int targetLevel, int attemptBudget) {
		if (pc == null || attemptBudget <= 0) {
			return new FastForwardResult(0, 0, false, "attempt-limit");
		}

		L1PcInventory inventory = pc.getInventory();
		int oldLevel = 0;
		int newLevel = 0;
		int safeEnchant = -1;
		int steps = 0;
		int itemType2 = 0;
		int itemObjectForLog = 0;
		String stopReason = "invalid";
		boolean reachedTarget = false;

		synchronized (inventory) {
			L1ItemInstance scroll = inventory.getItem(scrollObjectId);
			L1ItemInstance item = inventory.getItem(itemObjectId);
			if (scroll == null || item == null || !isNormalEnchantScroll(scroll.getItem().getItemId())
					|| !scrollMatchesItemType(scroll.getItem().getItemId(), item)
					|| !isSupportedBatchEquipment(item)) {
				return new FastForwardResult(0, item == null ? 0 : item.getEnchantLevel(), false, "invalid");
			}

			safeEnchant = item.getItem().get_safeenchant();
			oldLevel = item.getEnchantLevel();
			newLevel = oldLevel;
			itemType2 = item.getItem().getType2();
			itemObjectForLog = item.getId();

			if (targetLevel > safeEnchant || oldLevel >= targetLevel) {
				reachedTarget = oldLevel >= targetLevel;
				return new FastForwardResult(0, oldLevel, reachedTarget,
						reachedTarget ? "target-reached" : "risky-zone");
			}

			int requiredSteps = targetLevel - oldLevel;
			int availableScrolls = scroll.getCount();
			steps = Math.min(requiredSteps, Math.min(availableScrolls, attemptBudget));
			if (steps <= 0) {
				return new FastForwardResult(0, oldLevel, false,
						availableScrolls <= 0 ? "scroll-exhausted" : "attempt-limit");
			}

			int removed = inventory.removeItem(scroll, steps);
			if (removed != steps) {
				return new FastForwardResult(0, oldLevel, false, "inventory-changed");
			}

			newLevel = oldLevel + steps;
			item.setEnchantLevel(newLevel);
			inventory.updateItem(item, L1PcInventory.COL_ENCHANTLVL);
			reachedTarget = newLevel >= targetLevel;
			if (reachedTarget) {
				stopReason = "target-reached";
			} else if (steps >= attemptBudget) {
				stopReason = "attempt-limit";
			} else {
				stopReason = "scroll-exhausted";
			}
		}

		storeFastForwardLog(pc, itemType2, itemObjectForLog, safeEnchant, oldLevel, newLevel);
		return new FastForwardResult(steps, newLevel, reachedTarget, stopReason);
	}

	/**
	 * Execute exactly one standard enchant attempt through the original RNG logic.
	 * The caller owns policy decisions such as safe-boundary blessed retries.
	 */
	public static RiskyEnchantResult enchantRiskyOnce(L1PcInstance pc, int scrollObjectId,
			int itemObjectId, ClientThread client) {
		return enchantRiskyOnce(pc, scrollObjectId, itemObjectId, client, Integer.MIN_VALUE);
	}

	private static RiskyEnchantResult enchantRiskyOnce(L1PcInstance pc, int scrollObjectId,
			int itemObjectId, ClientThread client, int expectedEnchantLevel) {
		if (pc == null || client == null) {
			return RiskyEnchantResult.INVALID;
		}

		L1PcInventory inventory = pc.getInventory();
		synchronized (inventory) {
			L1ItemInstance scroll = inventory.getItem(scrollObjectId);
			L1ItemInstance item = inventory.getItem(itemObjectId);
			if (scroll == null || item == null || !isAnyStandardEnchantScroll(scroll.getItem().getItemId())
					|| !scrollMatchesItemType(scroll.getItem().getItemId(), item)
					|| !isSupportedBatchEquipment(item)) {
				return RiskyEnchantResult.INVALID;
			}
			if (expectedEnchantLevel != Integer.MIN_VALUE
					&& item.getEnchantLevel() != expectedEnchantLevel) {
				return RiskyEnchantResult.INVALID;
			}

			int oldEnchant = item.getEnchantLevel();
			int oldScrollCount = scroll.getCount();
			if (item.getItem().getType2() == 1) {
				Enchant.scrollOfEnchantWeapon(pc, scroll, item, client, false);
			} else if (item.getItem().getType2() == 2) {
				Enchant.scrollOfEnchantArmor(pc, scroll, item, client, false);
			} else {
				return RiskyEnchantResult.INVALID;
			}

			L1ItemInstance remainingScroll = inventory.getItem(scrollObjectId);
			int newScrollCount = remainingScroll == null ? 0 : remainingScroll.getCount();
			if (oldScrollCount - newScrollCount != 1) {
				return RiskyEnchantResult.INVALID;
			}

			L1ItemInstance remainingItem = inventory.getItem(itemObjectId);
			if (remainingItem == null) {
				return RiskyEnchantResult.DESTROYED;
			}
			if (remainingItem.getEnchantLevel() != oldEnchant) {
				return RiskyEnchantResult.SUCCESS;
			}
			return RiskyEnchantResult.NO_CHANGE;
		}
	}

	public static boolean isInventoryBusy(L1PcInstance pc) {
		return pc != null && _busyPlayers.containsKey(Integer.valueOf(pc.getId()));
	}

	public static boolean blockInventoryActionIfBusy(L1PcInstance pc, int opcode) {
		if (!Config.BATCH_ENCHANT_LOCK_INVENTORY_ACTIONS || !isInventoryBusy(pc)
				|| !isBlockedInventoryOpcode(opcode)) {
			return false;
		}
		notifyInventoryBusy(pc);
		return true;
	}

	public static void clear(L1PcInstance pc) {
		if (pc == null) {
			return;
		}
		Integer key = Integer.valueOf(pc.getId());
		_sessions.remove(key);
		if (!isInventoryBusy(pc)) {
			_cancelRequested.remove(key);
			_busyNoticeTimes.remove(key);
		}
	}

	public static void requestCancel(L1PcInstance pc) {
		if (pc == null) {
			return;
		}
		Integer key = Integer.valueOf(pc.getId());
		_sessions.remove(key);
		if (isInventoryBusy(pc)) {
			_cancelRequested.put(key, Boolean.TRUE);
		} else {
			_cancelRequested.remove(key);
			_busyNoticeTimes.remove(key);
		}
	}

	public static void cancelAndWaitForIdle(L1PcInstance pc, long timeoutMillis) {
		if (pc == null) {
			return;
		}
		requestCancel(pc);
		long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
		while (isInventoryBusy(pc) && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(5L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (isInventoryBusy(pc)) {
			_log.warning("[BatchEnchant] timed out waiting for batch shutdown: " + pc.getName());
		}
	}

	private static List<Integer> collectCandidateIds(L1PcInventory inventory, int templateObjectId,
			int targetLevel, int maxItems) {
		List<Integer> result = new ArrayList<Integer>();
		synchronized (inventory) {
			L1ItemInstance template = inventory.getItem(templateObjectId);
			if (template == null || !isSupportedBatchEquipment(template)) {
				return result;
			}
			int itemId = template.getItem().getItemId();
			if (template.getEnchantLevel() < targetLevel) {
				result.add(Integer.valueOf(template.getId()));
			}
			for (L1ItemInstance item : inventory.getItems()) {
				if (result.size() >= maxItems) {
					break;
				}
				if (item.getId() == templateObjectId || item.getItem().getItemId() != itemId) {
					continue;
				}
				if (!isSupportedBatchEquipment(item) || item.getEnchantLevel() >= targetLevel) {
					continue;
				}
				result.add(Integer.valueOf(item.getId()));
			}
		}
		return result;
	}

	private static Session getValidSession(L1PcInstance pc, boolean clearExpired) {
		Session session = _sessions.get(Integer.valueOf(pc.getId()));
		if (session != null && isExpired(session)) {
			if (clearExpired) {
				_sessions.remove(Integer.valueOf(pc.getId()));
			}
			return null;
		}
		return session;
	}

	private static boolean isExpired(Session session) {
		long timeoutMillis = Config.BATCH_ENCHANT_ARM_TIMEOUT_SECONDS * 1000L;
		return System.currentTimeMillis() - session._createdAt > timeoutMillis;
	}

	private static boolean isSupportedBatchEquipment(L1ItemInstance item) {
		if (item == null || item.isEquipped() || item.getBless() >= 128) {
			return false;
		}
		int type2 = item.getItem().getType2();
		if ((type2 != 1 && type2 != 2) || item.getItem().get_safeenchant() < 0) {
			return false;
		}
		int itemId = item.getItem().getItemId();
		if (type2 == 1) {
			if (itemId == 7 || itemId == 35 || itemId == 48 || itemId == 73 || itemId == 105
					|| itemId == 120 || itemId == 147 || itemId == 156 || itemId == 174
					|| itemId == 175 || itemId == 224 || itemId == 36 || itemId == 183
					|| (itemId >= 246 && itemId <= 255)) {
				return false;
			}
		} else {
			if (itemId == 20028 || itemId == 20082 || itemId == 20126 || itemId == 20173
					|| itemId == 20206 || itemId == 20232 || itemId == 21138 || itemId == 21051
					|| itemId == 21052 || itemId == 21053 || itemId == 21054 || itemId == 21055
					|| itemId == 21056 || itemId == 21140 || itemId == 21141 || itemId == 20161
					|| (itemId >= 21035 && itemId <= 21038)) {
				return false;
			}
		}
		return true;
	}

	private static boolean scrollMatchesItemType(int scrollItemId, L1ItemInstance item) {
		if (item == null) {
			return false;
		}
		if (item.getItem().getType2() == 1) {
			return isWeaponEnchantScroll(scrollItemId);
		}
		if (item.getItem().getType2() == 2) {
			return isArmorEnchantScroll(scrollItemId);
		}
		return false;
	}

	private static boolean isNormalEnchantScroll(int itemId) {
		return itemId == L1ItemId.SCROLL_OF_ENCHANT_WEAPON
				|| itemId == L1ItemId.SCROLL_OF_ENCHANT_ARMOR;
	}

	private static boolean isAnyStandardEnchantScroll(int itemId) {
		return isWeaponEnchantScroll(itemId) || isArmorEnchantScroll(itemId);
	}

	private static boolean isWeaponEnchantScroll(int itemId) {
		return itemId == L1ItemId.SCROLL_OF_ENCHANT_WEAPON
				|| itemId == L1ItemId.B_SCROLL_OF_ENCHANT_WEAPON
				|| itemId == L1ItemId.C_SCROLL_OF_ENCHANT_WEAPON;
	}

	private static boolean isArmorEnchantScroll(int itemId) {
		return itemId == L1ItemId.SCROLL_OF_ENCHANT_ARMOR
				|| itemId == L1ItemId.B_SCROLL_OF_ENCHANT_ARMOR
				|| itemId == L1ItemId.C_SCROLL_OF_ENCHANT_ARMOR;
	}

	private static int getBlessedScrollItemId(int itemType2) {
		return itemType2 == 1
				? L1ItemId.B_SCROLL_OF_ENCHANT_WEAPON
				: L1ItemId.B_SCROLL_OF_ENCHANT_ARMOR;
	}

	private static int getCursedScrollItemId(int itemType2) {
		return itemType2 == 1
				? L1ItemId.C_SCROLL_OF_ENCHANT_WEAPON
				: L1ItemId.C_SCROLL_OF_ENCHANT_ARMOR;
	}

	private static int findScrollObjectId(L1PcInventory inventory, int scrollItemId) {
		synchronized (inventory) {
			L1ItemInstance scroll = inventory.findItemId(scrollItemId);
			return scroll == null ? 0 : scroll.getId();
		}
	}

	private static boolean isCancelRequested(L1PcInstance pc) {
		return pc != null && _cancelRequested.containsKey(Integer.valueOf(pc.getId()));
	}

	private static boolean isSameActiveCharacter(L1PcInstance pc, ClientThread client) {
		return pc != null && client != null && pc.getOnlineStatus() == 1
				&& client.getActiveChar() == pc;
	}

	private static synchronized Semaphore getJobSlots() {
		int configured = Math.max(1, Config.BATCH_ENCHANT_MAX_CONCURRENT_JOBS);
		if (_jobSlots == null || _jobSlotCount != configured) {
			_jobSlots = new Semaphore(configured, true);
			_jobSlotCount = configured;
		}
		return _jobSlots;
	}

	private static boolean parseFlag(String value) {
		if ("0".equals(value)) {
			return false;
		}
		if ("1".equals(value)) {
			return true;
		}
		throw new IllegalArgumentException("flag must be 0 or 1");
	}

	private static int flag(boolean value) {
		return value ? 1 : 0;
	}

	private static boolean isBlockedInventoryOpcode(int opcode) {
		switch (opcode) {
			case Opcodes.C_OPCODE_CHANGECHAR:
			case Opcodes.C_OPCODE_RESTART:
			case Opcodes.C_OPCODE_RESTARTMENU:
			case Opcodes.C_OPCODE_USEITEM:
			case Opcodes.C_OPCODE_USEPETITEM:
			case Opcodes.C_OPCODE_DROPITEM:
			case Opcodes.C_OPCODE_DELETEINVENTORYITEM:
			case Opcodes.C_OPCODE_PICKUPITEM:
			case Opcodes.C_OPCODE_GIVEITEM:
			case Opcodes.C_OPCODE_TRADE:
			case Opcodes.C_OPCODE_TRADEADDITEM:
			case Opcodes.C_OPCODE_TRADEADDOK:
			case Opcodes.C_OPCODE_TRADEADDCANCEL:
			case Opcodes.C_OPCODE_SHOP:
			case Opcodes.C_OPCODE_PRIVATESHOPLIST:
			case Opcodes.C_OPCODE_DRAWAL:
			case Opcodes.C_OPCODE_DEPOSIT:
			case Opcodes.C_OPCODE_AMOUNT:
			case Opcodes.C_OPCODE_NPCACTION:
			case Opcodes.C_OPCODE_SELECTLIST:
			case Opcodes.C_OPCODE_SKILLBUYOK:
			case Opcodes.C_OPCODE_FIX_WEAPON_LIST:
			case Opcodes.C_OPCODE_USESKILL:
			case Opcodes.C_OPCODE_ATTACK:
			case Opcodes.C_OPCODE_ARROWATTACK:
				return true;
			default:
				return false;
		}
	}

	private static void notifyInventoryBusy(L1PcInstance pc) {
		Integer key = Integer.valueOf(pc.getId());
		long now = System.currentTimeMillis();
		Long previous = _busyNoticeTimes.get(key);
		if (previous != null && now - previous.longValue() < BUSY_NOTICE_INTERVAL_MILLIS) {
			return;
		}
		_busyNoticeTimes.put(key, Long.valueOf(now));
		pc.sendPackets(new S_SystemMessage("Inventory is busy while batch enchant is running."));
	}

	private static void storeFastForwardLog(L1PcInstance pc, int itemType2, int itemObjectId,
			int safeEnchant, int oldLevel, int newLevel) {
		int threshold = 0;
		if (itemType2 == 1) {
			threshold = Config.LOGGING_WEAPON_ENCHANT;
		} else if (itemType2 == 2) {
			threshold = Config.LOGGING_ARMOR_ENCHANT;
		}
		if (threshold == 0 || oldLevel == newLevel) {
			return;
		}
		for (int level = oldLevel + 1; level <= newLevel; level++) {
			if (safeEnchant == 0 || level >= threshold) {
				new LogEnchantTable().storeLogEnchant(pc.getId(), itemObjectId, level - 1, level);
			}
		}
	}

	private static void sendUsage(L1PcInstance pc) {
		pc.sendPackets(new S_SystemMessage(
				"Usage: eb <target> <count> [blessed 0|1] [cursed 0|1] | eb status | eb off"));
	}
}
