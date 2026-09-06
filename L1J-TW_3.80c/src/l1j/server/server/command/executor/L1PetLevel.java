package l1j.server.server.command.executor;

import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.Instance.L1PetInstance;
import l1j.server.server.serverpackets.S_PetPack;
import l1j.server.server.serverpackets.S_SystemMessage;
import l1j.server.server.utils.IntRange;

public class L1PetLevel implements L1CommandExecutor {
	private static final int MIN_LEVEL = 1;

	private static final int MAX_LEVEL = 50;

	private L1PetLevel() {
	}

	public static L1CommandExecutor getInstance() {
		return new L1PetLevel();
	}

	@Override
	public void execute(L1PcInstance pc, String cmdName, String arg) {
		String value = arg.trim();
		if (value.equalsIgnoreCase("reset")) {
			int count = clearOverrides(pc);
			pc.sendPackets(new S_SystemMessage("Pet level/HP override cleared for "
					+ count + " active pet(s)."));
			return;
		}

		final int level;
		try {
			level = Integer.parseInt(value);
		}
		catch (NumberFormatException e) {
			sendUsage(pc, cmdName);
			return;
		}

		if (!IntRange.includes(level, MIN_LEVEL, MAX_LEVEL)) {
			pc.sendPackets(new S_SystemMessage("Pet level must be between "
					+ MIN_LEVEL + " and " + MAX_LEVEL + "."));
			return;
		}

		int count = applyOverride(pc, level);
		if (count == 0) {
			pc.sendPackets(new S_SystemMessage("No active pets found."));
			return;
		}

		pc.sendPackets(new S_SystemMessage("Pet debug level set to " + level
				+ " and HP refilled for " + count + " active pet(s)."));
	}

	private int applyOverride(L1PcInstance pc, int level) {
		int count = 0;
		for (L1NpcInstance petNpc : pc.getPetList().values()) {
			if (!(petNpc instanceof L1PetInstance)) {
				continue;
			}

			L1PetInstance pet = (L1PetInstance) petNpc;
			if (pet.isDead()) {
				continue;
			}

			int realLevel = pet.getRealLevel();
			int realMaxHp = pet.getRealMaxHp();
			pet.setDebugLevelOverride(level);
			pc.sendPackets(new S_PetPack(pet, pc));
			pc.sendPackets(new S_SystemMessage(pet.getName() + ": Lv "
					+ realLevel + " -> " + pet.getLevel() + ", MaxHP "
					+ realMaxHp + " -> " + pet.getMaxHp() + "."));
			count++;
		}
		return count;
	}

	private int clearOverrides(L1PcInstance pc) {
		int count = 0;
		for (L1NpcInstance petNpc : pc.getPetList().values()) {
			if (!(petNpc instanceof L1PetInstance)) {
				continue;
			}

			L1PetInstance pet = (L1PetInstance) petNpc;
			if (!pet.hasDebugLevelOverride()) {
				continue;
			}

			pet.clearDebugLevelOverride();
			pc.sendPackets(new S_PetPack(pet, pc));
			pc.sendPackets(new S_SystemMessage(pet.getName()
					+ ": restored Lv " + pet.getLevel() + ", MaxHP "
					+ pet.getMaxHp() + "."));
			count++;
		}
		return count;
	}

	private void sendUsage(L1PcInstance pc, String cmdName) {
		pc.sendPackets(new S_SystemMessage("Usage: ." + cmdName
				+ " <1-50|reset>"));
	}
}
