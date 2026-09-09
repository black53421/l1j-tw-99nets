package l1j.server.server.model;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import l1j.server.server.ActionCodes;
import l1j.server.server.model.Instance.L1DollInstance;
import l1j.server.server.model.Instance.L1DoorInstance;
import l1j.server.server.model.Instance.L1EffectInstance;
import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.Instance.L1PetInstance;
import l1j.server.server.model.Instance.L1SummonInstance;

/**
 * Preserves dynamic tile blocking while multiple physical characters
 * temporarily occupy the same tile.
 *
 * Normal single-occupant NPC movement stays on the O(1) passability path.
 * World object scans are limited to first-time overlap detection. Player
 * movement keeps the existing known-object fallback for legacy overlaps.
 */
public final class L1TileOccupancy {

	private static final int TILE_LOCK_COUNT = 256;
	private static final Object[] TILE_LOCKS = new Object[TILE_LOCK_COUNT];
	private static final Map<Long, Set<Integer>> SHARED_TILES =
			new ConcurrentHashMap<Long, Set<Integer>>();

	static {
		for (int i = 0; i < TILE_LOCKS.length; i++) {
			TILE_LOCKS[i] = new Object();
		}
	}

	private L1TileOccupancy() {
	}

	/**
	 * Marks a tile occupied. If the tile already contains another live
	 * physical character, all occupants are registered for safe release.
	 */
	public static void occupyTile(L1Character occupant, int x, int y) {
		final long key = tileKey(occupant.getMapId(), x, y);
		synchronized (TILE_LOCKS[lockIndex(key)]) {
			Set<Integer> occupants = SHARED_TILES.get(key);
			if (occupants != null) {
				occupants.add(occupant.getId());
			}
			else if (!occupant.getMap().isPassable(x, y)) {
				occupants = findOtherPhysicalOccupants(occupant, x, y);
				if (!occupants.isEmpty()) {
					occupants.add(occupant.getId());
					SHARED_TILES.put(key, occupants);
				}
			}

			occupant.getMap().setPassable(x, y, false);
		}
	}

	/**
	 * Releases one occupant from a tile. A tile stays blocked until every
	 * registered physical occupant has released it.
	 */
	public static void releaseTile(L1Character occupant, int x, int y) {
		final long key = tileKey(occupant.getMapId(), x, y);
		synchronized (TILE_LOCKS[lockIndex(key)]) {
			Set<Integer> occupants = SHARED_TILES.get(key);
			if (occupants != null) {
				occupants.remove(occupant.getId());
				if (occupants.isEmpty()) {
					SHARED_TILES.remove(key);
					occupant.getMap().setPassable(x, y, true);
				}
				else {
					occupant.getMap().setPassable(x, y, false);
				}
				return;
			}

			if (occupant instanceof L1PcInstance) {
				occupants = findKnownPhysicalOccupants(occupant, x, y);
				if (!occupants.isEmpty()) {
					SHARED_TILES.put(key, occupants);
					occupant.getMap().setPassable(x, y, false);
					return;
				}
			}

			occupant.getMap().setPassable(x, y, true);
		}
	}

	/**
	 * Returns true only when the target tile is occupied by one or more live
	 * pets/summons owned by the same master as the moving companion and no
	 * other physical character is present.
	 */
	static boolean isOccupiedOnlyBySameOwnerCompanions(
			L1NpcInstance mover, int x, int y) {
		if (!isCompanion(mover) || (mover.getMaster() == null)) {
			return false;
		}

		L1Character master = mover.getMaster();
		boolean hasCandidate = false;
		Object[] companions = master.getPetList().values().toArray();
		for (Object object : companions) {
			if (!(object instanceof L1NpcInstance)) {
				continue;
			}
			L1NpcInstance companion = (L1NpcInstance) object;
			if ((companion != mover) && !companion.isDead()
					&& (companion.getMapId() == mover.getMapId())
					&& (companion.getX() == x) && (companion.getY() == y)) {
				hasCandidate = true;
				break;
			}
		}
		if (!hasCandidate) {
			return false;
		}

		boolean foundSameOwnerCompanion = false;
		for (L1Object object : L1World.getInstance().getObject()) {
			if ((object == mover) || !(object instanceof L1Character)
					|| (object.getMapId() != mover.getMapId())
					|| (object.getX() != x) || (object.getY() != y)) {
				continue;
			}

			L1Character character = (L1Character) object;
			if (character.isDead() || !isPhysicalOccupant(character)) {
				continue;
			}

			if (!(character instanceof L1NpcInstance)
					|| !isCompanion((L1NpcInstance) character)) {
				return false;
			}

			L1Character otherMaster = ((L1NpcInstance) character).getMaster();
			if ((otherMaster == null) || (otherMaster.getId() != master.getId())) {
				return false;
			}
			foundSameOwnerCompanion = true;
		}

		return foundSameOwnerCompanion;
	}

	private static boolean isCompanion(L1NpcInstance npc) {
		return (npc instanceof L1PetInstance)
				|| (npc instanceof L1SummonInstance);
	}

	private static Set<Integer> findOtherPhysicalOccupants(
			L1Character occupant, int x, int y) {
		Set<Integer> result = new HashSet<Integer>();
		for (L1Object object : L1World.getInstance().getObject()) {
			if (isOtherPhysicalOccupant(occupant, object, x, y)) {
				result.add(object.getId());
			}
		}
		return result;
	}

	private static Set<Integer> findKnownPhysicalOccupants(
			L1Character occupant, int x, int y) {
		Set<Integer> result = new HashSet<Integer>();
		for (L1Object object : occupant.getKnownObjects()) {
			if (isOtherPhysicalOccupant(occupant, object, x, y)) {
				result.add(object.getId());
			}
		}
		return result;
	}

	private static boolean isOtherPhysicalOccupant(L1Character occupant,
			L1Object object, int x, int y) {
		if ((object == occupant) || !(object instanceof L1Character)
				|| (object.getMapId() != occupant.getMapId())
				|| (object.getX() != x) || (object.getY() != y)) {
			return false;
		}

		L1Character character = (L1Character) object;
		return !character.isDead() && isPhysicalOccupant(character);
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

	private static long tileKey(int mapId, int x, int y) {
		return (((long) mapId & 0xffffL) << 32)
				| (((long) x & 0xffffL) << 16)
				| ((long) y & 0xffffL);
	}

	private static int lockIndex(long key) {
		long mixed = key ^ (key >>> 32);
		return (int) (mixed & (TILE_LOCK_COUNT - 1));
	}
}
