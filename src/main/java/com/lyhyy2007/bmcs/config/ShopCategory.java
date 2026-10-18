package com.lyhyy2007.bmcs.config;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * 商店分类（商品页签）。
 *
 * <p>买入与卖出各自拥有独立的分类集合：{@code in/} 目录里出现的分类只会出现在「买入」栏位，
 * {@code out/} 目录里的分类只会出现在「卖出」栏位。同名分类在两边各有一份，互不合并。
 *
 * @param side        该分类属于买入还是卖出
 * @param id          分类 id（同一方向内唯一）
 * @param displayName 展示名，支持翻译键；空串表示直接用 id
 * @param icon        页签图标物品（空堆栈表示无图标）
 * @param items       该分类下的商品
 */
public record ShopCategory(ShopSide side, String id, String displayName, ItemStack icon,
                           List<ShopItemEntry> items) {
    public ShopCategory {
        if (side == null) {
            side = ShopSide.BUY;
        }
        items = List.copyOf(items);
    }
}
