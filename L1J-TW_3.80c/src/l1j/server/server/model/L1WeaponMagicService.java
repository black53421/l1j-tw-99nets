package l1j.server.server.model;

import static l1j.server.server.model.skill.L1SkillId.ABSOLUTE_BARRIER;
import static l1j.server.server.model.skill.L1SkillId.COUNTER_MAGIC;
import static l1j.server.server.model.skill.L1SkillId.EARTH_BIND;
import static l1j.server.server.model.skill.L1SkillId.FREEZING_BLIZZARD;
import static l1j.server.server.model.skill.L1SkillId.FREEZING_BREATH;
import static l1j.server.server.model.skill.L1SkillId.ICE_LANCE;
import static l1j.server.server.model.skill.L1SkillId.STATUS_FREEZE;

import l1j.server.Config;
import l1j.server.server.ActionCodes;
import l1j.server.server.WarTimeController;
import l1j.server.server.datatables.SkillsTable;
import l1j.server.server.datatables.WeaponSkillTable;
import l1j.server.server.model.Instance.L1ItemInstance;
import l1j.server.server.model.Instance.L1MonsterInstance;
import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.Instance.L1PetInstance;
import l1j.server.server.model.Instance.L1SummonInstance;
import l1j.server.server.serverpackets.S_DoActionGFX;
import l1j.server.server.serverpackets.S_EffectLocation;
import l1j.server.server.serverpackets.S_SkillSound;
import l1j.server.server.serverpackets.S_UseAttackSkill;
import l1j.server.server.templates.L1Skills;
import l1j.server.server.utils.Random;

public final class L1WeaponMagicService {
    public static final int STAT_STR = 1;
    public static final int STAT_DEX = 2;
    public static final int STAT_INT = 4;
    public static final int STAT_SP = 8;

    public static final int AREA_CENTER_TARGET = 0;
    public static final int AREA_CENTER_CASTER = 1;

    public static final int EFFECT_SKILL_SOUND = 0;
    public static final int EFFECT_LOCATION = 1;

    private static final String FORMULA_FIXED_RANDOM = "FIXED_RANDOM";
    private static final String FORMULA_STAT_SCALE = "STAT_SCALE";
    private static final String FORMULA_STATUS_ONLY = "STATUS_ONLY";

    private static final L1WeaponMagicService _instance = new L1WeaponMagicService();

    public static L1WeaponMagicService getInstance() {
        return _instance;
    }

    private L1WeaponMagicService() {
    }

    public static boolean isSupportedFormulaType(String formulaType) {
        if (formulaType == null) {
            return false;
        }
        return FORMULA_FIXED_RANDOM.equalsIgnoreCase(formulaType)
                || FORMULA_STAT_SCALE.equalsIgnoreCase(formulaType)
                || FORMULA_STATUS_ONLY.equalsIgnoreCase(formulaType);
    }

    public double execute(L1PcInstance pc, L1Character target, int weaponId) {
        if (!Config.WEAPON_MAGIC_ENABLED || pc == null || target == null) {
            return 0;
        }

        L1WeaponSkill profile = WeaponSkillTable.getInstance().getTemplate(weaponId);
        if (profile == null || !profile.isEnabled()) {
            return 0;
        }

        if (!isTriggered(profile, pc, target)) {
            return 0;
        }

        applySkillEffect(profile, target);
        sendEffect(profile, pc, target);

        if (FORMULA_STATUS_ONLY.equalsIgnoreCase(profile.getFormulaType())) {
            return 0;
        }

        double rawDamage = calculateRawDamage(profile, pc);
        if (rawDamage <= 0) {
            return 0;
        }

        applyAreaDamage(profile, pc, target, rawDamage);
        return calculateFinalDamage(profile, pc, target, rawDamage);
    }

    private boolean isTriggered(L1WeaponSkill profile, L1PcInstance pc,
            L1Character target) {
        double triggerRate = Config.WEAPON_MAGIC_TRIGGER_RATE;
        double probability = Math.max(0.0, profile.getProbability()) * triggerRate;
        probability += calculateEnchantTriggerBonus(profile, pc, target,
                triggerRate);

        double probabilityCap = target instanceof L1PcInstance
                ? Config.WEAPON_MAGIC_PVP_MAX_TRIGGER_PROBABILITY
                : Config.WEAPON_MAGIC_PVE_MAX_TRIGGER_PROBABILITY;
        probability = Math.max(0.0,
                Math.min(Math.min(100.0, probabilityCap), probability));

        int threshold = (int) Math.floor(probability * 100.0);
        return threshold > 0 && (Random.nextInt(10000) + 1) <= threshold;
    }

    private double calculateEnchantTriggerBonus(L1WeaponSkill profile,
            L1PcInstance pc, L1Character target, double triggerRate) {
        if (!Config.WEAPON_MAGIC_ENCHANT_TRIGGER_BONUS_ENABLED
                || profile.getEnchantTriggerRate() <= 0.0) {
            return 0.0;
        }

        L1ItemInstance weapon = pc.getWeapon();
        if (weapon == null) {
            return 0.0;
        }

        int startLevel = Math.max(0, profile.getEnchantTriggerStartLevel());
        int bonusLevels = Math.max(0, weapon.getEnchantLevel() - startLevel);
        if (bonusLevels == 0) {
            return 0.0;
        }

        double bonus = bonusLevels * profile.getEnchantTriggerRate()
                * Config.WEAPON_MAGIC_ENCHANT_TRIGGER_BONUS_RATE
                * Math.max(0.0, triggerRate);
        double bonusCap = target instanceof L1PcInstance
                ? Config.WEAPON_MAGIC_PVP_MAX_ENCHANT_TRIGGER_BONUS
                : Config.WEAPON_MAGIC_PVE_MAX_ENCHANT_TRIGGER_BONUS;
        return Math.max(0.0, Math.min(Math.max(0.0, bonusCap), bonus));
    }

    private double calculateRawDamage(L1WeaponSkill profile, L1PcInstance pc) {
        String formulaType = profile.getFormulaType();
        double damage = profile.getFixDamage();

        if (profile.getRandomDamage() > 0) {
            damage += Random.nextInt(profile.getRandomDamage());
        }

        if (FORMULA_STAT_SCALE.equalsIgnoreCase(formulaType)) {
            damage += pc.getStr() * profile.getStrRate();
            damage += pc.getDex() * profile.getDexRate();
            damage += pc.getInt() * profile.getIntRate();
            damage += pc.getSp() * profile.getSpRate();

            int randomStatValue = getStatValue(pc, profile.getRandomStatMask());
            if (randomStatValue > 0 && profile.getRandomStatRate() != 0.0) {
                damage += Random.nextInt(randomStatValue) * profile.getRandomStatRate();
            }

            if (profile.getBuffSkillId() > 0
                    && pc.hasSkillEffect(profile.getBuffSkillId())) {
                damage += getStatValue(pc, profile.getBuffStatMask())
                        * profile.getBuffStatRate();
            }
        } else if (!FORMULA_FIXED_RANDOM.equalsIgnoreCase(formulaType)) {
            return 0;
        }

        if (Config.WEAPON_MAGIC_ENCHANT_BONUS_ENABLED
                && profile.getEnchantRate() != 0.0) {
            L1ItemInstance weapon = pc.getWeapon();
            if (weapon != null) {
                damage += weapon.getEnchantLevel() * profile.getEnchantRate();
            }
        }

        return Math.max(0.0, damage);
    }

    private int getStatValue(L1PcInstance pc, int mask) {
        int value = 0;
        if ((mask & STAT_STR) != 0) {
            value += pc.getStr();
        }
        if ((mask & STAT_DEX) != 0) {
            value += pc.getDex();
        }
        if ((mask & STAT_INT) != 0) {
            value += pc.getInt();
        }
        if ((mask & STAT_SP) != 0) {
            value += pc.getSp();
        }
        return Math.max(0, value);
    }

    private void applySkillEffect(L1WeaponSkill profile, L1Character target) {
        int skillId = profile.getSkillId();
        if (skillId == 0) {
            return;
        }
        L1Skills skill = SkillsTable.getInstance().getTemplate(skillId);
        if (skill != null && "buff".equals(skill.getTarget())
                && !isMagicBlocked(target)) {
            target.setSkillEffect(skillId, profile.getSkillTime() * 1000);
        }
    }

    private void sendEffect(L1WeaponSkill profile, L1PcInstance pc,
            L1Character target) {
        int effectId = profile.getEffectId();
        if (effectId == 0) {
            return;
        }

        L1Character effectTarget = profile.getEffectTarget() == 0 ? target : pc;
        if (profile.getEffectType() == EFFECT_LOCATION) {
            S_EffectLocation packet = new S_EffectLocation(effectTarget.getX(),
                    effectTarget.getY(), effectId);
            pc.sendPackets(packet);
            pc.broadcastPacket(packet);
            return;
        }

        if (!profile.isArrowType()) {
            S_SkillSound packet = new S_SkillSound(effectTarget.getId(), effectId);
            pc.sendPackets(packet);
            pc.broadcastPacket(packet);
            return;
        }

        int[] data = { ActionCodes.ACTION_Attack, 0, effectId, 6 };
        S_UseAttackSkill packet = new S_UseAttackSkill(pc, target.getId(),
                target.getX(), target.getY(), data, false);
        pc.sendPackets(packet);
        pc.broadcastPacket(packet);
    }

    private void applyAreaDamage(L1WeaponSkill profile, L1PcInstance pc,
            L1Character primaryTarget, double rawDamage) {
        int area = profile.getArea();
        if (area == 0 || area < -1) {
            return;
        }

        L1Character areaBase = profile.getAreaCenter() == AREA_CENTER_CASTER
                ? pc : primaryTarget;
        for (L1Object object : L1World.getInstance().getVisibleObjects(areaBase, area)) {
            if (!(object instanceof L1Character)) {
                continue;
            }
            if (object.getId() == pc.getId() || object.getId() == primaryTarget.getId()) {
                continue;
            }
            if (!isValidAreaTarget(primaryTarget, object)) {
                continue;
            }

            L1Character areaTarget = (L1Character) object;
            double targetRawDamage = profile.isAreaPvpProtection()
                    ? applyAreaTargetRules(rawDamage, areaTarget) : rawDamage;
            if (targetRawDamage <= 0) {
                continue;
            }

            double targetDamage = calculateFinalDamage(profile, pc, areaTarget,
                    targetRawDamage);
            if (targetDamage <= 0) {
                continue;
            }
            applyDamage(pc, areaTarget, targetDamage);
        }
    }

    private boolean isValidAreaTarget(L1Character primaryTarget, L1Object object) {
        if (primaryTarget instanceof L1MonsterInstance) {
            return object instanceof L1MonsterInstance;
        }
        if (primaryTarget instanceof L1PcInstance
                || primaryTarget instanceof L1SummonInstance
                || primaryTarget instanceof L1PetInstance) {
            return object instanceof L1PcInstance
                    || object instanceof L1SummonInstance
                    || object instanceof L1PetInstance
                    || object instanceof L1MonsterInstance;
        }
        return false;
    }

    private double applyAreaTargetRules(double rawDamage, L1Character target) {
        boolean isNowWar = false;
        int castleId = L1CastleLocation.getCastleIdByArea(target);
        if (castleId > 0) {
            isNowWar = WarTimeController.getInstance().isNowWar(castleId);
        }

        if (!isNowWar) {
            if (!(target instanceof L1MonsterInstance) && target.getZoneType() == 1) {
                return 0;
            }
            if (target instanceof L1PetInstance) {
                return rawDamage / 8.0;
            }
            if (target instanceof L1SummonInstance) {
                L1SummonInstance summon = (L1SummonInstance) target;
                if (summon.isExsistMaster()) {
                    return rawDamage / 8.0;
                }
            }
        }
        return rawDamage;
    }

    private void applyDamage(L1PcInstance attacker, L1Character target,
            double damage) {
        if (target instanceof L1PcInstance) {
            L1PcInstance targetPc = (L1PcInstance) target;
            S_DoActionGFX packet = new S_DoActionGFX(targetPc.getId(),
                    ActionCodes.ACTION_Damage);
            targetPc.sendPackets(packet);
            targetPc.broadcastPacket(packet);
            targetPc.receiveDamage(attacker, (int) damage, false);
        } else if (target instanceof L1NpcInstance) {
            L1NpcInstance targetNpc = (L1NpcInstance) target;
            targetNpc.broadcastPacket(new S_DoActionGFX(targetNpc.getId(),
                    ActionCodes.ACTION_Damage));
            targetNpc.receiveDamage(attacker, (int) damage);
        }
    }

    private double calculateFinalDamage(L1WeaponSkill profile, L1PcInstance pc,
            L1Character target, double damage) {
        if (isMagicBlocked(target)) {
            return 0;
        }

        boolean pvp = target instanceof L1PcInstance;
        if (Config.WEAPON_MAGIC_USE_MR_REDUCTION) {
            int mr = target.getMr();
            double penetration = Config.WEAPON_MAGIC_MR_PENETRATION_ENABLED
                    ? profile.getMrPenetration() : 0.0;
            double cap = pvp ? Config.WEAPON_MAGIC_PVP_MAX_MR_PENETRATION
                    : Config.WEAPON_MAGIC_PVE_MAX_MR_PENETRATION;
            mr = applyResistancePenetration(mr, penetration, cap);
            damage = applyMrReduction(pc, damage, mr);
        }

        if (Config.WEAPON_MAGIC_USE_ATTRIBUTE_REDUCTION) {
            int resistance = getAttributeResistance(target, profile.getAttr());
            double penetration = Config.WEAPON_MAGIC_ATTRIBUTE_PENETRATION_ENABLED
                    ? profile.getAttrPenetration() : 0.0;
            double cap = pvp
                    ? Config.WEAPON_MAGIC_PVP_MAX_ATTRIBUTE_PENETRATION
                    : Config.WEAPON_MAGIC_PVE_MAX_ATTRIBUTE_PENETRATION;
            resistance = applyResistancePenetration(resistance, penetration, cap);
            damage = applyAttributeReduction(damage, resistance);
        }

        damage *= Config.WEAPON_MAGIC_DAMAGE_RATE;
        damage *= pvp ? Config.WEAPON_MAGIC_PVP_DAMAGE_RATE
                : Config.WEAPON_MAGIC_PVE_DAMAGE_RATE;
        return Math.max(0.0, damage);
    }

    private int applyResistancePenetration(int resistance,
            double penetrationPercent, double capPercent) {
        if (resistance <= 0) {
            return resistance;
        }
        double penetration = Math.max(0.0,
                Math.min(Math.min(100.0, capPercent), penetrationPercent));
        return (int) Math.floor(resistance * (1.0 - penetration / 100.0));
    }

    private double applyMrReduction(L1PcInstance pc, double damage, int mr) {
        double mrFloor;
        double coefficient;
        if (mr <= 100) {
            mrFloor = Math.floor((mr - pc.getOriginalMagicHit()) / 2.0);
            coefficient = 1.0 - 0.01 * mrFloor;
        } else {
            mrFloor = Math.floor((mr - pc.getOriginalMagicHit()) / 10.0);
            coefficient = 0.6 - 0.01 * mrFloor;
        }
        return damage * coefficient;
    }

    private int getAttributeResistance(L1Character target, int attr) {
        if (attr == L1Skills.ATTR_EARTH) {
            return target.getEarth();
        }
        if (attr == L1Skills.ATTR_FIRE) {
            return target.getFire();
        }
        if (attr == L1Skills.ATTR_WATER) {
            return target.getWater();
        }
        if (attr == L1Skills.ATTR_WIND) {
            return target.getWind();
        }
        return 0;
    }

    private double applyAttributeReduction(double damage, int resistance) {
        int resistFloor = (int) (0.32 * Math.abs(resistance));
        if (resistance < 0) {
            resistFloor *= -1;
        }
        double attributeDefence = resistFloor / 32.0;
        return (1.0 - attributeDefence) * damage;
    }

    private boolean isMagicBlocked(L1Character target) {
        if (target.hasSkillEffect(STATUS_FREEZE)
                || target.hasSkillEffect(ABSOLUTE_BARRIER)
                || target.hasSkillEffect(ICE_LANCE)
                || target.hasSkillEffect(FREEZING_BLIZZARD)
                || target.hasSkillEffect(FREEZING_BREATH)
                || target.hasSkillEffect(EARTH_BIND)) {
            return true;
        }

        if (target.hasSkillEffect(COUNTER_MAGIC)) {
            target.removeSkillEffect(COUNTER_MAGIC);
            L1Skills counterMagic = SkillsTable.getInstance().getTemplate(COUNTER_MAGIC);
            if (counterMagic != null) {
                int castGfx = counterMagic.getCastGfx();
                target.broadcastPacket(new S_SkillSound(target.getId(), castGfx));
                if (target instanceof L1PcInstance) {
                    ((L1PcInstance) target).sendPackets(
                            new S_SkillSound(target.getId(), castGfx));
                }
            }
            return true;
        }
        return false;
    }
}
