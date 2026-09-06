package l1j.server.server.model;

import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.Instance.L1PetInstance;
import l1j.server.server.serverpackets.S_SystemMessage;

public final class L1PetDamageDebug {
	public static final String SOURCE_PHYSICAL = "PHYSICAL";
	public static final String SOURCE_MAGIC = "MAGIC";
	public static final String SOURCE_OTHER = "OTHER";

	private static final ThreadLocal<String> _damageSource = new ThreadLocal<String>();

	private L1PetDamageDebug() {
	}

	public static void beginDamageSource(String source) {
		_damageSource.set(source);
	}

	public static void endDamageSource() {
		_damageSource.remove();
	}

	public static String getDamageSource() {
		String source = _damageSource.get();
		return source != null ? source : SOURCE_OTHER;
	}

	public static void reportOutgoing(L1PetInstance pet, L1Character target,
			int calculatedDamage, int hpBefore, boolean hit) {
		L1PcInstance master = getMaster(pet);
		if (master == null || !master.isPetDamageMessageEnabled()) {
			return;
		}

		int hpAfter = target != null ? target.getCurrentHp() : hpBefore;
		int hpLoss = Math.max(0, hpBefore - hpAfter);
		String targetName = target != null ? target.getName() : "unknown";
		String result = hit ? "hit" : "miss";

		master.sendPackets(new S_SystemMessage(
				"[PETDMG][" + getDamageSource() + "] " + pet.getName() + " -> "
						+ targetName + " result=" + result + " calc="
						+ Math.max(0, calculatedDamage) + " loss=" + hpLoss
						+ " hp=" + hpBefore + "->" + hpAfter));
	}

	public static void reportIncoming(L1PetInstance pet, L1Character attacker,
			int inputDamage, int appliedDamage, int hpBefore, int hpAfter) {
		L1PcInstance master = getMaster(pet);
		if (master == null || !master.isPetHitMessageEnabled()) {
			return;
		}

		String attackerName = attacker != null ? attacker.getName() : "unknown";
		int hpLoss = Math.max(0, hpBefore - hpAfter);

		master.sendPackets(new S_SystemMessage(
				"[PETHIT][" + getDamageSource() + "] " + attackerName + " -> "
						+ pet.getName() + " input=" + Math.max(0, inputDamage)
						+ " applied=" + Math.max(0, appliedDamage) + " loss="
						+ hpLoss + " hp=" + hpBefore + "->" + hpAfter));
	}

	public static void reportMonsterMagicAdjustment(L1PetInstance pet,
			L1Character attacker, int rawDamage, int adjustedDamage, int rate,
			boolean primaryTarget) {
		L1PcInstance master = getMaster(pet);
		if (master == null || !master.isPetHitMessageEnabled()) {
			return;
		}

		String attackerName = attacker != null ? attacker.getName() : "unknown";
		String targetType = primaryTarget ? "PRIMARY" : "SECONDARY";
		master.sendPackets(new S_SystemMessage(
				"[PETHIT][MAGIC-RATE][" + targetType + "] " + attackerName
						+ " -> " + pet.getName() + " raw=" + Math.max(0, rawDamage)
						+ " rate=" + rate + "% final=" + Math.max(0, adjustedDamage)));
	}

	private static L1PcInstance getMaster(L1PetInstance pet) {
		if (pet == null || !(pet.getMaster() instanceof L1PcInstance)) {
			return null;
		}
		return (L1PcInstance) pet.getMaster();
	}
}
