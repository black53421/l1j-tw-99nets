package l1j.server.server.command.executor;

import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.serverpackets.S_SystemMessage;

public class L1GmAggro implements L1CommandExecutor {
	private L1GmAggro() {
	}

	public static L1CommandExecutor getInstance() {
		return new L1GmAggro();
	}

	@Override
	public void execute(L1PcInstance pc, String cmdName, String arg) {
		if (!pc.isGm()) {
			pc.sendPackets(new S_SystemMessage("This command requires GM status."));
			return;
		}

		String value = arg.trim();
		if (value.equalsIgnoreCase("on")) {
			pc.setGmAggroTestEnabled(true);
			pc.sendPackets(new S_SystemMessage(
					"GM aggro test: ON. Hostile NPCs may acquire this GM as a normal target."));
			return;
		}

		if (value.equalsIgnoreCase("off")) {
			pc.setGmAggroTestEnabled(false);
			pc.sendPackets(new S_SystemMessage(
					"GM aggro test: OFF. New target acquisition ignores this GM; existing hate is unchanged."));
			return;
		}

		if (value.equalsIgnoreCase("status") || value.length() == 0) {
			sendStatus(pc, cmdName);
			return;
		}

		pc.sendPackets(new S_SystemMessage(
				"Usage: ." + cmdName + " on|off|status"));
	}

	private void sendStatus(L1PcInstance pc, String cmdName) {
		String state = pc.isGmAggroTestEnabled() ? "ON" : "OFF";
		pc.sendPackets(new S_SystemMessage(
				"GM aggro test: " + state + ". Usage: ." + cmdName + " on|off|status"));
	}
}
