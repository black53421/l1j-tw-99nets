package l1j.server.server.model;

import l1j.server.Config;
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
	private static final int[] CARDINAL_HEADINGS = { 0, 2, 4, 6 };

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

	/**
	 * Moves a pet/summon while it is following its master. Normal collision
	 * rules are used first. If the step is blocked only by same-owner
	 * companions in a narrow passage, transient overlap is allowed.
	 */
	public static boolean tryMoveFollowingCompanion(L1NpcInstance npc,
			int heading) {
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
				return moveFollowingCompanionLocked(npc, fromX, fromY,
						targetX, targetY, heading);
			}
		}

		final int first = Math.min(sourceLock, targetLock);
		final int second = Math.max(sourceLock, targetLock);
		synchronized (TILE_LOCKS[first]) {
			synchronized (TILE_LOCKS[second]) {
				return moveFollowingCompanionLocked(npc, fromX, fromY,
						targetX, targetY, heading);
			}
		}
	}

	/**
	 * Checks whether a route-planning step may temporarily pass through a
	 * same-owner companion in a narrow passage. This method does not move the
	 * NPC and is also used by companion-only path planning.
	 */
	public static boolean isCompanionNarrowPassStep(L1NpcInstance npc,
			int fromX, int fromY, int heading) {
		if ((heading < 0) || (heading >= HEADING_X.length)) {
			return false;
		}

		int targetX = fromX + HEADING_X[heading];
		int targetY = fromY + HEADING_Y[heading];
		return canUseCompanionNarrowPass(npc, fromX, fromY, targetX,
				targetY, heading);
	}

	/**
	 * Returns true when the requested step is terrain-passable but currently
	 * occupied only by companions that have the same master as the mover.
	 * This check does not allow the move by itself.
	 */
	public static boolean isCompanionSameOwnerBlockerStep(L1NpcInstance npc,
			int fromX, int fromY, int heading) {
		if ((heading < 0) || (heading >= HEADING_X.length)) {
			return false;
		}

		int targetX = fromX + HEADING_X[heading];
		int targetY = fromY + HEADING_Y[heading];
		if (!npc.getMap().isInMap(targetX, targetY)
				|| !npc.getMap().isTerrainPassable(fromX, fromY, heading)) {
			return false;
		}
		return L1TileOccupancy.isOccupiedOnlyBySameOwnerCompanions(
				npc, targetX, targetY);
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
		L1TileOccupancy.occupyTile(pc, targetX, targetY);
		pc.recordCompanionFollowStep(fromX, fromY, targetX, targetY);
		return collision;
	}

	private static boolean moveNpcLocked(L1NpcInstance npc, int fromX,
			int fromY, int targetX, int targetY, int heading) {
		if (!npc.getMap().isInMap(targetX, targetY)
				|| !npc.getMap().isPassable(fromX, fromY, heading)) {
			return false;
		}

		commitNpcMove(npc, fromX, fromY, targetX, targetY, heading);
		return true;
	}

	private static boolean moveFollowingCompanionLocked(L1NpcInstance npc,
			int fromX, int fromY, int targetX, int targetY, int heading) {
		if (!npc.getMap().isInMap(targetX, targetY)) {
			return false;
		}

		if (npc.getMap().isPassable(fromX, fromY, heading)) {
			commitNpcMove(npc, fromX, fromY, targetX, targetY, heading);
			return true;
		}

		if (!canUseCompanionNarrowPass(npc, fromX, fromY, targetX,
				targetY, heading)) {
			return false;
		}

		commitNpcMove(npc, fromX, fromY, targetX, targetY, heading);
		return true;
	}

	private static void commitNpcMove(L1NpcInstance npc, int fromX,
			int fromY, int targetX, int targetY, int heading) {
		releaseSourceTile(npc, fromX, fromY);
		npc.setX(targetX);
		npc.setY(targetY);
		npc.setHeading(heading);
		L1TileOccupancy.occupyTile(npc, targetX, targetY);
	}

	private static boolean canUseCompanionNarrowPass(L1NpcInstance npc,
			int fromX, int fromY, int targetX, int targetY, int heading) {
		if (!Config.COMPANION_NARROW_PASS_ENABLED
				|| !npc.getMap().isInMap(targetX, targetY)
				|| !npc.getMap().isTerrainPassable(fromX, fromY, heading)
				|| !isNarrowPassageTransition(npc, fromX, fromY, targetX,
						targetY)) {
			return false;
		}

		return L1TileOccupancy.isOccupiedOnlyBySameOwnerCompanions(
				npc, targetX, targetY);
	}

	private static boolean isNarrowPassageTransition(L1NpcInstance npc,
			int fromX, int fromY, int targetX, int targetY) {
		if (isNarrowPassageTile(npc, fromX, fromY)
				|| isNarrowPassageTile(npc, targetX, targetY)) {
			return true;
		}

		int lookAhead = Config.COMPANION_NARROW_PASS_LOOK_AHEAD_STEPS;
		return (lookAhead > 0)
				&& (isConnectedToNarrowPassage(npc, fromX, fromY, lookAhead)
						|| isConnectedToNarrowPassage(npc, targetX, targetY, lookAhead));
	}

	private static boolean isConnectedToNarrowPassage(L1NpcInstance npc,
			int x, int y, int remainingSteps) {
		if (remainingSteps <= 0) {
			return false;
		}

		for (int heading : CARDINAL_HEADINGS) {
			if (!npc.getMap().isTerrainPassable(x, y, heading)) {
				continue;
			}
			int nextX = x + HEADING_X[heading];
			int nextY = y + HEADING_Y[heading];
			if (!npc.getMap().isInMap(nextX, nextY)) {
				continue;
			}
			if (isNarrowPassageTile(npc, nextX, nextY)) {
				return true;
			}
			if ((remainingSteps > 1)
					&& isConnectedToNarrowPassage(npc, nextX, nextY,
							remainingSteps - 1)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isNarrowPassageTile(L1NpcInstance npc, int x,
			int y) {
		if (!npc.getMap().isInMap(x, y)) {
			return false;
		}

		int exits = 0;
		for (int heading : CARDINAL_HEADINGS) {
			if (npc.getMap().isTerrainPassable(x, y, heading)) {
				exits++;
				if (exits > 2) {
					return false;
				}
			}
		}
		return exits > 0;
	}

	private static void releaseSourceTile(L1Character mover, int x, int y) {
		L1TileOccupancy.releaseTile(mover, x, y);
	}

	private static int lockIndex(int mapId, int x, int y) {
		int hash = 17;
		hash = (31 * hash) + mapId;
		hash = (31 * hash) + x;
		hash = (31 * hash) + y;
		return (hash & 0x7fffffff) % TILE_LOCK_COUNT;
	}
}
