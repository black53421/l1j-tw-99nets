package l1j.server.server.model;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Stores a bounded sequence of recent player movement tiles for companion
 * route coherence. All access is synchronized because player movement and NPC
 * AI can run on different threads.
 */
public final class L1CompanionTrail implements Serializable {
	private static final long serialVersionUID = 1L;

	public static final class Breadcrumb implements Serializable {
		private static final long serialVersionUID = 1L;

		private final long _sequence;
		private final int _mapId;
		private final int _x;
		private final int _y;

		private Breadcrumb(long sequence, int mapId, int x, int y) {
			_sequence = sequence;
			_mapId = mapId;
			_x = x;
			_y = y;
		}

		public long getSequence() {
			return _sequence;
		}

		public int getMapId() {
			return _mapId;
		}

		public int getX() {
			return _x;
		}

		public int getY() {
			return _y;
		}
	}

	private final Deque<Breadcrumb> _breadcrumbs = new ArrayDeque<Breadcrumb>();
	private long _nextSequence = 1L;

	public synchronized void clear() {
		_breadcrumbs.clear();
	}

	public synchronized void recordStep(int mapId, int fromX, int fromY,
			int toX, int toY, int maxEntries) {
		if (maxEntries < 2) {
			clear();
			return;
		}

		Breadcrumb last = _breadcrumbs.peekLast();
		if ((last == null) || (last.getMapId() != mapId)
				|| (last.getX() != fromX) || (last.getY() != fromY)) {
			resetAt(mapId, fromX, fromY);
		}

		last = _breadcrumbs.peekLast();
		if ((last == null) || (last.getX() != toX) || (last.getY() != toY)) {
			append(mapId, toX, toY);
		}
		trim(maxEntries);
	}

	public synchronized List<Breadcrumb> snapshot(int mapId, int currentX,
			int currentY, int maxEntries) {
		if (maxEntries < 2) {
			return Collections.emptyList();
		}

		Breadcrumb last = _breadcrumbs.peekLast();
		if ((last == null) || (last.getMapId() != mapId)
				|| (last.getX() != currentX) || (last.getY() != currentY)) {
			resetAt(mapId, currentX, currentY);
		}
		trim(maxEntries);
		return new ArrayList<Breadcrumb>(_breadcrumbs);
	}

	private void resetAt(int mapId, int x, int y) {
		_breadcrumbs.clear();
		append(mapId, x, y);
	}

	private void append(int mapId, int x, int y) {
		_breadcrumbs.addLast(new Breadcrumb(_nextSequence++, mapId, x, y));
	}

	private void trim(int maxEntries) {
		while (_breadcrumbs.size() > maxEntries) {
			_breadcrumbs.removeFirst();
		}
	}
}
