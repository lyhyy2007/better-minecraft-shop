package com.lyhyy2007.bmcs.config;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * 商店分类（商品页签）。
 *
 * @param id          分类 id（配置内唯一）
 * @param displayName 展示名，支持翻译键；空串表示直接用 id
 * @param icon        页签图标物品（空堆栈表示无图标）
 * @param items       该分类下的商品
 */
public record ShopCategory(String id, String displayName, ItemStack icon, List<ShopItemEntry> items) {
    public ShopCategory {
        items = List.copyOf(items);
    }
}
