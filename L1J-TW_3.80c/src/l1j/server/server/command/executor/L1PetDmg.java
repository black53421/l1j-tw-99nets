package l1j.server.server.command.executor;

import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.serverpackets.S_SystemMessage;

public class L1PetDmg implements L1CommandExecutor {
	private L1PetDmg() {
	}

	public static L1CommandExecutor getInstance() {
		return new L1PetDmg();
	}

	@Override
	public void execute(L1PcInstance pc, String cmdName, String arg) {
		if (arg.equalsIgnoreCase("on")) {
			pc.setPetDamageMessageEnabled(true);
			pc.sendPackets(new S_SystemMessage("Pet outgoing damage display: ON."));
		}
		else if (arg.equalsIgnoreCase("off")) {
			pc.setPetDamageMessageEnabled(false);
			pc.sendPackets(new S_SystemMessage("Pet outgoing damage display: OFF."));
		}
		else {
			String state = pc.isPetDamageMessageEnabled() ? "ON" : "OFF";
			pc.sendPackets(new S_SystemMessage("Pet outgoing damage display: " + state
					+ ". Usage: ." + cmdName + " on|off"));
		}
	}
}
