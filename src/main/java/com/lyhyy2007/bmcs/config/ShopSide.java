package com.lyhyy2007.bmcs.config;

/**
 * 商店的买卖方向。
 *
 * <p>买入与卖出共用同一份配置（{@code config/better-minecraft-shop/shop_items/**.json}），
 * 但界面左侧分成两个栏位展示，交易也各走各的价格字段：
 * <ul>
 *   <li>{@link #BUY}：玩家花钱买物品，单价取 {@code price}，受 {@code stock} 限量约束；</li>
 *   <li>{@link #SELL}：玩家卖物品换钱，单价取 {@code sell_price}，不限量。</li>
 * </ul>
 * 同一个物品会在两个方向各生成一条商品，价格互不影响。
 */
public enum ShopSide {
    /** 买入：玩家花钱买物品。 */
    BUY,
    /** 卖出：玩家卖物品换钱。 */
    SELL;

    public boolean sells() {
        return this == SELL;
    }
}
