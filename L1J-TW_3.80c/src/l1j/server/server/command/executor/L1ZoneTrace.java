package l1j.server.server.command.executor;

import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.serverpackets.S_SystemMessage;

public class L1ZoneTrace implements L1CommandExecutor {
	private L1ZoneTrace() {
	}

	public static L1CommandExecutor getInstance() {
		return new L1ZoneTrace();
	}

	@Override
	public void execute(L1PcInstance pc, String cmdName, String arg) {
		if (arg.equalsIgnoreCase("on")) {
			pc.setZoneTraceEnabled(true);
			pc.sendPackets(new S_SystemMessage(String.format(
					"Zone trace: ON. Current zone=%s map=%d x=%d y=%d.",
					zoneName(pc.getZoneType()), pc.getMapId(), pc.getX(), pc.getY())));
		}
		else if (arg.equalsIgnoreCase("off")) {
			pc.setZoneTraceEnabled(false);
			pc.sendPackets(new S_SystemMessage("Zone trace: OFF."));
		}
		else {
			String state = pc.isZoneTraceEnabled() ? "ON" : "OFF";
			pc.sendPackets(new S_SystemMessage(
					"Zone trace: " + state + ". Usage: ." + cmdName + " on|off"));
		}
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
