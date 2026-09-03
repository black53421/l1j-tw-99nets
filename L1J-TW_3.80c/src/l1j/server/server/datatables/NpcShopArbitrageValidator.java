package l1j.server.server.datatables;

import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

import l1j.server.Config;
import l1j.server.server.model.shop.L1Shop;
import l1j.server.server.templates.L1ShopItem;
import l1j.server.server.utils.collections.Maps;

final class NpcShopArbitrageValidator {
	private static final Logger _log =
			Logger.getLogger(NpcShopArbitrageValidator.class.getName());

	private static final class SellingPricePoint {
		private final int _npcId;
		private final int _orderPrice;
		private final int _packCount;

		private SellingPricePoint(int npcId, int orderPrice, int packCount) {
			_npcId = npcId;
			_orderPrice = orderPrice;
			_packCount = packCount;
		}
	}

	private NpcShopArbitrageValidator() {
	}

	static void validate(Map<Integer, L1Shop> allShops) {
		if (!Config.NPC_SHOP_ARBITRAGE_PROTECTION) {
			return;
		}

		Map<Integer, SellingPricePoint> lowestSellingPrices = Maps.newMap();

		for (L1Shop shop : allShops.values()) {
			for (L1ShopItem item : shop.getSellingItems()) {
				int packCount = item.getPackCount();
				if (packCount <= 0) {
					_log.severe("[ShopSecurity] Invalid selling pack count: npcId="
							+ shop.getNpcId() + ", itemId=" + item.getItemId()
							+ ", packCount=" + packCount);
					continue;
				}

				int orderPrice = (int) (item.getPrice()
						* Config.RATE_SHOP_SELLING_PRICE);
				if (orderPrice < 0) {
					continue;
				}

				SellingPricePoint current =
						lowestSellingPrices.get(item.getItemId());
				if ((current == null)
						|| ((long) orderPrice * current._packCount
								< (long) current._orderPrice * packCount)) {
					lowestSellingPrices.put(item.getItemId(),
							new SellingPricePoint(
									shop.getNpcId(), orderPrice, packCount));
				}
			}
		}

		int disabledCount = 0;
		for (L1Shop shop : allShops.values()) {
			Iterator<L1ShopItem> iterator =
					shop.getPurchasingItems().iterator();
			while (iterator.hasNext()) {
				L1ShopItem item = iterator.next();
				SellingPricePoint selling =
						lowestSellingPrices.get(item.getItemId());
				if (selling == null) {
					continue;
				}

				int packCount = item.getPackCount();
				if (packCount <= 0) {
					_log.severe("[ShopSecurity] Invalid purchasing pack count: npcId="
							+ shop.getNpcId() + ", itemId=" + item.getItemId()
							+ ", packCount=" + packCount);
					continue;
				}

				int purchasingUnitPrice = (int) (item.getPrice()
						* Config.RATE_SHOP_PURCHASING_PRICE / packCount);
				if (purchasingUnitPrice <= 0) {
					continue;
				}

				long purchasingValue =
						(long) purchasingUnitPrice * selling._packCount;
				long sellingValue = selling._orderPrice;
				boolean unsafe = Config.NPC_SHOP_ARBITRAGE_ALLOW_EQUAL_PRICE
						? purchasingValue > sellingValue
						: purchasingValue >= sellingValue;
				if (!unsafe) {
					continue;
				}

				iterator.remove();
				disabledCount++;
				_log.severe("[ShopSecurity] Disabled NPC purchasing entry: itemId="
						+ item.getItemId() + ", purchasingNpcId=" + shop.getNpcId()
						+ ", purchasingUnitPrice=" + purchasingUnitPrice
						+ ", lowestSellingNpcId=" + selling._npcId
						+ ", lowestSellingOrderPrice=" + selling._orderPrice
						+ ", lowestSellingPackCount=" + selling._packCount);
			}
		}

		_log.info("[ShopSecurity] NPC shop arbitrage validation complete. "
				+ "Disabled purchasing entries: " + disabledCount);
	}
}