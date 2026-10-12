package com.lyhyy2007.bmcs.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分类优先级表：多文件配置出现「同一件商品」时的取舍依据。
 *
 * <p>需求给出的优先级顺序是
 * <b>武器工具 &gt; 五金 &gt; 建材 &gt; 石材 &gt; 木材 &gt; 原材料 &gt; 食物 &gt; 种子</b>。
 * 本类把它翻译成「数值越大越优先」，方便与 JSON 里可选的 {@code priority} 字段统一比较：
 * <pre>
 *   武器工具 weapons_tools / combat      80
 *   五金     hardware / ores            70
 *   建材     building / blocks          60
 *   石材     stone                      50
 *   木材     wood                       40
 *   原材料   material / misc            30
 *   食物     food                       20
 *   种子     seeds                      10
 *   其它未知分类                        5
 * </pre>
 *
 * <p>分类的 {@code priority} 字段可以显式覆盖上表（取值 {@code 0..100}），
 * 于是「优先级」这件事既能用内置表零配置工作，也能被服主精确控制。
 *
 * <p>本类不依赖任何 Minecraft 类型，因此可以脱离游戏单独跑校验（见 {@code tools/}）。
 */
public final class CategoryPriority {
    /** 显式 priority 的允许区间。 */
    public static final int MIN = 0;
    public static final int MAX = 100;
    /** 未在表内的分类的默认优先级：低于所有内置分类。 */
    public static final int UNKNOWN = 5;

    /** 需求文档给出的优先级顺序（从高到低），用于日志与文档。 */
    public static final List<String> ORDER = List.of(
            "weapons_tools", "hardware", "building", "stone", "wood", "material", "food", "seeds");

    private static final Map<String, Integer> TABLE = Map.ofEntries(
            // 0 武器工具
            Map.entry("weapons_tools", 80), Map.entry("weapon", 80), Map.entry("weapons", 80),
            Map.entry("tools", 80), Map.entry("tool", 80), Map.entry("combat", 80),
            Map.entry("equipment", 80), Map.entry("gear", 80), Map.entry("武器工具", 80),
            Map.entry("武器", 80), Map.entry("工具", 80), Map.entry("战斗", 80),
            // 1 五金
            Map.entry("hardware", 70), Map.entry("metal", 70), Map.entry("metals", 70),
            Map.entry("ingot", 70), Map.entry("ingots", 70), Map.entry("ore", 70),
            Map.entry("ores", 70), Map.entry("五金", 70), Map.entry("金属", 70), Map.entry("矿物", 70),
            // 2 建材
            Map.entry("building", 60), Map.entry("build", 60), Map.entry("blocks", 60),
            Map.entry("block", 60), Map.entry("decoration", 60), Map.entry("建材", 60),
            Map.entry("建筑", 60), Map.entry("方块", 60),
            // 3 石材
            Map.entry("stone", 50), Map.entry("stones", 50), Map.entry("rock", 50),
            Map.entry("石材", 50), Map.entry("石头", 50),
            // 4 木材
            Map.entry("wood", 40), Map.entry("woods", 40), Map.entry("log", 40),
            Map.entry("logs", 40), Map.entry("planks", 40), Map.entry("木材", 40), Map.entry("木头", 40),
            // 5 原材料
            Map.entry("material", 30), Map.entry("materials", 30), Map.entry("raw", 30),
            Map.entry("misc", 30), Map.entry("原材料", 30), Map.entry("材料", 30), Map.entry("杂项", 30),
            // 6 食物
            Map.entry("food", 20), Map.entry("foods", 20), Map.entry("foodstuff", 20),
            Map.entry("食物", 20), Map.entry("食品", 20),
            // 7 种子
            Map.entry("seeds", 10), Map.entry("seed", 10), Map.entry("farming", 10),
            Map.entry("crops", 10), Map.entry("crop", 10), Map.entry("种子", 10), Map.entry("农业", 10));

    private CategoryPriority() {
    }

    /** 内置表里的优先级；未知分类返回 {@link #UNKNOWN}。 */
    public static int of(String categoryId) {
        if (categoryId == null) {
            return UNKNOWN;
        }
        Integer value = TABLE.get(categoryId.trim().toLowerCase(Locale.ROOT));
        return value == null ? UNKNOWN : value;
    }

    /**
     * 生效优先级：JSON 里显式写了 {@code priority} 就用它（裁剪到 [0,100]），否则查表。
     *
     * @param categoryId 分类 id
     * @param explicit   配置里的 priority，{@code null} 表示未配置
     */
    public static int effective(String categoryId, Integer explicit) {
        if (explicit == null) {
            return of(categoryId);
        }
        return Math.max(MIN, Math.min(MAX, explicit));
    }

    /** 该分类是否命中内置优先级表。 */
    public static boolean known(String categoryId) {
        return categoryId != null && TABLE.containsKey(categoryId.trim().toLowerCase(Locale.ROOT));
    }

    /** 供日志 / 文档展示的优先级顺序。 */
    public static String order() {
        return String.join(" > ", ORDER);
    }
}
