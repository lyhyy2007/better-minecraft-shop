package com.lyhyy2007.bmcs.config;

/**
 * 商店的买卖方向。
 *
 * <p>买入与卖出各有独立的配置文件目录，JSON 字段也各自独立：
 * <ul>
 *   <li>{@link #BUY}：玩家花钱买物品，读 {@code config/better-minecraft-shop/in/**.json}，
 *       单价取 {@code price}（必填），受 {@code stock} 限量约束；</li>
 *   <li>{@link #SELL}：玩家卖物品换钱，读 {@code config/better-minecraft-shop/out/**.json}，
 *       单价取 {@code sell_price}（必填），不限量。</li>
 * </ul>
 * 同一个物品可以在两边各写一条，价格互不影响；界面左侧按方向分成两个栏位展示。
 */
public enum ShopSide {
    /** 买入：玩家花钱买物品（对应 {@code in/}）。 */
    BUY,
    /** 卖出：玩家卖物品换钱（对应 {@code out/}）。 */
    SELL;

    public boolean sells() {
        return this == SELL;
    }
}
