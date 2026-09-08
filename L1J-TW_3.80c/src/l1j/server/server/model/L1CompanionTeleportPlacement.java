package l1j.server.server.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import l1j.server.server.ActionCodes;
import l1j.server.server.model.Instance.L1DoorInstance;
import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.map.L1Map;
import l1j.server.server.utils.Random;

/**
 * Finds companion teleport destinations that are connected to the owner's
 * destination by local walkable terrain.
 *
 * Dynamic character occupancy is ignored while building the connected terrain
 * area, but a destination tile must be free when it is selected. Closed doors
 * are treated as connectivity barriers so companions are not placed across a
 * door that they cannot currently cross. Candidate destinations are also
 * limited by shortest-path length so a geometrically close tile that requires
 * a long detour around a wall is not selected.
 */
public final class L1CompanionTeleportPlacement {

	private static final int[] HEADING_X = { 0, 1, 1, 1, 0, -1, -1, -1 };
	private static final int[] HEADING_Y = { -1, -1, 0, 1, 1, 1, 0, -1 };

	private final L1PcInstance _owner;
	private final L1Map _map;
	private final int _originX;
	private final int _originY;
	private final int _maxRadius;
	private final int _maxPathDetour;
	private final int _maxPathLength;
	private final List<Candidate> _candidates = new ArrayList<Candidate>();
	private final List<Candidate> _overlapCandidates = new ArrayList<Candidate>();
	private final Set<Long> _closedDoorTiles = new HashSet<Long>();

	public L1CompanionTeleportPlacement(L1PcInstance owner, int maxRadius,
			int maxPathDetour, int maxPathLength) {
		if (owner == null) {
			throw new IllegalArgumentException("owner must not be null");
		}
		if (maxRadius < 1) {
			throw new IllegalArgumentException("maxRadius must be 1 or greater");
		}
		if (maxPathDetour < 0) {
			throw new IllegalArgumentException("maxPathDetour must be 0 or greater");
		}
		if (maxPathLength < 1) {
			throw new IllegalArgumentException("maxPathLength must be 1 or greater");
		}

		_owner = owner;
		_map = owner.getMap();
		_originX = owner.getX();
		_originY = owner.getY();
		_maxRadius = maxRadius;
		_maxPathDetour = maxPathDetour;
		_maxPathLength = maxPathLength;

		collectClosedDoors(owner);
		buildReachableCandidates();
	}

	/**
	 * Returns a currently free destination. The preferred radius is searched
	 * first; if no tile is available, the search automatically expands to the
	 * maximum radius supplied to the constructor.
	 */
	public L1Location findFreeLocation(int preferredRadius) {
		int radius = preferredRadius;
		if (radius < 1) {
			radius = 1;
		}
		if (radius > _maxRadius) {
			radius = _maxRadius;
		}

		int index = selectRandomFreeCandidate(radius);
		if ((index < 0) && (radius < _maxRadius)) {
			index = selectRandomFreeCandidate(_maxRadius);
		}
		if (index < 0) {
			return null;
		}

		Candidate selected = _candidates.remove(index);
		selected.placementCount = 1;
		_overlapCandidates.add(selected);
		return new L1Location(selected.x, selected.y, _map);
	}

	/**
	 * Returns a previously selected safe companion destination for controlled
	 * overlap. Only destinations selected by this planner and currently
	 * occupied exclusively by this owner's companions are reused. Destinations
	 * with the lowest placement count are preferred to distribute companions
	 * as evenly as possible.
	 */
	public L1Location findOverlapLocation() {
		int index = selectLeastUsedOverlapCandidate();
		if (index < 0) {
			return null;
		}

		Candidate selected = _overlapCandidates.get(index);
		selected.placementCount++;
		return new L1Location(selected.x, selected.y, _map);
	}

	private void collectClosedDoors(L1PcInstance owner) {
		for (L1Object object : L1World.getInstance().getVisibleObjects(owner, _maxRadius)) {
			if (!(object instanceof L1DoorInstance)) {
				continue;
			}

			L1DoorInstance door = (L1DoorInstance) object;
			if (door.getOpenStatus() != ActionCodes.ACTION_Open) {
				_closedDoorTiles.add(locationKey(door.getX(), door.getY()));
			}
		}
	}

	private void buildReachableCandidates() {
		ArrayDeque<SearchNode> queue = new ArrayDeque<SearchNode>();
		Set<Long> visited = new HashSet<Long>();

		queue.addLast(new SearchNode(_originX, _originY, 0));
		visited.add(locationKey(_originX, _originY));

		while (!queue.isEmpty()) {
			SearchNode current = queue.removeFirst();
			for (int heading = 0; heading < HEADING_X.length; heading++) {
				int nextX = current.x + HEADING_X[heading];
				int nextY = current.y + HEADING_Y[heading];
				int nextPathLength = current.pathLength + 1;
				int directDistance = tileLineDistance(nextX, nextY);
				long key = locationKey(nextX, nextY);

				if (visited.contains(key) || !_map.isInMap(nextX, nextY)
						|| (directDistance > _maxRadius)
						|| (nextPathLength > _maxPathLength)
						|| _closedDoorTiles.contains(key)
						|| !_map.isTerrainPassable(current.x, current.y, heading)) {
					continue;
				}

				visited.add(key);
				queue.addLast(new SearchNode(nextX, nextY, nextPathLength));

				if (nextPathLength <= (directDistance + _maxPathDetour)) {
					_candidates.add(new Candidate(nextX, nextY, directDistance));
				}
			}
		}
	}

	private int selectRandomFreeCandidate(int radius) {
		int selectedIndex = -1;
		int eligibleCount = 0;

		for (int i = 0; i < _candidates.size(); i++) {
			Candidate candidate = _candidates.get(i);
			if ((candidate.distance > radius)
					|| !_map.isPassable(candidate.x, candidate.y)) {
				continue;
			}

			eligibleCount++;
			if (Random.nextInt(eligibleCount) == 0) {
				selectedIndex = i;
			}
		}

		return selectedIndex;
	}

	private int selectLeastUsedOverlapCandidate() {
		int selectedIndex = -1;
		int selectedUsage = Integer.MAX_VALUE;
		int selectedDistance = Integer.MAX_VALUE;
		int equalPriorityCount = 0;

		for (int i = 0; i < _overlapCandidates.size(); i++) {
			Candidate candidate = _overlapCandidates.get(i);
			if (!isOwnedCompanionOverlapCandidate(candidate)) {
				continue;
			}

			if ((candidate.placementCount < selectedUsage)
					|| ((candidate.placementCount == selectedUsage)
							&& (candidate.distance < selectedDistance))) {
				selectedIndex = i;
				selectedUsage = candidate.placementCount;
				selectedDistance = candidate.distance;
				equalPriorityCount = 1;
			}
			else if ((candidate.placementCount == selectedUsage)
					&& (candidate.distance == selectedDistance)) {
				equalPriorityCount++;
				if (Random.nextInt(equalPriorityCount) == 0) {
					selectedIndex = i;
				}
			}
		}

		return selectedIndex;
	}

	private boolean isOwnedCompanionOverlapCandidate(Candidate candidate) {
		if (_map.isPassable(candidate.x, candidate.y)) {
			return false;
		}

		boolean foundCompanion = false;
		for (L1Object object : L1World.getInstance().getObject()) {
			if (!(object instanceof L1Character)
					|| (object.getMapId() != _owner.getMapId())
					|| (object.getX() != candidate.x)
					|| (object.getY() != candidate.y)) {
				continue;
			}

			L1Character character = (L1Character) object;
			if (character.isDead()) {
				continue;
			}
			if (!(character instanceof L1NpcInstance)
					|| !_owner.getPetList().containsKey(character.getId())) {
				return false;
			}
			foundCompanion = true;
		}
		return foundCompanion;
	}

	private int tileLineDistance(int x, int y) {
		return Math.max(Math.abs(x - _originX), Math.abs(y - _originY));
	}

	private static long locationKey(int x, int y) {
		return (((long) x) << 32) ^ (y & 0xffffffffL);
	}

	private static final class SearchNode {
		private final int x;
		private final int y;
		private final int pathLength;

		private SearchNode(int x, int y, int pathLength) {
			this.x = x;
			this.y = y;
			this.pathLength = pathLength;
		}
	}

	private static final class Candidate {
		private final int x;
		private final int y;
		private final int distance;
		private int placementCount;

		private Candidate(int x, int y, int distance) {
			this.x = x;
			this.y = y;
			this.distance = distance;
		}
	}
}
