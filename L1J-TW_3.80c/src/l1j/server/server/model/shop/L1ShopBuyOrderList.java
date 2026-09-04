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
package l1j.server.server.model.shop;

import java.util.List;

import l1j.server.Config;
import l1j.server.server.model.L1Inventory;
import l1j.server.server.model.L1TaxCalculator;
import l1j.server.server.templates.L1ShopItem;
import l1j.server.server.utils.collections.Lists;

class L1ShopBuyOrder {
	private final L1ShopItem _item;

	private final int _count;

	public L1ShopBuyOrder(L1ShopItem item, int count) {
		_item = item;
		_count = count;
	}

	public L1ShopItem getItem() {
		return _item;
	}

	public int getCount() {
		return _count;
	}
}

public class L1ShopBuyOrderList {
	private final L1Shop _shop;

	private final List<L1ShopBuyOrder> _list = Lists.newList();

	private final L1TaxCalculator _taxCalc;

	private int _totalWeight = 0;

	private int _totalPrice = 0;

	private int _totalPriceTaxIncluded = 0;

	L1ShopBuyOrderList(L1Shop shop) {
		_shop = shop;
		_taxCalc = new L1TaxCalculator(shop.getNpcId());
	}

	private static final int MAX_NONSTACKABLE_ORDER_COUNT = 180;

	private int _nonStackableOrderCount = 0;

	public boolean add(int orderNumber, int count) {
		List<L1ShopItem> sellingItems = _shop.getSellingItems();
		if ((orderNumber < 0) || (orderNumber >= sellingItems.size())
				|| (count <= 0) || (count > L1Inventory.MAX_AMOUNT)) {
			return false;
		}

		L1ShopItem shopItem = sellingItems.get(orderNumber);
		int packCount = shopItem.getPackCount();
		if (packCount <= 0) {
			return false;
		}

		double scaledPrice = shopItem.getPrice()
				* Config.RATE_SHOP_SELLING_PRICE;
		if (Double.isNaN(scaledPrice) || Double.isInfinite(scaledPrice)
				|| (scaledPrice < 0) || (scaledPrice > L1Inventory.MAX_AMOUNT)) {
			return false;
		}

		int price = (int) scaledPrice;
		int taxedPrice = _taxCalc.layTax(price);
		if ((taxedPrice < 0) || (taxedPrice < price)) {
			return false;
		}

		long itemCount = (long) count * packCount;
		long orderPrice = (long) price * count;
		long orderPriceTaxIncluded = (long) taxedPrice * count;
		long orderWeight = (long) shopItem.getItem().getWeight() * itemCount;
		if ((itemCount <= 0) || (itemCount > L1Inventory.MAX_AMOUNT)
				|| (orderPrice < 0) || (orderPrice > L1Inventory.MAX_AMOUNT)
				|| (orderPriceTaxIncluded < 0)
				|| (orderPriceTaxIncluded > L1Inventory.MAX_AMOUNT)
				|| (orderWeight < 0) || (orderWeight > Integer.MAX_VALUE)) {
			return false;
		}

		long newTotalPrice = (long) _totalPrice + orderPrice;
		long newTotalPriceTaxIncluded = (long) _totalPriceTaxIncluded
				+ orderPriceTaxIncluded;
		long newTotalWeight = (long) _totalWeight + orderWeight;
		if ((newTotalPrice > L1Inventory.MAX_AMOUNT)
				|| (newTotalPriceTaxIncluded > L1Inventory.MAX_AMOUNT)
				|| (newTotalWeight > Integer.MAX_VALUE)) {
			return false;
		}

		if (!shopItem.getItem().isStackable()) {
			long newNonStackableCount = (long) _nonStackableOrderCount
					+ itemCount;
			if (newNonStackableCount > MAX_NONSTACKABLE_ORDER_COUNT) {
				return false;
			}
			_nonStackableOrderCount = (int) newNonStackableCount;
		}

		_totalPrice = (int) newTotalPrice;
		_totalPriceTaxIncluded = (int) newTotalPriceTaxIncluded;
		_totalWeight = (int) newTotalWeight;

		if (shopItem.getItem().isStackable()) {
			_list.add(new L1ShopBuyOrder(shopItem, (int) itemCount));
			return true;
		}

		for (int i = 0; i < (int) itemCount; i++) {
			_list.add(new L1ShopBuyOrder(shopItem, 1));
		}
		return true;
	}

	List<L1ShopBuyOrder> getList() {
		return _list;
	}

	public int getTotalWeight() {
		return _totalWeight;
	}

	public int getTotalPrice() {
		return _totalPrice;
	}

	public int getTotalPriceTaxIncluded() {
		return _totalPriceTaxIncluded;
	}

	L1TaxCalculator getTaxCalculator() {
		return _taxCalc;
	}
}
