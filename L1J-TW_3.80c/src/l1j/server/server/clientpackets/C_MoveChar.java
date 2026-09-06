/**
 *                            License
 * THE WORK (AS DEFINED BELOW) IS PROVIDED UNDER THE TERMS OF THIS  
 * CREATIVE COMMONS PUBLIC LICENSE ("CCPL" OR "LICENSE"). 
 * THE WORK IS PROTECTED BY COPYRIGHT AND/OR OTHER APPLICABLE LAW.  
 * ANY USE OF THE WORK OTHER THAN AS AUTHORIZED UNDER THIS LICENSE OR  
 * COPYRIGHT LAW IS PROHIBITED.
 * 
 * BY EXERCISING ANY RIGHTS TO THE WORK PROVIDED HERE, YOU ACCEPT AND  
 * AGREE TO BE BOUND BY THE TERMS OF THIS LICENSE. TO THE EXTENT THIS LICENSE  
 * MAY BE CONSIDERED TO BE A CONTRACT, THE LICENSOR GRANTS YOU THE RIGHTS CONTAINED 
 * HERE IN CONSIDERATION OF YOUR ACCEPTANCE OF SUCH TERMS AND CONDITIONS.
 * 
 */
package l1j.server.server.clientpackets;

import static l1j.server.server.model.Instance.L1PcInstance.REGENSTATE_MOVE;
import static l1j.server.server.model.skill.L1SkillId.ABSOLUTE_BARRIER;
import static l1j.server.server.model.skill.L1SkillId.MEDITATION;
import l1j.server.Config;
import l1j.server.server.ClientThread;
import l1j.server.server.model.AcceleratorChecker;
import l1j.server.server.model.Dungeon;
import l1j.server.server.model.DungeonRandom;
import l1j.server.server.model.L1Character;
import l1j.server.server.model.L1Object;
import l1j.server.server.model.L1MovementCoordinator;
import l1j.server.server.model.L1PlayerMovementCollision;
import l1j.server.server.model.L1PlayerMovementCollision.Result;
import l1j.server.server.model.L1Trade;
import l1j.server.server.model.L1World;
import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.trap.L1WorldTraps;
import l1j.server.server.serverpackets.S_MoveCharPacket;
import l1j.server.server.serverpackets.S_OwnCharPack;
import l1j.server.server.serverpackets.S_SystemMessage;

// Referenced classes of package l1j.server.server.clientpackets:
// ClientBasePacket

/**
 * 處理收到由客戶端傳來移動角色的封包
 */
public class C_MoveChar extends ClientBasePacket {

	private static final byte HEADING_TABLE_X[] =
	{ 0, 1, 1, 1, 0, -1, -1, -1 };

	private static final byte HEADING_TABLE_Y[] =
	{ -1, -1, 0, 1, 1, 1, 0, -1 };

	private static final int CLIENT_LANGUAGE = Config.CLIENT_LANGUAGE;

	// 地圖編號的研究
	@SuppressWarnings("unused")
	private void sendMapTileLog(L1PcInstance pc) {
		pc.sendPackets(new S_SystemMessage(pc.getMap().toString(pc.getLocation())));
	}

	// 移動
	public C_MoveChar(byte decrypt[], ClientThread client) throws Exception {
		super(decrypt);
		
		L1PcInstance pc = client.getActiveChar();
		if ((pc == null) || pc.isTeleport()) { // 傳送中
			return;
		}
		
		int locx = readH();
		int locy = readH();
		int heading = readC();

		if (CLIENT_LANGUAGE == 3) { // Taiwan Only
			heading ^= 0x49;
			locx = pc.getX();
			locy = pc.getY();
		}

		if ((heading < 0) || (heading >= HEADING_TABLE_X.length)) {
			return;
		}

		int fromX = pc.getX();
		int fromY = pc.getY();
		int targetX = locx + HEADING_TABLE_X[heading];
		int targetY = locy + HEADING_TABLE_Y[heading];
		Result collision = L1PlayerMovementCollision.check(pc, targetX, targetY);
		if (collision.isBlocked()) {
			traceMovePacket(pc, fromX, fromY, targetX, targetY, heading, collision);
			correctClientPosition(pc);
			return;
		}

		// 檢查移動的時間間隔
		if (Config.CHECK_MOVE_INTERVAL) {
			int result;
			result = pc.getAcceleratorChecker().checkInterval(AcceleratorChecker.ACT_TYPE.MOVE);
			if (result == AcceleratorChecker.R_DISPOSED) {
				return;
			}
		}
		
		locx = targetX;
		locy = targetY;

		if (Dungeon.getInstance().dg(locx, locy, pc.getMap().getId(), pc)) { // 傳點
			return;
		}
		if (DungeonRandom.getInstance().dg(locx, locy, pc.getMap().getId(), pc)) { // 取得隨機傳送地點
			return;
		}

		// Revalidate and publish the movement atomically with NPC walking.
		// The preliminary check above keeps blocked moves out of the normal
		// side-effect path; this final check closes the simultaneous-move race.
		collision = L1MovementCoordinator.tryMovePlayer(pc, locx, locy, heading);
		traceMovePacket(pc, fromX, fromY, targetX, targetY, heading, collision);
		if (collision.isBlocked()) {
			correctClientPosition(pc);
			return;
		}

		// Apply movement side effects only after the atomic move succeeds.
		if (pc.getTradeID() != 0) {
			L1Trade trade = new L1Trade();
			trade.TradeCancel(pc);
		}
		if (pc.hasSkillEffect(MEDITATION)) {
			pc.removeSkillEffect(MEDITATION);
		}
		pc.setCallClanId(0);
		if (!pc.hasSkillEffect(ABSOLUTE_BARRIER)) {
			pc.setRegenState(REGENSTATE_MOVE);
		}
		if (pc.isGmInvis() || pc.isGhost()) {}
		else if (pc.isInvisble()) {
			pc.broadcastPacketForFindInvis(new S_MoveCharPacket(pc), true);
		}
		else {
			pc.broadcastPacket(new S_MoveCharPacket(pc));
		}

		traceZoneTransition(pc);

		// sendMapTileLog(pc); //發送信息的目的地瓦（為調查地圖）
		// 寵物競速-判斷圈數
		l1j.server.server.model.game.L1PolyRace.getInstance().checkLapFinish(pc);
		L1WorldTraps.getInstance().onPlayerMoved(pc);

		// user.UpdateObject(); // 可視範囲内の全オブジェクト更新
	}

	private static void traceMovePacket(L1PcInstance pc, int fromX, int fromY,
			int targetX, int targetY, int heading, Result collision) {
		if (!pc.isMoveTraceEnabled()) {
			return;
		}

		boolean inMap = pc.getMap().isInMap(targetX, targetY);
		int tile = inMap ? pc.getMap().getOriginalTile(targetX, targetY) : -1;
		boolean pathPassable = inMap && pc.getMap().isPassable(fromX, fromY, heading);
		String targetZone = inMap ? zoneNameAt(pc, targetX, targetY) : "OUTSIDE";
		String objects = describeCharactersAt(pc, targetX, targetY);

		String tileText = inMap ? String.format("0x%02X(%d)", tile, tile) : "N/A";
		String blocker = describeBlocker(collision.getBlocker());
		String message = String.format(
				"[MOVETRACE] received=yes map=%d from=%d,%d to=%d,%d heading=%d zone=%s->%s tile=%s pathPassable=%s decision=%s reason=%s blocker=%s obj=%s",
				pc.getMapId(), fromX, fromY, targetX, targetY, heading,
				zoneName(pc.getZoneType()), targetZone, tileText,
				Boolean.toString(pathPassable), collision.isBlocked() ? "BLOCK" : "ALLOW",
				collision.getReason(), blocker, objects);
		pc.sendPackets(new S_SystemMessage(message));
	}

	private static void correctClientPosition(L1PcInstance pc) {
		pc.sendPackets(new S_OwnCharPack(pc));
	}

	private static String describeBlocker(L1Character blocker) {
		if (blocker == null) {
			return "none";
		}

		StringBuilder result = new StringBuilder();
		result.append(blocker.getClass().getSimpleName())
				.append(':').append(blocker.getName())
				.append('#').append(blocker.getId());
		if (blocker instanceof L1NpcInstance) {
			result.append("/npc=")
					.append(((L1NpcInstance) blocker).getNpcTemplate().get_npcId());
		}
		return result.toString();
	}

	private static String describeCharactersAt(L1PcInstance pc, int x, int y) {
		StringBuilder result = new StringBuilder();
		int count = 0;

		for (L1Object object : L1World.getInstance().getVisibleObjects(pc)) {
			if (!(object instanceof L1Character)) {
				continue;
			}
			if ((object.getX() != x) || (object.getY() != y)) {
				continue;
			}

			if (count > 0) {
				result.append(',');
			}
			if (count >= 3) {
				result.append("...");
				break;
			}

			L1Character character = (L1Character) object;
			result.append(object.getClass().getSimpleName())
					.append(':').append(character.getName())
					.append('#').append(object.getId());
			if (object instanceof L1NpcInstance) {
				result.append("/npc=")
						.append(((L1NpcInstance) object).getNpcTemplate().get_npcId());
			}
			count++;
		}

		return result.length() == 0 ? "none" : result.toString();
	}

	private static String zoneNameAt(L1PcInstance pc, int x, int y) {
		if (pc.getMap().isSafetyZone(x, y)) {
			return "SAFE";
		}
		if (pc.getMap().isCombatZone(x, y)) {
			return "COMBAT";
		}
		return "NORMAL";
	}

	private static void traceZoneTransition(L1PcInstance pc) {
		if (!pc.isZoneTraceEnabled()) {
			return;
		}

		short mapId = pc.getMapId();
		int zoneType = pc.getZoneType();
		short previousMapId = pc.getZoneTraceLastMapId();
		int previousZoneType = pc.getZoneTraceLastZoneType();

		if ((mapId == previousMapId) && (zoneType == previousZoneType)) {
			return;
		}

		int tile = pc.getMap().getOriginalTile(pc.getX(), pc.getY());
		String message;
		if (mapId != previousMapId) {
			message = String.format(
					"[ZONETRACE] MAP %d -> %d zone=%s x=%d y=%d tile=0x%02X(%d) accepted=yes",
					previousMapId, mapId, zoneName(zoneType), pc.getX(), pc.getY(), tile, tile);
		}
		else {
			message = String.format(
					"[ZONETRACE] %s -> %s map=%d x=%d y=%d tile=0x%02X(%d) accepted=yes",
					zoneName(previousZoneType), zoneName(zoneType), mapId, pc.getX(), pc.getY(), tile, tile);
		}

		pc.sendPackets(new S_SystemMessage(message));
		pc.updateZoneTraceState();
	}

	private static String zoneName(int zoneType) {
		if (zoneType == 1) {
			return "SAFE";
		}
		if (zoneType == -1) {
			return "COMBAT";
		}
		return "NORMAL";
	}
}