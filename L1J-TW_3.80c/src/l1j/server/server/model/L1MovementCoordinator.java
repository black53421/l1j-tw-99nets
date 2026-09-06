package l1j.server.server.model;

import l1j.server.server.ActionCodes;
import l1j.server.server.model.Instance.L1DollInstance;
import l1j.server.server.model.Instance.L1DoorInstance;
import l1j.server.server.model.Instance.L1EffectInstance;
import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.L1PlayerMovementCollision.Result;

/**
 * Coordinates walking movement so destination collision validation and tile
 * occupancy publication cannot race with another walking character.
 *
 * This intentionally uses striped tile locks instead of one lock per map.
 * Unrelated movement can proceed concurrently while moves sharing either the
 * source or destination stripe are serialized.
 */
public final class L1MovementCoordinator {

	private static final int TILE_LOCK_COUNT = 256;
	private static final Object[] TILE_LOCKS = new Object[TILE_LOCK_COUNT];

	private static final byte[] HEADING_X = { 0, 1, 1, 1, 0, -1, -1, -1 };
	private static final byte[] HEADING_Y = { -1, -1, 0, 1, 1, 1, 0, -1 };

	static {
		for (int i = 0; i < TILE_LOCKS.length; i++) {
			TILE_LOCKS[i] = new Object();
		}
	}

	private L1MovementCoordinator() {
	}

	public static Result tryMovePlayer(L1PcInstance pc, int targetX,
			int targetY, int heading) {
		final int fromX = pc.getX();
		final int fromY = pc.getY();
		final int sourceLock = lockIndex(pc.getMapId(), fromX, fromY);
		final int targetLock = lockIndex(pc.getMapId(), targetX, targetY);

		if (sourceLock == targetLock) {
			synchronized (TILE_LOCKS[sourceLock]) {
				return movePlayerLocked(pc, fromX, fromY, targetX, targetY,
						heading);
			}
		}

		final int first = Math.min(sourceLock, targetLock);
		final int second = Math.max(sourceLock, targetLock);
		synchronized (TILE_LOCKS[first]) {
			synchronized (TILE_LOCKS[second]) {
				return movePlayerLocked(pc, fromX, fromY, targetX, targetY,
						heading);
			}
		}
	}

	public static boolean tryMoveNpc(L1NpcInstance npc, int heading) {
		if ((heading < 0) || (heading >= HEADING_X.length)) {
			return false;
		}

		final int fromX = npc.getX();
		final int fromY = npc.getY();
		final int targetX = fromX + HEADING_X[heading];
		final int targetY = fromY + HEADING_Y[heading];
		final int sourceLock = lockIndex(npc.getMapId(), fromX, fromY);
		final int targetLock = lockIndex(npc.getMapId(), targetX, targetY);

		if (sourceLock == targetLock) {
			synchronized (TILE_LOCKS[sourceLock]) {
				return moveNpcLocked(npc, fromX, fromY, targetX, targetY,
						heading);
			}
		}

		final int first = Math.min(sourceLock, targetLock);
		final int second = Math.max(sourceLock, targetLock);
		synchronized (TILE_LOCKS[first]) {
			synchronized (TILE_LOCKS[second]) {
				return moveNpcLocked(npc, fromX, fromY, targetX, targetY,
						heading);
			}
		}
	}

	private static Result movePlayerLocked(L1PcInstance pc, int fromX,
			int fromY, int targetX, int targetY, int heading) {
		Result collision = L1PlayerMovementCollision.check(pc, targetX,
				targetY);
		if (collision.isBlocked()) {
			return collision;
		}

		releaseSourceTile(pc, fromX, fromY);
		pc.getLocation().set(targetX, targetY);
		pc.setHeading(heading);
		pc.getMap().setPassable(targetX, targetY, false);
		return collision;
	}

	private static boolean moveNpcLocked(L1NpcInstance npc, int fromX,
			int fromY, int targetX, int targetY, int heading) {
		if (!npc.getMap().isInMap(targetX, targetY)
				|| !npc.getMap().isPassable(fromX, fromY, heading)) {
			return false;
		}

		releaseSourceTile(npc, fromX, fromY);
		npc.setX(targetX);
		npc.setY(targetY);
		npc.setHeading(heading);
		npc.getMap().setPassable(targetX, targetY, false);
		return true;
	}

	private static void releaseSourceTile(L1Character mover, int x, int y) {
		// Player movement is the recovery path for legacy overlaps created
		// before the atomic walking lock was installed. Preserve the blocked
		// bit if another physical character still occupies the old tile.
		if ((mover instanceof L1PcInstance)
				&& hasOtherPhysicalOccupant(mover, x, y)) {
			mover.getMap().setPassable(x, y, false);
			return;
		}
		mover.getMap().setPassable(x, y, true);
	}

	private static boolean hasOtherPhysicalOccupant(L1Character mover, int x,
			int y) {
		for (L1Object object : mover.getKnownObjects()) {
			if ((object == mover) || !(object instanceof L1Character)
					|| (object.getMapId() != mover.getMapId())
					|| (object.getX() != x) || (object.getY() != y)) {
				continue;
			}

			L1Character character = (L1Character) object;
			if (!character.isDead() && isPhysicalOccupant(character)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isPhysicalOccupant(L1Character character) {
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

	private static int lockIndex(int mapId, int x, int y) {
		int hash = 17;
		hash = (31 * hash) + mapId;
		hash = (31 * hash) + x;
		hash = (31 * hash) + y;
		return (hash & 0x7fffffff) % TILE_LOCK_COUNT;
	}
}
