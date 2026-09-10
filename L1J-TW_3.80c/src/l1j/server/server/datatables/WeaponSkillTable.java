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
package l1j.server.server.datatables;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import l1j.server.L1DatabaseFactory;
import l1j.server.server.model.L1WeaponMagicService;
import l1j.server.server.model.L1WeaponSkill;
import l1j.server.server.utils.SQLUtil;
import l1j.server.server.utils.collections.Maps;

public class WeaponSkillTable {
	private static Logger _log = Logger.getLogger(WeaponSkillTable.class.getName());

	private static WeaponSkillTable _instance;

	private final Map<Integer, L1WeaponSkill> _weaponIdIndex = Maps.newMap();

	public static WeaponSkillTable getInstance() {
		if (_instance == null) {
			_instance = new WeaponSkillTable();
		}
		return _instance;
	}

	private WeaponSkillTable() {
		loadWeaponSkill();
	}

	private void loadWeaponSkill() {
		Connection con = null;
		PreparedStatement pstm = null;
		ResultSet rs = null;
		try {

			con = L1DatabaseFactory.getInstance().getConnection();
			pstm = con.prepareStatement("SELECT * FROM weapon_skill");
			rs = pstm.executeQuery();
			fillWeaponSkillTable(rs);
		}
		catch (SQLException e) {
			_log.log(Level.SEVERE, "error while creating weapon_skill table", e);
		}
		finally {
			SQLUtil.close(rs);
			SQLUtil.close(pstm);
			SQLUtil.close(con);
		}
	}

	private void fillWeaponSkillTable(ResultSet rs) throws SQLException {
		while (rs.next()) {
			int weaponId = rs.getInt("weapon_id");
			int probability = rs.getInt("probability");
			int fixDamage = rs.getInt("fix_damage");
			int randomDamage = rs.getInt("random_damage");
			int area = rs.getInt("area");
			int skillId = rs.getInt("skill_id");
			int skillTime = rs.getInt("skill_time");
			int effectId = rs.getInt("effect_id");
			int effectTarget = rs.getInt("effect_target");
			boolean isArrowType = rs.getBoolean("arrow_type");
			int attr = rs.getInt("attr");
			String formulaType = rs.getString("formula_type");
			boolean enabled = rs.getBoolean("enabled");
			if (formulaType != null) {
				formulaType = formulaType.trim();
			}
			if (enabled && !L1WeaponMagicService.isSupportedFormulaType(formulaType)) {
				_log.warning("Unsupported weapon magic formula_type for weapon_id "
						+ weaponId + ": " + formulaType);
				continue;
			}
			double strRate = rs.getDouble("str_rate");
			double dexRate = rs.getDouble("dex_rate");
			double intRate = rs.getDouble("int_rate");
			double spRate = rs.getDouble("sp_rate");
			int randomStatMask = rs.getInt("random_stat_mask");
			double randomStatRate = rs.getDouble("random_stat_rate");
			double enchantRate = rs.getDouble("enchant_rate");
			double enchantTriggerRate = rs.getDouble("enchant_trigger_rate");
			int enchantTriggerStartLevel = rs.getInt("enchant_trigger_start_level");
			int buffSkillId = rs.getInt("buff_skill_id");
			int buffStatMask = rs.getInt("buff_stat_mask");
			double buffStatRate = rs.getDouble("buff_stat_rate");
			double mrPenetration = rs.getDouble("mr_penetration");
			double attrPenetration = rs.getDouble("attr_penetration");
			int areaCenter = rs.getInt("area_center");
			boolean areaPvpProtection = rs.getBoolean("area_pvp_protection");
			int effectType = rs.getInt("effect_type");
			L1WeaponSkill weaponSkill = new L1WeaponSkill(weaponId, probability, fixDamage, randomDamage, area, skillId, skillTime, effectId,
					effectTarget, isArrowType, attr, formulaType, enabled, strRate, dexRate, intRate, spRate, randomStatMask, randomStatRate,
					enchantRate, enchantTriggerRate, enchantTriggerStartLevel, buffSkillId, buffStatMask, buffStatRate, mrPenetration,
					attrPenetration, areaCenter, areaPvpProtection, effectType);
			_weaponIdIndex.put(weaponId, weaponSkill);
		}
		_log.config("武器スキルリスト " + _weaponIdIndex.size() + "件ロード");
	}

	public L1WeaponSkill getTemplate(int weaponId) {
		return _weaponIdIndex.get(weaponId);
	}

}
