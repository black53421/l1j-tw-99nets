package l1j.server.server.command.executor;

import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.serverpackets.S_SystemMessage;

public class L1MoveTrace implements L1CommandExecutor {
	private L1MoveTrace() {
	}

	public static L1CommandExecutor getInstance() {
		return new L1MoveTrace();
	}

	@Override
	public void execute(L1PcInstance pc, String cmdName, String arg) {
		if (arg.equalsIgnoreCase("on")) {
			pc.setMoveTraceEnabled(true);
			pc.sendPackets(new S_SystemMessage(String.format(
					"Move trace: ON. map=%d x=%d y=%d zone=%s.",
					pc.getMapId(), pc.getX(), pc.getY(), zoneName(pc.getZoneType()))));
		}
		else if (arg.equalsIgnoreCase("off")) {
			pc.setMoveTraceEnabled(false);
			pc.sendPackets(new S_SystemMessage("Move trace: OFF."));
		}
		else {
			String state = pc.isMoveTraceEnabled() ? "ON" : "OFF";
			pc.sendPackets(new S_SystemMessage(
					"Move trace: " + state + ". Usage: ." + cmdName + " on|off"));
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
