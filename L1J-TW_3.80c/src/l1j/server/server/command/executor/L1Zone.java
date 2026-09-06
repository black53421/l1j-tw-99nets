package l1j.server.server.command.executor;

import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.serverpackets.S_SystemMessage;

public class L1Zone implements L1CommandExecutor {
	private L1Zone() {
	}

	public static L1CommandExecutor getInstance() {
		return new L1Zone();
	}

	@Override
	public void execute(L1PcInstance pc, String cmdName, String arg) {
		int tile = pc.getMap().getOriginalTile(pc.getX(), pc.getY());
		String message = String.format(
				"[ZONE] map=%d x=%d y=%d heading=%d zone=%s tile=0x%02X(%d)",
				pc.getMapId(), pc.getX(), pc.getY(), pc.getHeading(),
				zoneName(pc.getZoneType()), tile, tile);
		pc.sendPackets(new S_SystemMessage(message));
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
