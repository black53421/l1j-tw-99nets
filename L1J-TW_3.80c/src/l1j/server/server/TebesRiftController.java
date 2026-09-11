package l1j.server.server;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

import l1j.server.Config;
import l1j.server.server.datatables.NpcTable;
import l1j.server.server.model.L1Teleport;
import l1j.server.server.model.L1World;
import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.serverpackets.S_SystemMessage;
import l1j.server.server.utils.DailyTimeWindow;
import l1j.server.server.utils.Random;

public final class TebesRiftController implements Runnable {
	private static final Logger _log = Logger.getLogger(TebesRiftController.class.getName());
	private static final long CHECK_INTERVAL_MILLIS = 5000L;
	private static final String OPEN_MESSAGE = "\u6642\u7a7a\u88c2\u75d5\u958b\u555f\u4e86\uff01";
	private static final String CLOSE_MESSAGE = "\u6642\u7a7a\u88c2\u75d5\u5df2\u7d93\u6d88\u5931\u4e86\u3002";

	private static TebesRiftController _instance;

	private List<DailyTimeWindow> _entryWindows = new ArrayList<DailyTimeWindow>();
	private List<PortalLocation> _randomPortalLocations = new ArrayList<PortalLocation>();
	private PortalLocation _fixedPortalLocation;
	private L1NpcInstance _portal;
	private volatile PortalLocation _activePortalLocation;
	private boolean _configurationValid;
	private volatile boolean _entryOpen;

	public static synchronized TebesRiftController getInstance() {
		if (_instance == null) {
			_instance = new TebesRiftController();
		}
		return _instance;
	}

	private TebesRiftController() {
		loadConfiguration();
	}

	@Override
	public void run() {
		try {
			updateState(false);
			while (true) {
				Thread.sleep(CHECK_INTERVAL_MILLIS);
				updateState(true);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			closePortal(false);
		}
		catch (Exception e) {
			_log.log(Level.SEVERE, "Tebes rift controller stopped unexpectedly.", e);
			closePortal(false);
		}
	}

	public boolean isEntryOpen() {
		return _entryOpen;
	}

	public boolean tryEnter(L1PcInstance pc) {
		if (!isPortalTriggerLocation(pc)) {
			return false;
		}

		L1Teleport.teleport(pc, Config.TEBES_RIFT_DESTINATION_X,
				Config.TEBES_RIFT_DESTINATION_Y,
				Config.TEBES_RIFT_DESTINATION_MAP_ID,
				Config.TEBES_RIFT_DESTINATION_HEADING, true);
		return true;
	}

	private boolean isPortalTriggerLocation(L1PcInstance pc) {
		if ((pc == null) || !_entryOpen || !Config.TEBES_RIFT_ENABLED) {
			return false;
		}

		PortalLocation activePortal = _activePortalLocation;
		if ((activePortal == null) || (pc.getMapId() != activePortal.mapId)) {
			return false;
		}

		int deltaX = Math.abs(pc.getX() - activePortal.x);
		int deltaY = Math.abs(pc.getY() - activePortal.y);
		return Math.max(deltaX, deltaY) <= Config.TEBES_RIFT_TRIGGER_RADIUS;
	}

	private void loadConfiguration() {
		try {
			_entryWindows = DailyTimeWindow.parseList(Config.TEBES_RIFT_ENTRY_WINDOWS);
			_randomPortalLocations = parsePortalLocations(Config.TEBES_RIFT_PORTAL_RANDOM_LOCATIONS);
			_fixedPortalLocation = parseSinglePortalLocation(Config.TEBES_RIFT_PORTAL_FIXED_LOCATION);

			if ("SCHEDULE".equals(Config.TEBES_RIFT_ENTRY_MODE) && _entryWindows.isEmpty()) {
				throw new IllegalArgumentException("No Tebes rift entry windows configured for SCHEDULE mode");
			}
			if ("RANDOM".equals(Config.TEBES_RIFT_PORTAL_SPAWN_MODE)
					&& _randomPortalLocations.isEmpty()) {
				throw new IllegalArgumentException("No Tebes rift random portal locations configured");
			}
			if ("FIXED".equals(Config.TEBES_RIFT_PORTAL_SPAWN_MODE)
					&& _fixedPortalLocation == null) {
				throw new IllegalArgumentException("No Tebes rift fixed portal location configured");
			}
			_configurationValid = true;
		}
		catch (IllegalArgumentException e) {
			_configurationValid = false;
			_log.log(Level.SEVERE, "Invalid Tebes rift configuration. Entry will remain closed.", e);
		}
	}

	private void updateState(boolean broadcast) {
		boolean shouldOpen = shouldOpenNow();
		if (shouldOpen == _entryOpen) {
			return;
		}

		if (shouldOpen) {
			openPortal(broadcast);
		}
		else {
			closePortal(broadcast);
		}
	}

	private boolean shouldOpenNow() {
		if (!Config.TEBES_RIFT_ENABLED || !_configurationValid) {
			return false;
		}
		if ("DISABLED".equals(Config.TEBES_RIFT_ENTRY_MODE)) {
			return false;
		}
		if ("ALWAYS".equals(Config.TEBES_RIFT_ENTRY_MODE)) {
			return true;
		}

		Calendar now = Calendar.getInstance(TimeZone.getTimeZone(Config.TIME_ZONE));
		for (DailyTimeWindow window : _entryWindows) {
			if (window.isActive(now)) {
				return true;
			}
		}
		return false;
	}

	private synchronized void openPortal(boolean broadcast) {
		if (_entryOpen) {
			return;
		}

		PortalLocation selected = selectPortalLocation();
		if (selected == null) {
			_log.severe("Failed to select a Tebes rift portal location.");
			return;
		}

		L1NpcInstance created = null;
		try {
			created = createPortal(selected);
			if (created == null) {
				throw new IllegalStateException("Failed to create Tebes rift portal");
			}
			_portal = created;
			_activePortalLocation = selected;
			_entryOpen = true;
			_log.info("Tebes rift entry opened at " + selected + ".");
			if (broadcast && Config.TEBES_RIFT_BROADCAST_ENABLED) {
				L1World.getInstance().broadcastPacketToAll(new S_SystemMessage(OPEN_MESSAGE));
			}
		}
		catch (Exception e) {
			if (created != null) {
				created.deleteMe();
			}
			_portal = null;
			_activePortalLocation = null;
			_entryOpen = false;
			_log.log(Level.SEVERE, "Failed to open Tebes rift entry.", e);
		}
	}

	private synchronized void closePortal(boolean broadcast) {
		boolean wasOpen = _entryOpen;
		_entryOpen = false;
		if (_portal != null) {
			try {
				_portal.deleteMe();
			}
			catch (Exception e) {
				_log.log(Level.WARNING, "Failed to delete the Tebes rift portal.", e);
			}
		}
		_portal = null;
		_activePortalLocation = null;

		if (wasOpen) {
			_log.info("Tebes rift entry closed.");
			if (broadcast && Config.TEBES_RIFT_BROADCAST_ENABLED) {
				L1World.getInstance().broadcastPacketToAll(new S_SystemMessage(CLOSE_MESSAGE));
			}
		}
	}

	private PortalLocation selectPortalLocation() {
		if ("FIXED".equals(Config.TEBES_RIFT_PORTAL_SPAWN_MODE)) {
			return _fixedPortalLocation;
		}
		if (_randomPortalLocations.isEmpty()) {
			return null;
		}
		return _randomPortalLocations.get(Random.nextInt(_randomPortalLocations.size()));
	}

	private L1NpcInstance createPortal(PortalLocation location) {
		L1NpcInstance portal = NpcTable.getInstance().newNpcInstance(Config.TEBES_RIFT_PORTAL_NPC_ID);
		if (portal == null) {
			return null;
		}

		portal.setId(IdFactory.getInstance().nextId());
		portal.setX(location.x);
		portal.setY(location.y);
		portal.setHomeX(location.x);
		portal.setHomeY(location.y);
		portal.setMap(location.mapId);
		portal.setHeading(location.heading);
		L1World.getInstance().storeObject(portal);
		L1World.getInstance().addVisibleObject(portal);

		for (L1PcInstance pc : L1World.getInstance().getVisiblePlayer(portal)) {
			portal.onPerceive(pc);
		}
		portal.turnOnOffLight();
		portal.startChat(L1NpcInstance.CHAT_TIMING_APPEARANCE);
		return portal;
	}

	private List<PortalLocation> parsePortalLocations(String value) {
		List<PortalLocation> result = new ArrayList<PortalLocation>();
		if (value == null || value.trim().length() == 0) {
			return result;
		}

		String[] locations = value.split(";");
		for (String location : locations) {
			String trimmed = location.trim();
			if (trimmed.length() == 0) {
				throw new IllegalArgumentException("Empty Tebes rift portal location");
			}
			result.add(parsePortalLocation(trimmed));
		}
		return result;
	}

	private PortalLocation parseSinglePortalLocation(String value) {
		if (value == null || value.trim().length() == 0) {
			return null;
		}
		return parsePortalLocation(value.trim());
	}

	private PortalLocation parsePortalLocation(String value) {
		String[] fields = value.split(",");
		if (fields.length != 4) {
			throw new IllegalArgumentException("Invalid Tebes rift portal location: " + value);
		}

		try {
			int x = Integer.parseInt(fields[0].trim());
			int y = Integer.parseInt(fields[1].trim());
			short mapId = Short.parseShort(fields[2].trim());
			int heading = Integer.parseInt(fields[3].trim());
			if ((heading < 0) || (heading > 7)) {
				throw new IllegalArgumentException("Invalid Tebes rift portal heading: " + heading);
			}
			return new PortalLocation(x, y, mapId, heading);
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid Tebes rift portal location: " + value, e);
		}
	}

	private static final class PortalLocation {
		private final int x;
		private final int y;
		private final short mapId;
		private final int heading;

		private PortalLocation(int x, int y, short mapId, int heading) {
			this.x = x;
			this.y = y;
			this.mapId = mapId;
			this.heading = heading;
		}

		@Override
		public String toString() {
			return x + "," + y + "," + mapId + "," + heading;
		}
	}
}
