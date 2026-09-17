/**
 *                            License
 * THE WORK (AS DEFINED BELOW) IS PROVIDED UNDER THE TERMS OF THIS  
 * CREATIVE COMMONS PUBLIC LICENSE ("CCPL" OR "LICENSE"). 
 * THE WORK IS PROTECTED BY COPYRIGHT AND/OR OTHER APPLICABLE LAW.  
 * ANY USE OF THE WORK OTHER THAN AS AUTHORIZED UNDER THIS LICENSE OR  
 * COPYRIGHT LAW IS PROHIBITED.
 * 
 * BY EXERCISING ANY RIGHTS TO THE WORK PROVIDED HERE, YOU ACCEPT AND  
 * AGREE TO BE BOUND BY THE TERMS OF THIS LICENSE. TO THE EXTENT THIS LICENSE  
 * MAY BE CONSIDERED TO BE A CONTRACT, THE LICENSOR GRANTS YOU THE RIGHTS CONTAINED 
 * HERE IN CONSIDERATION OF YOUR ACCEPTANCE OF SUCH TERMS AND CONDITIONS.
 * 
 */
package l1j.server.server.clientpackets;

import java.util.logging.Level;
import java.util.logging.Logger;

import l1j.server.Config;
import l1j.server.server.ClientThread;
import l1j.server.server.datatables.CharacterTable;
import l1j.server.server.datatables.ExpTable;
import l1j.server.server.model.L1Teleport;
import l1j.server.server.model.Instance.L1ItemInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.serverpackets.S_CharReset;
import l1j.server.server.serverpackets.S_OwnCharStatus;
import l1j.server.server.serverpackets.S_OwnCharStatus2;
import l1j.server.server.utils.CalcInitHpMp;
import l1j.server.server.utils.CalcStat;

// Referenced classes of package l1j.server.server.clientpackets:
// ClientBasePacket

/**
 * 處理收到客戶端傳來角色升級/出生的封包
 */
public class C_CharReset extends ClientBasePacket {

	private static final String C_CHAR_RESET = "[C] C_CharReset";

	private static Logger _log = Logger.getLogger(C_CharReset.class.getName());

	private static final int RESET_ITEM_ID = 49142;
	private static final int BASE_STATUS_TOTAL = 75;
	private static final int MAX_STATUS = 35;

	private static final int[] ORIGINAL_STR = new int[]{ 13, 16, 11, 8, 12, 13, 11 };
	private static final int[] ORIGINAL_DEX = new int[]{ 10, 12, 12, 7, 15, 11, 10 };
	private static final int[] ORIGINAL_CON = new int[]{ 10, 14, 12, 12, 8, 14, 12 };
	private static final int[] ORIGINAL_WIS = new int[]{ 11, 9, 12, 12, 10, 12, 12 };
	private static final int[] ORIGINAL_CHA = new int[]{ 13, 12, 9, 8, 9, 8, 8 };
	private static final int[] ORIGINAL_INT = new int[]{ 10, 8, 12, 12, 11, 11, 12 };
	private static final int[] ORIGINAL_AMOUNT = new int[]{ 8, 4, 7, 16, 10, 6, 10 };

	/**
	 * //配置完初期點數 按確定 127.0.0.1 Request Work ID : 120 0000: 78 01 0d 0a 0b 0a 12
	 * 0d
	 * 
	 * //提升10及 127.0.0.1 Request Work ID : 120 0000: 78 02 07 00 //提升1及
	 * 127.0.0.1 Request Work ID : 120 0000: 78 02 00 04
	 * 
	 * //提升完等級 127.0.0.1 Request Work ID : 120 0000: 78 02 08 00 x...
	 * 
	 * //萬能藥 127.0.0.1 Request Work ID : 120 0000: 78 03 23 0a 0b 17 12 0d
	 */

	public C_CharReset(byte abyte0[], ClientThread clientthread) {
		super(abyte0);
		
		L1PcInstance pc = clientthread.getActiveChar();
		if (pc == null) {
			return;
		}
		if (!pc.isInCharReset()) {
			logInvalid(pc, "packet received outside reset session");
			return;
		}
		if (!pc.getInventory().checkItem(RESET_ITEM_ID)) {
			logInvalid(pc, "reset item missing during reset session");
			return;
		}
		
		int stage = readC();

		if (stage == 0x01) { // 0x01:キャラクター初期化
			int str = readC();
			int intel = readC();
			int wis = readC();
			int dex = readC();
			int con = readC();
			int cha = readC();
			if (!isValidInitialStats(pc, str, intel, wis, dex, con, cha)) {
				logInvalid(pc, "invalid initial stats");
				return;
			}
			int hp = CalcInitHpMp.calcInitHp(pc);
			int mp = CalcInitHpMp.calcInitMp(pc);
			pc.sendPackets(new S_OwnCharStatus2(pc, 0));
			/**
			 * 『來源:伺服器』<位址:64>{長度:8}(時間:1233793211)
             *  0000:  40 04 00 00 04 01 8b df   @.......
             *  尚未知的封包
			 */
			pc.sendPackets(new S_CharReset(pc, 1, hp, mp, 10, str, intel, wis, dex, con, cha));
			initCharStatus(pc, hp, mp, str, intel, wis, dex, con, cha);
			CharacterTable.getInstance();
			CharacterTable.saveCharStatus(pc);
		}
		else if (stage == 0x02) { // 0x02:ステータス再分配
			int type2 = readC();
			if (type2 == 0x00) { // 0x00:Lv1UP
				if (!canNormalLevelUp(pc, 1)) {
					return;
				}
				setLevelUp(pc, 1);
			}
			else if (type2 == 0x07) { // 0x07:Lv10UP
				if (!canNormalLevelUp(pc, 10)) {
					return;
				}
				setLevelUp(pc, 10);
			}
			else if ((type2 >= 0x01) && (type2 <= 0x06)) {
				if (!canBonusLevelUp(pc, type2)) {
					return;
				}
				addStatus(pc, type2);
				setLevelUp(pc, 1);
			}
			else if (type2 == 0x08) {
				if (!handleFinalLevelStat(pc, readC())) {
					return;
				}
				if (pc.getElixirStats() > 0) {
					pc.sendPackets(new S_CharReset(pc.getElixirStats()));
					return;
				}
				saveNewCharStatus(pc);
			}
			else {
				logInvalid(pc, "unknown stage 2 type=" + type2);
			}
		}
		else if (stage == 0x03) {
			int str = readC();
			int intel = readC();
			int wis = readC();
			int dex = readC();
			int con = readC();
			int cha = readC();
			if (!isLevelAllocationComplete(pc)) {
				logInvalid(pc, "elixir stage requested before level allocation completed");
				return;
			}
			if (!Config.CHAR_RESET_ALLOW_FREE_ELIXIR_STATS
					&& !isValidStrictElixirStats(pc, str, intel, wis, dex, con, cha)) {
				logInvalid(pc, "invalid strict elixir stats");
				return;
			}
			pc.addBaseStr((byte) (str - pc.getBaseStr()));
			pc.addBaseInt((byte) (intel - pc.getBaseInt()));
			pc.addBaseWis((byte) (wis - pc.getBaseWis()));
			pc.addBaseDex((byte) (dex - pc.getBaseDex()));
			pc.addBaseCon((byte) (con - pc.getBaseCon()));
			pc.addBaseCha((byte) (cha - pc.getBaseCha()));
			saveNewCharStatus(pc);
		}
		else {
			logInvalid(pc, "unknown stage=" + stage);
		}
	}

	private void saveNewCharStatus(L1PcInstance pc) {
		L1ItemInstance item = pc.getInventory().findItemId(RESET_ITEM_ID);
		if (item == null) {
			logInvalid(pc, "reset item missing at commit");
			return;
		}
		pc.setInCharReset(false);
		if (pc.getOriginalAc() > 0) {
			pc.addAc(pc.getOriginalAc());
		}
		if (pc.getOriginalMr() > 0) {
			pc.addMr(0 - pc.getOriginalMr());
		}
		pc.refresh();
		pc.setCurrentHp(pc.getMaxHp());
		pc.setCurrentMp(pc.getMaxMp());
		if (pc.getTempMaxLevel() != pc.getLevel()) {
			pc.setLevel(pc.getTempMaxLevel());
			pc.setExp(ExpTable.getExpByLevel(pc.getTempMaxLevel()));
		}
		if (pc.getLevel() > 50) {
			pc.setBonusStats(pc.getLevel() - 50);
		}
		else {
			pc.setBonusStats(0);
		}
		pc.sendPackets(new S_OwnCharStatus(pc));
		try {
			pc.getInventory().removeItem(item, 1);
			pc.save(); // 儲存玩家的資料到資料庫中
		}
		catch (Exception e) {
			_log.log(Level.SEVERE, e.getLocalizedMessage(), e);
		}
		L1Teleport.teleport(pc, 32628, 32772, (short) 4, 4, false);
	}

	private boolean isValidInitialStats(L1PcInstance pc, int str, int intel, int wis,
			int dex, int con, int cha) {
		int type = pc.getType();
		if ((type < 0) || (type >= ORIGINAL_STR.length)) {
			return false;
		}
		int amount = ORIGINAL_AMOUNT[type];
		if ((str < ORIGINAL_STR[type]) || (str > ORIGINAL_STR[type] + amount)
				|| (dex < ORIGINAL_DEX[type]) || (dex > ORIGINAL_DEX[type] + amount)
				|| (con < ORIGINAL_CON[type]) || (con > ORIGINAL_CON[type] + amount)
				|| (wis < ORIGINAL_WIS[type]) || (wis > ORIGINAL_WIS[type] + amount)
				|| (cha < ORIGINAL_CHA[type]) || (cha > ORIGINAL_CHA[type] + amount)
				|| (intel < ORIGINAL_INT[type]) || (intel > ORIGINAL_INT[type] + amount)) {
			return false;
		}
		return (str + intel + wis + dex + con + cha) == BASE_STATUS_TOTAL;
	}

	private boolean canNormalLevelUp(L1PcInstance pc, int addLv) {
		int currentLevel = pc.getTempLevel();
		int targetLevel = currentLevel + addLv;
		if ((addLv <= 0) || (targetLevel > pc.getTempMaxLevel())) {
			logInvalid(pc, "level increase exceeds reset max");
			return false;
		}
		if (currentLevel > 50) {
			logInvalid(pc, "normal level-up would skip bonus stat allocation");
			return false;
		}
		if (getBaseStatusTotal(pc) != BASE_STATUS_TOTAL) {
			logInvalid(pc, "invalid stat total before normal level-up");
			return false;
		}
		if (targetLevel > 51) {
			logInvalid(pc, "normal level-up would skip bonus stat allocation");
			return false;
		}
		return true;
	}

	private boolean canBonusLevelUp(L1PcInstance pc, int statusType) {
		int currentLevel = pc.getTempLevel();
		int targetLevel = currentLevel + 1;
		if (targetLevel > pc.getTempMaxLevel()) {
			logInvalid(pc, "bonus level-up exceeds reset max");
			return false;
		}
		if (currentLevel < 51) {
			logInvalid(pc, "bonus stat requested before level 51");
			return false;
		}
		if (getBaseStatusTotal(pc) != getExpectedStatusTotal(currentLevel) - 1) {
			logInvalid(pc, "invalid pending stat total before bonus level-up");
			return false;
		}
		return canAddStatus(pc, statusType);
	}

	private boolean handleFinalLevelStat(L1PcInstance pc, int statusType) {
		if ((statusType < 0) || (statusType > 6)) {
			logInvalid(pc, "invalid final stat type=" + statusType);
			return false;
		}
		if (pc.getTempLevel() != pc.getTempMaxLevel()) {
			logInvalid(pc, "final stage requested before reaching reset max");
			return false;
		}

		int currentTotal = getBaseStatusTotal(pc);
		int expectedTotal = getExpectedStatusTotal(pc.getTempMaxLevel());
		if (pc.getTempMaxLevel() <= 50) {
			if ((statusType != 0) || (currentTotal != expectedTotal)) {
				logInvalid(pc, "invalid final level state without bonus stat");
				return false;
			}
			return true;
		}

		if ((statusType == 0) || (currentTotal != expectedTotal - 1)) {
			logInvalid(pc, "invalid final level state with pending bonus stat");
			return false;
		}
		if (!canAddStatus(pc, statusType)) {
			return false;
		}
		addStatus(pc, statusType);
		if (getBaseStatusTotal(pc) != expectedTotal) {
			logInvalid(pc, "invalid stat total after final bonus stat");
			return false;
		}
		return true;
	}

	private boolean isLevelAllocationComplete(L1PcInstance pc) {
		return (pc.getTempLevel() == pc.getTempMaxLevel())
				&& (getBaseStatusTotal(pc) == getExpectedStatusTotal(pc.getTempMaxLevel()));
	}

	private boolean isValidStrictElixirStats(L1PcInstance pc, int str, int intel, int wis,
			int dex, int con, int cha) {
		if (!isStatusInRange(str) || !isStatusInRange(intel) || !isStatusInRange(wis)
				|| !isStatusInRange(dex) || !isStatusInRange(con) || !isStatusInRange(cha)) {
			return false;
		}
		if ((str < pc.getBaseStr()) || (intel < pc.getBaseInt()) || (wis < pc.getBaseWis())
				|| (dex < pc.getBaseDex()) || (con < pc.getBaseCon()) || (cha < pc.getBaseCha())) {
			return false;
		}
		int targetTotal = str + intel + wis + dex + con + cha;
		return (targetTotal - getBaseStatusTotal(pc)) == pc.getElixirStats();
	}

	private boolean canAddStatus(L1PcInstance pc, int statusType) {
		int value = getStatus(pc, statusType);
		if ((value < 0) || (value >= MAX_STATUS)) {
			logInvalid(pc, "stat increase exceeds limit type=" + statusType + " value=" + value);
			return false;
		}
		return true;
	}

	private boolean isStatusInRange(int value) {
		return (value >= 1) && (value <= MAX_STATUS);
	}

	private int getExpectedStatusTotal(int level) {
		return BASE_STATUS_TOTAL + Math.max(0, level - 50);
	}

	private int getBaseStatusTotal(L1PcInstance pc) {
		return pc.getBaseStr() + pc.getBaseInt() + pc.getBaseWis()
				+ pc.getBaseDex() + pc.getBaseCon() + pc.getBaseCha();
	}

	private int getStatus(L1PcInstance pc, int statusType) {
		switch (statusType) {
		case 1:
			return pc.getBaseStr();
		case 2:
			return pc.getBaseInt();
		case 3:
			return pc.getBaseWis();
		case 4:
			return pc.getBaseDex();
		case 5:
			return pc.getBaseCon();
		case 6:
			return pc.getBaseCha();
		default:
			return -1;
		}
	}

	private void addStatus(L1PcInstance pc, int statusType) {
		switch (statusType) {
		case 1:
			pc.addBaseStr((byte) 1);
			break;
		case 2:
			pc.addBaseInt((byte) 1);
			break;
		case 3:
			pc.addBaseWis((byte) 1);
			break;
		case 4:
			pc.addBaseDex((byte) 1);
			break;
		case 5:
			pc.addBaseCon((byte) 1);
			break;
		case 6:
			pc.addBaseCha((byte) 1);
			break;
		default:
			break;
		}
	}

	private void logInvalid(L1PcInstance pc, String reason) {
		_log.warning("[CHAR_RESET] Rejected packet: char=" + pc.getName()
				+ " objid=" + pc.getId() + " reason=" + reason);
	}

	private void initCharStatus(L1PcInstance pc, int hp, int mp, int str, int intel, int wis, int dex, int con, int cha) {
		pc.addBaseMaxHp((short) (hp - pc.getBaseMaxHp()));
		pc.addBaseMaxMp((short) (mp - pc.getBaseMaxMp()));
		pc.addBaseStr((byte) (str - pc.getBaseStr()));
		pc.addBaseInt((byte) (intel - pc.getBaseInt()));
		pc.addBaseWis((byte) (wis - pc.getBaseWis()));
		pc.addBaseDex((byte) (dex - pc.getBaseDex()));
		pc.addBaseCon((byte) (con - pc.getBaseCon()));
		pc.addBaseCha((byte) (cha - pc.getBaseCha()));
	}

	private void setLevelUp(L1PcInstance pc, int addLv) {
		pc.setTempLevel(pc.getTempLevel() + addLv);
		for (int i = 0; i < addLv; i++) {
			short randomHp = CalcStat.calcStatHp(pc.getType(), pc.getBaseMaxHp(), pc.getBaseCon(), pc.getOriginalHpup());
			short randomMp = CalcStat.calcStatMp(pc.getType(), pc.getBaseMaxMp(), pc.getBaseWis(), pc.getOriginalMpup());
			pc.addBaseMaxHp(randomHp);
			pc.addBaseMaxMp(randomMp);
		}
		int newAc = CalcStat.calcAc(pc.getTempLevel(), pc.getBaseDex());
		pc.sendPackets(new S_CharReset(pc, pc.getTempLevel(), pc.getBaseMaxHp(), pc.getBaseMaxMp(), newAc, pc.getBaseStr(), pc.getBaseInt(), 
				          pc.getBaseWis(), pc.getBaseDex(), pc.getBaseCon(), pc.getBaseCha()));
	}

	@Override
	public String getType() {
		return C_CHAR_RESET;
	}

}
