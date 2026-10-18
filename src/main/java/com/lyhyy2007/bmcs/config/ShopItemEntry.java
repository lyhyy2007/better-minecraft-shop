package com.lyhyy2007.bmcs.config;

import com.lyhyy2007.bmcs.util.ItemNameUtil;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 一条商品（不可变）。
 *
 * <p>关键约定：
 * <ul>
 *   <li>{@link #template} 恒为 <b>1 件</b>，并通过 DataComponentPatch 携带附魔等元数据
 *       （{@code components} 只支持附魔）；交易时只做 {@code copy()}，绝不手工重建 ItemStack，
 *       也不会丢组件。</li>
 *   <li>交易单位统一是「件」：买 64 个石头 = 64 件。SDMShop 里“显示数量 / 计价数量 / 实际发放数量”
 *       三套算法的坑在这里被消除。</li>
 *   <li>配置里<b>没有</b> display_name：展示名一律由 {@link ItemNameUtil} 走游戏内置本地化，
 *       跟随客户端语言，模组物品由各自模组的 lang 提供。</li>
 *   <li>{@link #initialStock} 为 {@link #UNLIMITED} 表示不限量；否则是限量初始值，售出后扣减、回收后回升。</li>
 *   <li>{@link #side} 区分买入条目与卖出条目：买入条目只有 {@link #price}，卖出条目只有 {@link #sellPrice}，
 *       两边的价格来自不同目录、互不影响。</li>
 * </ul>
 *
 * @param id               交易寻址用的稳定 UUID（由 {@link #key} 派生，跨存档 / 跨重启稳定）
 * @param key              配置内的稳定键，买入形如 {@code stone.1}，卖出形如 {@code out.stone.1}
 *                         （可按此键用 /bmcs stock 调整库存）
 * @param categoryId       所属分类 id
 * @param template         单件物品模板（含附魔）
 * @param price            买入单价（买入条目 >= 1；卖出条目恒为 0）
 * @param sellPrice        卖出单价（卖出条目 >= 1；买入条目恒为 0）
 * @param initialStock     限量初始值，{@link #UNLIMITED} 表示不限量（卖出条目恒为不限量）
 * @param customComponents 配置里是否显式提供了附魔
 * @param side             买入 / 卖出方向
 */
public record ShopItemEntry(
        UUID id,
        String key,
        String categoryId,
        ItemStack template,
        int price,
        int sellPrice,
        int initialStock,
        boolean customComponents,
        ShopSide side
) {
    /** 不限量标记。 */
    public static final int UNLIMITED = -1;

    public ShopItemEntry {
        // 防御性归一化：模板永远只保留 1 件，数量由交易数量决定。
        template = template.copyWithCount(1);
        if (side == null) {
            side = ShopSide.BUY;
        }
    }

    public boolean unlimited() {
        return initialStock == UNLIMITED;
    }

    /** 是否允许回收（卖出）：只有卖出目录里的条目才可回收。 */
    public boolean sellable() {
        return side == ShopSide.SELL && sellPrice > 0;
    }

    /** 是否允许买入：只有买入目录里的条目才可买入。 */
    public boolean buyable() {
        return side == ShopSide.BUY && price > 0;
    }

    /** 展示名：走游戏内置本地化（custom_name → item_name → 物品翻译键）。 */
    public Component displayNameComponent() {
        return ItemNameUtil.displayName(template);
    }

    /** 单次交易上限：物品堆叠上限与 64 取最小，至少 1。 */
    public int tradeLimit() {
        return Math.max(1, Math.min(64, template.getMaxStackSize()));
    }
}
