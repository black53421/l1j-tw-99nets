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
package l1j.server.server.utils;

import static l1j.server.server.model.skill.L1SkillId.MEDITATION;
import static l1j.server.server.model.skill.L1SkillId.WIND_SHACKLE;

import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import l1j.server.Config;
import l1j.server.server.model.L1Clan;
import l1j.server.server.model.L1CompanionTeleportPlacement;
import l1j.server.server.model.L1DragonSlayer;
import l1j.server.server.model.L1Location;
import l1j.server.server.model.L1TileOccupancy;
import l1j.server.server.model.L1World;
import l1j.server.server.model.Instance.L1DollInstance;
import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.Instance.L1PetInstance;
import l1j.server.server.model.Instance.L1SummonInstance;
import l1j.server.server.model.map.L1Map;
import l1j.server.server.model.map.L1WorldMap;
import l1j.server.server.serverpackets.S_CharVisualUpdate;
import l1j.server.server.serverpackets.S_DollPack;
import l1j.server.server.serverpackets.S_MapID;
import l1j.server.server.serverpackets.S_OtherCharPacks;
import l1j.server.server.serverpackets.S_OwnCharPack;
import l1j.server.server.serverpackets.S_PetPack;
import l1j.server.server.serverpackets.S_SkillIconWindShackle;
import l1j.server.server.serverpackets.S_SummonPack;

// Referenced classes of package l1j.server.server.utils:
// FaceToFace

public class Teleportation {
	private static final Logger _log = Logger.getLogger(Teleportation.class.getName());

	private Teleportation() {
	}

	public static void actionTeleportation(final L1PcInstance pc) {
		if (pc.isDead() || pc.isTeleport()) {
			return;
		}

		int x = pc.getTeleportX();
		int y = pc.getTeleportY();
		short mapId = pc.getTeleportMapId();
		final int head = pc.getTeleportHeading();

		// テレポート先が不正であれば元の座標へ(GMは除く)
		final L1Map map = L1WorldMap.getInstance().getMap(mapId);

		if (!map.isInMap(x, y) && !pc.isGm()) {
			x = pc.getX();
			y = pc.getY();
			mapId = pc.getMapId();
		}

		pc.setTeleport(true);
		final int oldX = pc.getX();
		final int oldY = pc.getY();

		try {
		final L1Clan clan = L1World.getInstance().getClan(pc.getClanname());
		if (clan != null) {
			if (clan.getWarehouseUsingChar() == pc.getId()) { // 自キャラがクラン倉庫使用中
				clan.setWarehouseUsingChar(0); // クラン倉庫のロックを解除
			}
		}

		L1TileOccupancy.releaseTile(pc, oldX, oldY);
		L1World.getInstance().moveVisibleObject(pc, mapId);
		pc.setLocation(x, y, mapId);
		L1TileOccupancy.occupyTile(pc, pc.getX(), pc.getY());
		pc.setHeading(head);
		pc.sendPackets(new S_MapID(pc.getMapId(), pc.getMap().isUnderwater()));

		if (pc.isReserveGhost()) { // ゴースト状態解除
			pc.endGhost();
		}
		if (pc.isGhost() || pc.isGmInvis()) {}
		else if (pc.isInvisble()) {
			pc.broadcastPacketForFindInvis(new S_OtherCharPacks(pc, true), true);
		}
		else {
			pc.broadcastPacket(new S_OtherCharPacks(pc));
		}
		pc.sendPackets(new S_OwnCharPack(pc));

		pc.removeAllKnownObjects();
		pc.sendVisualEffectAtTeleport(); // クラウン、毒、水中等の視覚効果を表示
		pc.updateObject();
		// spr番号6310, 5641の変身中にテレポートするとテレポート後に移動できなくなる
		// 武器を着脱すると移動できるようになるため、S_CharVisualUpdateを送信する
		pc.sendPackets(new S_CharVisualUpdate(pc));

		pc.killSkillEffectTimer(MEDITATION);
		pc.setCallClanId(0); // コールクランを唱えた後に移動すると召喚無効

		/*
		 * subjects ペットとサモンのテレポート先画面内へ居たプレイヤー。
		 * 各ペット毎にUpdateObjectを行う方がコード上ではスマートだが、
		 * ネットワーク負荷が大きくなる為、一旦Setへ格納して最後にまとめてUpdateObjectする。
		 */
		final HashSet<L1PcInstance> subjects = new HashSet<L1PcInstance>();
		subjects.add(pc);

		if (!pc.isGhost()) {
			if (pc.getMap().isTakePets()) {
				final boolean petMatchMap = isPetMatchMap(pc.getMapId());
				final L1CompanionTeleportPlacement companionPlacement;
				if (Config.COMPANION_TELEPORT_REACHABLE_PLACEMENT_ENABLED && !petMatchMap) {
					companionPlacement = new L1CompanionTeleportPlacement(pc,
							Config.COMPANION_TELEPORT_EMERGENCY_PLACEMENT_RADIUS,
							Config.COMPANION_TELEPORT_MAX_PATH_DETOUR,
							Config.COMPANION_TELEPORT_MAX_PATH_LENGTH);
				}
				else {
					companionPlacement = null;
				}

				// ペットとサモンも一緒に移動させる。
				for (final L1NpcInstance petNpc : pc.getPetList().values()) {
					int nx;
					int ny;
					if (petMatchMap) { // ペットマッチ会場
						nx = 32799 + Random.nextInt(5) - 3;
						ny = 32864 + Random.nextInt(5) - 3;
					}
					else if (companionPlacement != null) {
						L1Location loc = companionPlacement.findFreeLocation(
								Config.COMPANION_TELEPORT_PLACEMENT_RADIUS);
						if ((loc == null) && Config.COMPANION_TELEPORT_OVERLAP_FALLBACK_ENABLED) {
							loc = companionPlacement.findOverlapLocation();
						}
						if (loc != null) {
							nx = loc.getX();
							ny = loc.getY();
						}
						else {
							nx = pc.getX();
							ny = pc.getY();
							_log.warning("[CompanionTeleport] No safe companion tile for npcId="
									+ petNpc.getId() + " owner=" + pc.getName() + " mapId="
									+ pc.getMapId() + " x=" + pc.getX() + " y=" + pc.getY()
									+ "; using owner tile as the safety fallback.");
						}
					}
					else {
						final L1Location loc = pc.getLocation().randomLocation(3, false);
						nx = loc.getX();
						ny = loc.getY();
					}
					teleport(petNpc, nx, ny, mapId, head);
					if (petNpc instanceof L1SummonInstance) { // サモンモンスター
						final L1SummonInstance summon = (L1SummonInstance) petNpc;
						pc.sendPackets(new S_SummonPack(summon, pc));
					}
					else if (petNpc instanceof L1PetInstance) { // ペット
						final L1PetInstance pet = (L1PetInstance) petNpc;
						pc.sendPackets(new S_PetPack(pet, pc));
					}

					for (final L1PcInstance visiblePc : L1World.getInstance().getVisiblePlayer(petNpc)) {
						// テレポート元と先に同じPCが居た場合、正しく更新されない為、一度removeする。
						visiblePc.removeKnownObject(petNpc);
						subjects.add(visiblePc);
					}
				}

				// マジックドールも一緒に移動させる。
				for (final L1DollInstance doll : pc.getDollList().values()) {
					// テレポート先の設定
					final L1Location loc = pc.getLocation().randomLocation(3, false);
					final int nx = loc.getX();
					final int ny = loc.getY();

					teleport(doll, nx, ny, mapId, head);
					pc.sendPackets(new S_DollPack(doll));

					for (final L1PcInstance visiblePc : L1World.getInstance().getVisiblePlayer(doll)) {
						// テレポート元と先に同じPCが居た場合、正しく更新されない為、一度removeする。
						visiblePc.removeKnownObject(doll);
						subjects.add(visiblePc);
					}
				}
			}
			else {
				for (final L1DollInstance doll : pc.getDollList().values()) {
					// テレポート先の設定
					final L1Location loc = pc.getLocation().randomLocation(3, false);
					final int nx = loc.getX();
					final int ny = loc.getY();

					teleport(doll, nx, ny, mapId, head);
					pc.sendPackets(new S_DollPack(doll));

					for (final L1PcInstance visiblePc : L1World.getInstance().getVisiblePlayer(doll)) {
						// テレポート元と先に同じPCが居た場合、正しく更新されない為、一度removeする。
						visiblePc.removeKnownObject(doll);
						subjects.add(visiblePc);
					}
				}
			}
		}

		for (final L1PcInstance updatePc : subjects) {
			updatePc.updateObject();
		}

		if (pc.hasSkillEffect(WIND_SHACKLE)) {
			pc.sendPackets(new S_SkillIconWindShackle(pc.getId(), pc.getSkillEffectTimeSec(WIND_SHACKLE)));
		}

		// 副本編號與副本地圖不符
		if (pc.getPortalNumber() != -1
				&& (pc.getMapId() !=  (1005 + pc.getPortalNumber()))) {
			L1DragonSlayer.getInstance().removePlayer(pc, pc.getPortalNumber());
			pc.setPortalNumber(-1);
		}
		// 離開旅館地圖，旅館鑰匙歸零
		if (pc.getMapId() <= 10000 && pc.getInnKeyId() != 0) {
			pc.setInnKeyId(0);
		}
		} catch (RuntimeException e) {
			_log.log(Level.SEVERE, "Teleportation failed for character " + pc.getName()
					+ " to (" + pc.getTeleportX() + ", " + pc.getTeleportY() + ", "
					+ pc.getTeleportMapId() + ")", e);
			throw e;
		} finally {
			pc.setTeleport(false);
		}
	}

	private static boolean isPetMatchMap(int mapId) {
		return (mapId == 5125) || (mapId == 5131) || (mapId == 5132)
				|| (mapId == 5133) || (mapId == 5134);
	}

	private static void teleport(L1NpcInstance npc, int x, int y, short map, int head) {
		L1World.getInstance().moveVisibleObject(npc, map);
		L1TileOccupancy.releaseTile(npc, npc.getX(), npc.getY());
		npc.setX(x);
		npc.setY(y);
		npc.setMap(map);
		npc.setHeading(head);
		L1TileOccupancy.occupyTile(npc, npc.getX(), npc.getY());
	}

}
