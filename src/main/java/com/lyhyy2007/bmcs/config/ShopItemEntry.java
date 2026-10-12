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
 *   <li>{@link #template} 恒为 <b>1 件</b>，并通过 DataComponentPatch 完整携带附魔、改名等元数据；
 *       交易时只做 {@code copy()}，绝不手工重建 ItemStack，也不会丢组件。</li>
 *   <li>交易单位统一是「件」：买 64 个石头 = 64 件。SDMShop 里“显示数量 / 计价数量 / 实际发放数量”
 *       三套算法的坑在这里被消除。</li>
 *   <li>配置里<b>没有</b> display_name：展示名一律由 {@link ItemNameUtil} 走游戏内置本地化，
 *       跟随客户端语言，模组物品由各自模组的 lang 提供。</li>
 *   <li>{@link #initialStock} 为 {@link #UNLIMITED} 表示不限量；否则是限量初始值，售出后扣减、回收后回升。</li>
 * </ul>
 *
 * @param id               交易寻址用的稳定 UUID（由 {@link #key} 派生，跨存档 / 跨重启稳定）
 * @param key              配置内的稳定键，形如 {@code stone.1}（可按此键用 /bmcs stock 调整库存）
 * @param categoryId       所属分类 id
 * @param template         单件物品模板（含组件）
 * @param price            买入单价（>= 1）
 * @param sellPrice        卖出单价（0 表示不可出售）
 * @param initialStock     限量初始值，{@link #UNLIMITED} 表示不限量
 * @param maxTrade         单次交易数量上限（还会被物品堆叠上限裁剪）
 * @param customComponents 配置里是否显式提供了 components（附魔 / 改名等），用于提示玩家
 */
public record ShopItemEntry(
        UUID id,
        String key,
        String categoryId,
        ItemStack template,
        int price,
        int sellPrice,
        int initialStock,
        int maxTrade,
        boolean customComponents
) {
    /** 不限量标记。 */
    public static final int UNLIMITED = -1;

    public ShopItemEntry {
        // 防御性归一化：模板永远只保留 1 件，数量由交易数量决定。
        template = template.copyWithCount(1);
    }

    public boolean unlimited() {
        return initialStock == UNLIMITED;
    }

    /** 是否允许回收（卖出）。 */
    public boolean sellable() {
        return sellPrice > 0;
    }

    /** 展示名：走游戏内置本地化（custom_name → item_name → 物品翻译键）。 */
    public Component displayNameComponent() {
        return ItemNameUtil.displayName(template);
    }

    /** 单次交易上限：配置值、物品堆叠上限、64 三者取最小，至少 1。 */
    public int tradeLimit() {
        return Math.max(1, Math.min(maxTrade, Math.min(64, template.getMaxStackSize())));
    }
}
