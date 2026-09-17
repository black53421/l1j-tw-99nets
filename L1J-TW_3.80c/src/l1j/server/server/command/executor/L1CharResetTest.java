package l1j.server.server.command.executor;

import java.util.StringTokenizer;

import l1j.server.server.datatables.ExpTable;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.serverpackets.S_OwnCharStatus;
import l1j.server.server.serverpackets.S_SystemMessage;
import l1j.server.server.utils.CalcStat;
import l1j.server.server.utils.IntRange;

public class L1CharResetTest implements L1CommandExecutor {
    private L1CharResetTest() {
    }

    public static L1CommandExecutor getInstance() {
        return new L1CharResetTest();
    }

    @Override
    public void execute(L1PcInstance pc, String cmdName, String arg) {
        try {
            StringTokenizer tok = new StringTokenizer(arg);
            int level = Integer.parseInt(tok.nextToken());
            int elixirStats = tok.hasMoreTokens() ? Integer.parseInt(tok.nextToken()) : 0;

            if (tok.hasMoreTokens()) {
                showUsage(pc, cmdName);
                return;
            }
            if (!IntRange.includes(level, 1, 99)) {
                pc.sendPackets(new S_SystemMessage("測試等級必須介於 1 到 99。"));
                return;
            }
            if (!IntRange.includes(elixirStats, 0, 5)) {
                pc.sendPackets(new S_SystemMessage("萬能藥點數必須介於 0 到 5。"));
                return;
            }
            if (pc.isInCharReset()) {
                pc.sendPackets(new S_SystemMessage("角色目前正在能力重置流程中，無法建立測試狀態。"));
                return;
            }
            if (!hasValidOriginalStats(pc)) {
                pc.sendPackets(new S_SystemMessage("角色的初始能力資料異常，六項 Original 能力總和必須為 75。"));
                return;
            }
            if (pc.getOriginalStr() + elixirStats > 35) {
                pc.sendPackets(new S_SystemMessage("測試萬能藥點數會使 STR 超過 35，已取消操作。"));
                return;
            }

            prepareCleanState(pc, level, elixirStats);
            pc.save();
            pc.sendPackets(new S_OwnCharStatus(pc));
            pc.sendPackets(new S_SystemMessage("回憶蠟燭測試狀態已建立：Level=" + level
                    + "、BonusStatus=0、ElixirStatus=" + elixirStats + "。"));
            pc.sendPackets(new S_SystemMessage("此指令會覆寫角色等級、能力與 HP/MP，請只用於 GM 測試角色。"));
        }
        catch (Exception e) {
            showUsage(pc, cmdName);
        }
    }

    private static void prepareCleanState(L1PcInstance pc, int level, int elixirStats) {
        pc.setInCharReset(false);
        pc.setTempLevel(0);
        pc.setTempMaxLevel(0);

        setBaseStatsToOriginal(pc);
        if (elixirStats > 0) {
            pc.addBaseStr((byte) elixirStats);
        }
        pc.setBonusStats(0);
        pc.setElixirStats(elixirStats);

        pc.setExp(ExpTable.getExpByLevel(1));
        pc.setHighLevel(1);
        pc.refresh();

        int initHp = calcInitialHp(pc);
        int initMp = calcInitialMp(pc);
        pc.addBaseMaxHp((short) (initHp - pc.getBaseMaxHp()));
        pc.addBaseMaxMp((short) (initMp - pc.getBaseMaxMp()));

        for (int currentLevel = 2; currentLevel <= level; currentLevel++) {
            short hp = CalcStat.calcStatHp(pc.getType(), pc.getBaseMaxHp(), pc.getBaseCon(), pc.getOriginalHpup());
            short mp = CalcStat.calcStatMp(pc.getType(), pc.getBaseMaxMp(), pc.getBaseWis(), pc.getOriginalMpup());
            pc.addBaseMaxHp(hp);
            pc.addBaseMaxMp(mp);
        }

        pc.setExp(ExpTable.getExpByLevel(level));
        pc.setHighLevel(level);
        pc.refresh();
        pc.setCurrentHpDirect(pc.getMaxHp());
        pc.setCurrentMpDirect(pc.getMaxMp());
    }

    private static int calcInitialHp(L1PcInstance pc) {
        switch (pc.getType()) {
        case 0:
            return 14;
        case 1:
            return 16;
        case 2:
            return 15;
        case 3:
        case 4:
            return 12;
        case 5:
            return 16;
        case 6:
            return 14;
        default:
            return 1;
        }
    }

    private static int calcInitialMp(L1PcInstance pc) {
        int wis = pc.getBaseWis();
        switch (pc.getType()) {
        case 0:
            if (wis == 11) {
                return 2;
            }
            if (wis >= 12 && wis <= 15) {
                return 3;
            }
            if (wis >= 16 && wis <= 18) {
                return 4;
            }
            return 2;
        case 1:
            if (wis >= 12 && wis <= 13) {
                return 2;
            }
            return 1;
        case 2:
            return (wis >= 16 && wis <= 18) ? 6 : 4;
        case 3:
            return (wis >= 16 && wis <= 18) ? 8 : 6;
        case 4:
            if (wis >= 16 && wis <= 18) {
                return 6;
            }
            if (wis >= 12 && wis <= 15) {
                return 4;
            }
            return 3;
        case 5:
            return 2;
        case 6:
            return (wis >= 16 && wis <= 18) ? 6 : 5;
        default:
            return 1;
        }
    }

    private static void setBaseStatsToOriginal(L1PcInstance pc) {
        pc.addBaseStr((byte) (pc.getOriginalStr() - pc.getBaseStr()));
        pc.addBaseCon((byte) (pc.getOriginalCon() - pc.getBaseCon()));
        pc.addBaseDex((byte) (pc.getOriginalDex() - pc.getBaseDex()));
        pc.addBaseCha((byte) (pc.getOriginalCha() - pc.getBaseCha()));
        pc.addBaseInt((byte) (pc.getOriginalInt() - pc.getBaseInt()));
        pc.addBaseWis((byte) (pc.getOriginalWis() - pc.getBaseWis()));
    }

    private static boolean hasValidOriginalStats(L1PcInstance pc) {
        if (pc.getOriginalStr() < 1 || pc.getOriginalCon() < 1 || pc.getOriginalDex() < 1
                || pc.getOriginalCha() < 1 || pc.getOriginalInt() < 1 || pc.getOriginalWis() < 1) {
            return false;
        }
        int total = pc.getOriginalStr() + pc.getOriginalCon() + pc.getOriginalDex()
                + pc.getOriginalCha() + pc.getOriginalInt() + pc.getOriginalWis();
        return total == 75;
    }

    private static void showUsage(L1PcInstance pc, String cmdName) {
        pc.sendPackets(new S_SystemMessage("用法：." + cmdName + " <等級 1-99> [萬能藥點數 0-5]"));
        pc.sendPackets(new S_SystemMessage("例如：." + cmdName + " 49 或 ." + cmdName + " 80 5"));
    }
}
