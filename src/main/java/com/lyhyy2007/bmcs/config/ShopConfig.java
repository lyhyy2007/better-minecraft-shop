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
 * <p>配置位置：
 * <ul>
 *   <li><b>推荐</b>：{@code config/better-minecraft-shop/shop_items/**.json}，任意数量、可递归子目录；</li>
 *   <li><b>兼容</b>：老版本的 {@code config/better-minecraft-shop/shop_items.json} 仍会被读取。</li>
 * </ul>
 * 首次启动时，如果配置目录里一个 JSON 都没有，会把打包在 jar 里的默认配置
 * （{@code data/better_minecraft_shop/shop_items/*.json}）复制出去。
 *
 * <p>相对 SDMShop 的关键差异：SDMShop 的商品目录由客户端在 GUI 里编辑、通过 NBT 包上传并被服务端
 * 原样保存（无权限校验、无结构校验、无版本号），既可能被普通玩家改写，也可能因一次解析失败整份坏档。
 * 这里：
 * <ul>
 *   <li>目录只来自服务端本地 JSON，客户端永远不能写入；</li>
 *   <li>解析全部交给 {@link ShopConfigLoader}：单文件 / 单条目出错只跳过出错的那一份；</li>
 *   <li>一旦目录里一个配置都读不到，保留上一份可用数据，绝不把商店清空；</li>
 *   <li>成功加载才会递增 {@link #revision()}，客户端拿旧版本号交易会被拒绝并重新同步。</li>
 * </ul>
 */
public final class ShopConfig {
    /** 配置文件格式版本。 */
    public static final int FORMAT_VERSION = ShopConfigLoader.FORMAT_VERSION;
    /** 配置子目录名。 */
    public static final String DIR_NAME = ShopConfigLoader.BUNDLED_DIR;
    /** 老版本单文件名（兼容读取）。 */
    public static final String LEGACY_FILE_NAME = "shop_items.json";

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

    /** 商品配置目录：{@code config/better-minecraft-shop/shop_items}。 */
    public Path dir() {
        return configDir().resolve(DIR_NAME);
    }

    /** 老版本单文件路径。 */
    public Path legacyFile() {
        return configDir().resolve(LEGACY_FILE_NAME);
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
        return "目录版本 " + revision + "：" + categories.size() + " 个分类 / " + entries.size()
                + " 件商品 / " + loadedFiles + " 个文件 / 去重 " + duplicates + " 条 / 跳过 "
                + skippedEntries + " 条 / 耗时 " + lastLoadMillis + "ms";
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 读取（必要时生成）配置目录下的全部配置文件。
     *
     * @param registries 服务端注册表访问（附魔等组件解析必需）
     * @return 是否加载成功；失败时若此前已加载过，会保留旧数据
     */
    public synchronized boolean load(HolderLookup.Provider registries) {
        Path dir = dir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            BetterMinecraftShop.LOGGER.error("[{}] 无法创建商店配置目录：{}",
                    BetterMinecraftShop.MOD_NAME, dir, e);
            return false;
        }

        Path legacy = legacyFile();
        if (ShopConfigLoader.collectFiles(dir, legacy).isEmpty()) {
            // 一个 JSON 都没有：把 jar 里的默认配置复制出来。
            ShopConfigLoader.writeDefaults(dir);
        }

        ShopConfigLoader.Result result;
        try {
            result = ShopConfigLoader.load(dir, legacy, registries);
        } catch (RuntimeException e) {
            BetterMinecraftShop.LOGGER.error("[{}] 商店配置解析异常，已保留上一份可用数据",
                    BetterMinecraftShop.MOD_NAME, e);
            return false;
        }

        if (result.files() == 0) {
            BetterMinecraftShop.LOGGER.warn("[{}] {} 下没有任何可读的 JSON 配置，已保留上一份数据"
                            + "（如需清空商店，请放一个 categories 为空的 JSON 文件）",
                    BetterMinecraftShop.MOD_NAME, dir.toAbsolutePath());
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
