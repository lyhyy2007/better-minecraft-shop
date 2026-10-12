package com.lyhyy2007.bmcs.util;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import net.minecraft.core.component.DataComponents;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 物品本地化名称工具。
 *
 * <p>设计原则：**配置里不再维护名字**。配置只写 {@code item}（可选的 {@code components}），
 * 展示名一律走游戏内置本地化链路：
 * <ol>
 *   <li>{@code minecraft:custom_name} 组件（改名物品）优先；</li>
 *   <li>其次是 {@code minecraft:item_name} 组件；</li>
 *   <li>否则用 {@link ItemStack#getDescriptionId()} 得到翻译键（例如 {@code item.minecraft.stone}、
 *       药水这类按堆栈变化的物品会得到 {@code item.minecraft.potion.effect.xxx}），
 *       交给 {@link Component#translatable(String)} 由客户端语言文件翻译 —— 中文客户端显示中文，
 *       英文客户端显示英文，模组物品的语言文件也由各自模组提供；</li>
 *   <li>只有翻译键在整个语言表里都不存在时（模组物品没带 lang，或服务端只有原版 en_us），
 *       才退化成「把翻译键尾巴的可读形式当名字」，避免界面上直接出现 {@code item.xxx.yyy} 这种原始键。</li>
 * </ol>
 *
 * <p>注意 {@link Language#getInstance()} 在客户端是玩家当前语言（由客户端注入），
 * 在专用服务端只有原版 {@code en_us}（+ NeoForge 注入的服务端语言）。
 * 因此「有没有翻译」这个判断在客户端最准；{@code /bmcs debug item} 会把两侧的差异讲清楚。
 */
public final class ItemNameUtil {
    private ItemNameUtil() {
    }

    /** 展示名：等价于 {@link ItemStack#getHoverName()}，但缺翻译时不会把原始键名印到界面上。 */
    public static Component displayName(ItemStack stack) {
        if (stack.isEmpty()) {
            return Component.empty();
        }
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        if (custom != null) {
            return custom;
        }
        Component itemName = stack.get(DataComponents.ITEM_NAME);
        if (itemName != null) {
            return itemName;
        }
        String key = stack.getDescriptionId();
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(fallbackText(key));
    }

    /** 展平后的展示名（用于搜索索引与命令输出）。 */
    public static String plainText(ItemStack stack) {
        return displayName(stack).getString();
    }

    /** 该堆栈当前实际使用的翻译键。 */
    public static String descriptionKey(ItemStack stack) {
        return stack.isEmpty() ? "" : stack.getDescriptionId();
    }

    /** 当前语言表里是否有这个物品的翻译。 */
    public static boolean isLocalized(ItemStack stack) {
        return !stack.isEmpty() && isLocalized(stack.getDescriptionId());
    }

    /** 当前语言表里是否有这个翻译键。 */
    public static boolean isLocalized(String translationKey) {
        return translationKey != null && !translationKey.isEmpty() && Language.getInstance().has(translationKey);
    }

    /** 分类展示名：配置里的 display_name 优先（翻译键或字面量都支持），否则用内置翻译键，再否则用分类 id。 */
    public static Component categoryName(String displayName, String categoryId) {
        if (displayName != null && !displayName.isBlank()) {
            return resolveOr(displayName.trim(), displayName.trim());
        }
        return resolveOr(categoryKey(categoryId), categoryId);
    }

    /** 内置分类翻译键：{@code category.better_minecraft_shop.<分类id>}。 */
    public static String categoryKey(String categoryId) {
        return "category." + BetterMinecraftShop.MOD_ID + "." + categoryId;
    }

    /**
     * 能翻译就翻译，否则退化到可读文本。
     *
     * @param translationKey 翻译键
     * @param fallbackText   语言表里没有该键时显示的文本
     */
    public static Component resolveOr(String translationKey, String fallbackText) {
        if (translationKey == null || translationKey.isBlank()) {
            return Component.literal(fallbackText == null ? "" : fallbackText);
        }
        if (isLocalized(translationKey)) {
            return Component.translatable(translationKey);
        }
        String text = fallbackText == null || fallbackText.isBlank() ? translationKey : fallbackText;
        return Component.literal(text);
    }

    /** 把 {@code item.modid.some_thing} 变成 {@code some thing}。 */
    public static String fallbackText(String translationKey) {
        if (translationKey == null || translationKey.isEmpty()) {
            return "";
        }
        int dot = translationKey.lastIndexOf('.');
        String tail = dot >= 0 && dot + 1 < translationKey.length() ? translationKey.substring(dot + 1) : translationKey;
        return tail.replace('_', ' ');
    }
}
