package l1j.server.server.command.executor;

import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.serverpackets.S_SystemMessage;

public class L1PetHit implements L1CommandExecutor {
	private L1PetHit() {
	}

	public static L1CommandExecutor getInstance() {
		return new L1PetHit();
	}

	@Override
	public void execute(L1PcInstance pc, String cmdName, String arg) {
		if (arg.equalsIgnoreCase("on")) {
			pc.setPetHitMessageEnabled(true);
			pc.sendPackets(new S_SystemMessage("Pet incoming damage display: ON."));
		}
		else if (arg.equalsIgnoreCase("off")) {
			pc.setPetHitMessageEnabled(false);
			pc.sendPackets(new S_SystemMessage("Pet incoming damage display: OFF."));
		}
		else {
			String state = pc.isPetHitMessageEnabled() ? "ON" : "OFF";
			pc.sendPackets(new S_SystemMessage("Pet incoming damage display: " + state
					+ ". Usage: ." + cmdName + " on|off"));
		}
	}
}
