package com.lyhyy2007.bmcs.config;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 商店商品配置（服务端权威，多文件聚合后的内存视图）。
 *
 * <p>配置位置（两个目录都<b>直接</b>位于 {@code config/better-minecraft-shop/} 下，均可递归子目录）：
 * <ul>
 *   <li>{@code in/**.json}：玩家<b>买入</b>的商品，每条写 {@code item} + {@code price}
 *       （必填，&gt;= 1），可选 {@code stock}（{@code -1} = 不限量）与 {@code components}（附魔）；</li>
 *   <li>{@code out/**.json}：玩家<b>卖出</b>的商品，每条写 {@code item} + {@code sell_price}
 *       （必填，&gt;= 1），可选 {@code components}；卖出不限量。</li>
 * </ul>
 * 两个目录在启动时自动创建，但<b>不会写入任何默认 JSON</b>：目录为空，商店就是空的。
 * 旧版布局（{@code shop_items/}、{@code shop_items.json}）不再读取，也不会被修改或删除 ——
 * 检测到旧数据时只打印一条迁移提示（迁移脚本见 {@code tools/migrate_shop_items.py}）。
 *
 * <p>相对 SDMShop 的关键差异：SDMShop 的商品目录由客户端在 GUI 里编辑、通过 NBT 包上传并被服务端
 * 原样保存（无权限校验、无结构校验、无版本号），既可能被普通玩家改写，也可能因一次解析失败整份坏档。
 * 这里：
 * <ul>
 *   <li>目录只来自服务端本地 JSON，客户端永远不能整份写入；</li>
 *   <li>解析全部交给 {@link ShopConfigLoader}：单文件 / 单条目出错只跳过出错的那一份；</li>
 *   <li>只有「有文件但一个可用条目都没解析出来」时才保留上一份数据，避免一次手滑清空商店；</li>
 *   <li>成功加载才会递增 {@link #revision()}，客户端拿旧版本号交易会被拒绝并重新同步。</li>
 * </ul>
 */
public final class ShopConfig {
    /** 配置文件格式版本。 */
    public static final int FORMAT_VERSION = ShopConfigLoader.FORMAT_VERSION;
    /** 买入目录名（直接位于配置根目录下）。 */
    public static final String IN_DIR_NAME = ShopConfigLoader.IN_DIR;
    /** 卖出目录名（直接位于配置根目录下）。 */
    public static final String OUT_DIR_NAME = ShopConfigLoader.OUT_DIR;
    /** 旧版商品目录名：只用于提示迁移。 */
    public static final String LEGACY_DIR_NAME = ShopConfigLoader.LEGACY_DIR;
    /** 旧版单文件名：只用于提示迁移。 */
    public static final String LEGACY_FILE_NAME = ShopConfigLoader.LEGACY_FILE;

    private static final int MAX_LOGGED_COMPONENT_ENTRIES = 16;
    private static final ShopConfig INSTANCE = new ShopConfig();

    private volatile List<ShopCategory> categories = List.of();
    private volatile List<ShopItemEntry> entries = List.of();
    private volatile Map<UUID, ShopItemEntry> byId = Map.of();
    private volatile Map<String, ShopItemEntry> byKey = Map.of();
    private volatile int revision;
    private volatile boolean loaded;
    private volatile int loadedFiles;
    private volatile int skippedEntries;
    private volatile int duplicates;
    private volatile int componentEntries;
    private volatile long lastLoadMillis;
    /** 旧版布局提示只打印一次（配置热重载会反复调用 load，不必重复刷日志）。 */
    private boolean legacyWarned;

    private ShopConfig() {
    }

    public static ShopConfig get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 模组配置目录：{@code config/better-minecraft-shop}。 */
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve(BetterMinecraftShop.CONFIG_DIR);
    }

    /** 买入配置目录：{@code config/better-minecraft-shop/in}。 */
    public Path buyDir() {
        return configDir().resolve(IN_DIR_NAME);
    }

    /** 卖出配置目录：{@code config/better-minecraft-shop/out}。 */
    public Path sellDir() {
        return configDir().resolve(OUT_DIR_NAME);
    }

    /** 旧版商品目录：{@code config/better-minecraft-shop/shop_items}（只提示，不读取）。 */
    public Path legacyDir() {
        return configDir().resolve(LEGACY_DIR_NAME);
    }

    /** 旧版单文件路径（只提示，不读取）。 */
    public Path legacyFile() {
        return configDir().resolve(LEGACY_FILE_NAME);
    }

    /**
     * 旧版目录 / 单文件里是否还有 JSON。只做存在性检测，不读取、不修改、不删除。
     *
     * <p>给 {@code /bmcs debug config} 与启动告警用：判断要不要提示服主去跑迁移脚本。
     */
    public boolean hasLegacyData() {
        return !ShopConfigLoader.collectFiles(legacyDir()).isEmpty() || Files.isRegularFile(legacyFile());
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** 目录版本号：每次成功加载 +1；客户端交易时回传，用于识别过期界面。 */
    public int revision() {
        return revision;
    }

    public boolean loaded() {
        return loaded;
    }

    public List<ShopCategory> categories() {
        return categories;
    }

    public List<ShopItemEntry> entries() {
        return entries;
    }

    public Optional<ShopItemEntry> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<ShopItemEntry> byKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** 参与解析的文件数。 */
    public int loadedFiles() {
        return loadedFiles;
    }

    /** 上次加载被跳过的条目 / 文件数。 */
    public int skipped() {
        return skippedEntries;
    }

    /** 上次加载被去重丢弃的条目数。 */
    public int duplicates() {
        return duplicates;
    }

    /** 带自定义组件的条目数。 */
    public int componentEntries() {
        return componentEntries;
    }

    /** 上次加载耗时（毫秒）。 */
    public long lastLoadMillis() {
        return lastLoadMillis;
    }

    /** 供命令输出使用的统计描述。 */
    public String statistics() {
        long buyEntries = 0;
        for (ShopItemEntry entry : entries) {
            if (entry.side() == ShopSide.BUY) {
                buyEntries++;
            }
        }
        long sellEntries = entries.size() - buyEntries;
        return "目录版本 " + revision + "：" + categories.size() + " 个分类 / " + entries.size()
                + " 件商品（买入 " + buyEntries + " / 卖出 " + sellEntries + "）/ " + loadedFiles
                + " 个文件 / 去重 " + duplicates + " 条 / 跳过 " + skippedEntries + " 条 / 耗时 "
                + lastLoadMillis + "ms";
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 读取 {@code in/} 与 {@code out/} 下的全部配置文件（不会写入任何默认 JSON）。
     *
     * @param registries 服务端注册表访问（附魔等组件解析必需）
     * @return 是否加载成功；只有当所有文件都解析失败（一个分类都没得到）且此前已加载过时，
     *         才会保留旧数据并返回 {@code false}；空分类属于正常状态，照常应用
     */
    public synchronized boolean load(HolderLookup.Provider registries) {
        Path buyDir = buyDir();
        Path sellDir = sellDir();
        try {
            Files.createDirectories(buyDir);
            Files.createDirectories(sellDir);
        } catch (IOException e) {
            BetterMinecraftShop.LOGGER.error("[{}] 无法创建商店配置目录：{} / {}",
                    BetterMinecraftShop.MOD_NAME, buyDir, sellDir, e);
            return false;
        }
        warnLegacyLayout();

        ShopConfigLoader.Result result;
        try {
            result = ShopConfigLoader.load(buyDir, sellDir, registries);
        } catch (RuntimeException e) {
            BetterMinecraftShop.LOGGER.error("[{}] 商店配置解析异常，已保留上一份可用数据",
                    BetterMinecraftShop.MOD_NAME, e);
            return false;
        }

        if (result.files() == 0) {
            // 严格空商店：两个目录里一个 JSON 都没有就是空商店。
            // 这里绝不能「回退到内置默认配置」——本模组不再向配置目录写任何默认 JSON。
            apply(result);
            BetterMinecraftShop.LOGGER.info("[{}] {} 与 {} 下没有任何 JSON，商店为空（不会写入默认配置）",
                    BetterMinecraftShop.MOD_NAME, buyDir.toAbsolutePath(), sellDir.toAbsolutePath());
            return true;
        }
        if (result.categories().isEmpty() && result.skipped() > 0 && loaded) {
            // 所有文件都没能解析出分类（多半是刚改坏的 JSON）：保留上一份，避免把商店清空。
            // 注意：只要解析出了分类就绝不走这里——刚新建、还没有商品的空分类是合法状态。
            BetterMinecraftShop.LOGGER.warn("[{}] 本次没有解析出任何分类（文件 {} 个，跳过 {} 条），已保留上一份数据",
                    BetterMinecraftShop.MOD_NAME, result.files(), result.skipped());
            return false;
        }

        apply(result);
        BetterMinecraftShop.LOGGER.info("[{}] 商店配置加载完成：{} 个文件 → {} 个分类 / {} 件商品"
                        + "（其中 {} 件带自定义组件）{}，耗时 {} ms",
                BetterMinecraftShop.MOD_NAME, result.files(), categories.size(), entries.size(),
                result.componentEntries(),
                result.duplicates() == 0 ? "" : "；去重丢弃 " + result.duplicates() + " 条",
                result.millis());
        if (result.skipped() > 0) {
            BetterMinecraftShop.LOGGER.warn("[{}] 本次加载跳过 {} 条无效配置 / 文件，详见上方日志",
                    BetterMinecraftShop.MOD_NAME, result.skipped());
        }
        logComponentEntries();
        return true;
    }

    /** 旧版布局只提示、不读取、不改写；每次服务器生命周期最多提示一次。 */
    private void warnLegacyLayout() {
        if (legacyWarned) {
            return;
        }
        Path legacyDir = legacyDir();
        Path legacyFile = legacyFile();
        boolean hasDir = !ShopConfigLoader.collectFiles(legacyDir).isEmpty();
        boolean hasSingle = Files.isRegularFile(legacyFile);
        if (!hasDir && !hasSingle) {
            return;
        }
        legacyWarned = true;
        BetterMinecraftShop.LOGGER.warn("[{}] 检测到旧版商店配置（{} 或 {}）。本版本只读取 in/ 与 out/，"
                        + "不会读取、修改或删除旧文件；如需迁移，请运行 tools/migrate_shop_items.py"
                        + "（默认只预览，加 --apply 才会写入 in/ 与 out/）。",
                BetterMinecraftShop.MOD_NAME, legacyDir.toAbsolutePath(), legacyFile.toAbsolutePath());
    }

    private void apply(ShopConfigLoader.Result result) {
        this.categories = result.categories();
        this.entries = result.entries();
        Map<UUID, ShopItemEntry> idMap = new LinkedHashMap<>();
        Map<String, ShopItemEntry> keyMap = new LinkedHashMap<>();
        for (ShopItemEntry entry : result.entries()) {
            idMap.put(entry.id(), entry);
            keyMap.put(entry.key(), entry);
        }
        this.byId = Map.copyOf(idMap);
        this.byKey = Map.copyOf(keyMap);
        this.loadedFiles = result.files();
        this.skippedEntries = result.skipped();
        this.duplicates = result.duplicates();
        this.componentEntries = result.componentEntries();
        this.lastLoadMillis = result.millis();
        this.revision++;
        this.loaded = true;
    }

    /** 打印带自定义组件的条目，方便管理员确认附魔 / 改名确实被解析进来了。 */
    private void logComponentEntries() {
        int shown = 0;
        for (ShopItemEntry entry : entries) {
            if (!entry.customComponents()) {
                continue;
            }
            if (shown++ >= MAX_LOGGED_COMPONENT_ENTRIES) {
                BetterMinecraftShop.LOGGER.info("[{}] …其余带组件商品略", BetterMinecraftShop.MOD_NAME);
                break;
            }
            BetterMinecraftShop.LOGGER.info("[{}] 组件商品 {} = {}（附魔 {} 项）",
                    BetterMinecraftShop.MOD_NAME, entry.key(),
                    entry.template().getHoverName().getString(),
                    enchantmentCount(entry.template()));
        }
    }

    /** 附魔项数（走 DataComponent，不用已被 NeoForge 标记过时的 {@code ItemStack#getEnchantments}）。 */
    private static int enchantmentCount(ItemStack stack) {
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        return enchantments == null ? 0 : enchantments.size();
    }
}
