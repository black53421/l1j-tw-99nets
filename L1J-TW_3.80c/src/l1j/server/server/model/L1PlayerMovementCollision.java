package l1j.server.server.model;

import l1j.server.Config;
import l1j.server.server.ActionCodes;
import l1j.server.server.model.Instance.L1DollInstance;
import l1j.server.server.model.Instance.L1DoorInstance;
import l1j.server.server.model.Instance.L1EffectInstance;
import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;

/**
 * Server-side physical object collision policy for player movement.
 *
 * Phase 1 blocks physical characters without allowing pet/summon overlap.
 * Terrain validation and multi-occupant tile accounting are intentionally
 * deferred so client position correction can be verified independently.
 */
public final class L1PlayerMovementCollision {

	public static final class Result {
		private final boolean _blocked;
		private final String _reason;
		private final L1Character _blocker;

		private Result(boolean blocked, String reason, L1Character blocker) {
			_blocked = blocked;
			_reason = reason;
			_blocker = blocker;
		}

		public boolean isBlocked() {
			return _blocked;
		}

		public String getReason() {
			return _reason;
		}

		public L1Character getBlocker() {
			return _blocker;
		}
	}

	private static final Result ALLOW_DISABLED = new Result(false, "DISABLED", null);
	private static final Result ALLOW_GHOST = new Result(false, "GHOST_BYPASS", null);
	private static final Result ALLOW_CLEAR = new Result(false, "CLEAR", null);

	private L1PlayerMovementCollision() {
	}

	public static Result check(L1PcInstance pc, int targetX, int targetY) {
		if (!Config.SERVER_PLAYER_COLLISION_ENABLED) {
			return ALLOW_DISABLED;
		}

		if (pc.isGhost()) {
			return ALLOW_GHOST;
		}

		if (!pc.getMap().isInMap(targetX, targetY)) {
			return new Result(true, "OUTSIDE_MAP", null);
		}

		L1Character blocker = findPhysicalBlocker(pc, targetX, targetY);
		if (blocker != null) {
			return new Result(true, "CHARACTER", blocker);
		}

		return ALLOW_CLEAR;
	}

	private static L1Character findPhysicalBlocker(L1PcInstance pc, int x, int y) {
		for (L1Object object : pc.getKnownObjects()) {
			if ((object.getMapId() != pc.getMapId())
					|| (object.getX() != x) || (object.getY() != y)) {
				continue;
			}
			if (!(object instanceof L1Character)) {
				continue;
			}

			L1Character character = (L1Character) object;
			if ((character == pc) || character.isDead() || !isPhysicalBlocker(character)) {
				continue;
			}

			return character;
		}

		return null;
	}

	private static boolean isPhysicalBlocker(L1Character character) {
		if ((character instanceof L1EffectInstance)
				|| (character instanceof L1DollInstance)) {
			return false;
		}

		if (character instanceof L1DoorInstance) {
			L1DoorInstance door = (L1DoorInstance) character;
			return door.getOpenStatus() != ActionCodes.ACTION_Open;
		}

		return (character instanceof L1PcInstance)
				|| (character instanceof L1NpcInstance);
	}
}
