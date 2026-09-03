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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import l1j.server.L1DatabaseFactory;
import l1j.server.server.templates.L1PetType;
import l1j.server.server.utils.IntRange;
import l1j.server.server.utils.SQLUtil;
import l1j.server.server.utils.collections.Maps;

public class PetTypeTable {
	private static PetTypeTable _instance;

	private static Logger _log = Logger.getLogger(PetTypeTable.class.getName());

	private Map<Integer, L1PetType> _types = Maps.newMap();

	private Set<String> _defaultNames = new HashSet<String>();

	public static void load() {
		_instance = new PetTypeTable();
	}

	public static PetTypeTable getInstance() {
		return _instance;
	}

	private PetTypeTable() {
		loadTypes();
	}

	private void loadTypes() {
		Connection con = null;
		PreparedStatement pstm = null;
		ResultSet rs = null;
		try {
			con = L1DatabaseFactory.getInstance().getConnection();
			pstm = con.prepareStatement("SELECT * FROM pettypes");

			rs = pstm.executeQuery();

			while (rs.next()) {
				int baseNpcId = rs.getInt("BaseNpcId");
				int petNpcId = rs.getInt("PetNpcId");
				String name = rs.getString("Name");
				int itemIdForTaming = rs.getInt("ItemIdForTaming");
				int hpUpMin = rs.getInt("HpUpMin");
				int hpUpMax = rs.getInt("HpUpMax");
				int mpUpMin = rs.getInt("MpUpMin");
				int mpUpMax = rs.getInt("MpUpMax");
				int evolvItemId = rs.getInt("EvolvItemId");
				int npcIdForEvolving = rs.getInt("NpcIdForEvolving");
				int msgIds[] = new int[5];
				for (int i = 0; i < 5; i++) {
					msgIds[i] = rs.getInt("MessageId" + (i + 1));
				}
				int defyMsgId = rs.getInt("DefyMessageId");
				boolean canUseEquipment =  rs.getBoolean("canUseEquipment");
				IntRange hpUpRange = new IntRange(hpUpMin, hpUpMax);
				IntRange mpUpRange = new IntRange(mpUpMin, mpUpMax);
				L1PetType type = new L1PetType(baseNpcId, petNpcId, name,
						itemIdForTaming, hpUpRange, mpUpRange, evolvItemId,
						npcIdForEvolving, msgIds, defyMsgId, canUseEquipment);

				if (type.getBaseNpcTemplate() == null) {
					_log.severe("pettypes: BaseNpcId " + baseNpcId
							+ " does not exist in npc table. Entry skipped.");
					continue;
				}
				if (type.getPetNpcTemplate() == null) {
					_log.severe("pettypes: PetNpcId " + type.getPetNpcId()
							+ " does not exist in npc table. Entry skipped.");
					continue;
				}

				registerType(baseNpcId, type, "BaseNpcId");
				if (type.getPetNpcId() != baseNpcId) {
					registerType(type.getPetNpcId(), type, "PetNpcId");
				}
				_defaultNames.add(name.toLowerCase());
			}
		}
		catch (SQLException e) {
			_log.log(Level.SEVERE, e.getLocalizedMessage(), e);
		}
		finally {
			SQLUtil.close(rs);
			SQLUtil.close(pstm);
			SQLUtil.close(con);
		}
	}

	private void registerType(int npcId, L1PetType type, String source) {
		L1PetType previous = _types.put(npcId, type);
		if ((previous != null) && (previous != type)) {
			_log.warning("pettypes: duplicate NPC mapping for " + npcId
					+ " from " + source + "; later entry overrides earlier entry.");
		}
	}

	public L1PetType get(int npcId) {
		return _types.get(npcId);
	}

	public boolean isNameDefault(String name) {
		return _defaultNames.contains(name.toLowerCase());
	}
}
