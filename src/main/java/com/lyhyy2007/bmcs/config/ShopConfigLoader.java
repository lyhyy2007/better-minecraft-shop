package com.lyhyy2007.bmcs.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 多文件商店配置加载器。
 *
 * <p>目录：{@code config/better-minecraft-shop/shop_items/**.json}（可递归子目录），用的是第一版的
 * 单层结构：一条商品里同时写 {@code price} 与 {@code sell_price}。
 * <ul>
 *   <li>界面左侧「买入」栏位：用 {@code price} 结算，并展示 {@code stock}（限量）；</li>
 *   <li>界面左侧「卖出」栏位：用 {@code sell_price} 结算，卖出不限量；</li>
 *   <li>兼容：老版本单文件 {@code config/better-minecraft-shop/shop_items.json} 同样会被读取。</li>
 * </ul>
 * 同一份配置会为两个方向各解析一条商品（同一物品可以在买入 / 卖出栏位里各出现一次），
 * 两个方向各自去重、各自定价，互不影响。
 *
 * <p>健壮性设计（这是相对 SDMShop 那套「一坨 NBT / 一次解析失败整份坏档」的核心改进）：
 * <ol>
 *   <li><b>单文件隔离</b>：每个文件独立解析，抛出异常只跳过该文件并打日志，其余文件照常生效；</li>
 *   <li><b>单条目隔离</b>：文件内部逐条用 {@link Codec} 严格解析（{@code result()}），
 *       一条坏数据只丢这一条；</li>
 *   <li><b>体积上限</b>：单文件超过 {@link #MAX_FILE_BYTES}（10MB）直接拒绝，避免 OOM；</li>
 *   <li><b>注册表上下文</b>：用 {@link RegistryOps} 而不是裸 {@code JsonOps}，
 *       否则附魔这类需要查注册表的组件会被静默丢弃；</li>
 *   <li><b>确定性合并</b>：文件按路径排序处理，分类按首次出现顺序合并，
 *       商品在分类内的序号稳定，因此 {@code /bmcs stock set <分类>.<序号>} 的键跨重启不变。</li>
 * </ol>
 *
 * <p>去重：同一分类内（以及不同文件合并后）出现重复商品时，保留
 * {@link CategoryPriority 优先级}更高的一份，优先级相同则保留先出现的一份。
 * 判重键是「物品 id + 附魔签名」——没有附魔的条目严格按 item 去重，
 * 带附魔的条目是**不同的商品**，不会被合并掉。
 *
 * <p>组件：{@code components} 目前**只支持附魔**（{@code minecraft:enchantments} /
 * {@code minecraft:stored_enchantments}）；其它组件键会被忽略并打印提示，不再支持改名、lore 等。
 */
public final class ShopConfigLoader {
    /** 配置格式版本。 */
    public static final int FORMAT_VERSION = 1;
    /** 单文件体积上限：10MB。超过直接拒绝加载。 */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    /** 分类数量上限（同时也是网络包的分类上限）。 */
    public static final int MAX_CATEGORIES = 256;
    /** 商品总条目上限：10000 条需求留出 6 倍余量。 */
    public static final int MAX_ITEMS = 65536;
    /** 首次启动从 mod 资源里复制出去的默认配置文件名。 */
    public static final List<String> DEFAULT_FILE_NAMES = List.of(
            "01-weapons_tools.json", "02-hardware.json", "03-building.json", "04-stone.json",
            "05-wood.json", "06-material.json", "07-food.json", "08-seeds.json");
    public static final String BUNDLED_DIR = "shop_items";
    /** 卖出商品在 {@code byKey} 里的键前缀，避免和买入的 {@code 分类.序号} 撞车。 */
    private static final String SELL_KEY_PREFIX = "sell.";
    /** 内置资源全部读取失败时写出的最小配置文件。 */
    public static final String FALLBACK_FILE_NAME = "00-minimal.json";

    private static final int MAX_LOGGED_ERRORS = 40;
    private static final int MAX_LOGGED_DUPLICATES = 12;
    /** {@code components} 允许的组件键：只支持附魔（手持 / 附魔书）。 */
    private static final List<String> ENCHANTMENT_COMPONENT_KEYS =
            List.of("minecraft:enchantments", "minecraft:stored_enchantments");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String COMPONENT_KEY_SEPARATOR = "|";

    /** 逐条解析用的 Codec。物品数据全程走 DataComponent，不出现任何原始 NBT 操作。 */
    private static final Codec<RawItem> RAW_ITEM_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("item").forGetter(RawItem::item),
            // 已废弃字段：仍然读出来，只为打印一条迁移提示，不再参与任何逻辑。
            Codec.STRING.optionalFieldOf("display_name", "").forGetter(RawItem::deprecatedDisplayName),
            Codec.INT.optionalFieldOf("price", 0).forGetter(RawItem::price),
            Codec.INT.optionalFieldOf("sell_price", 0).forGetter(RawItem::sellPrice),
            Codec.INT.optionalFieldOf("stock", ShopItemEntry.UNLIMITED).forGetter(RawItem::stock),
            DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY)
                    .forGetter(RawItem::components)
    ).apply(instance, RawItem::new));

    private ShopConfigLoader() {
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 加载结果。
     *
     * @param categories        合并去重后的分类
     * @param entries           合并去重后的全部商品
     * @param files             实际参与解析的文件数
     * @param skipped           被跳过的文件 + 条目数
     * @param duplicates        被去重丢弃的条目数
     * @param componentEntries  带自定义组件的条目数
     * @param millis            耗时（毫秒）
     */
    public record Result(List<ShopCategory> categories, List<ShopItemEntry> entries, int files, int skipped,
                         int duplicates, int componentEntries, long millis) {
        public static Result empty() {
            return new Result(List.of(), List.of(), 0, 0, 0, 0, 0L);
        }
    }

    /**
     * 扫描并解析全部配置文件。
     *
     * <p>本方法不修改任何全局状态，也不会因为文件损坏而抛出异常——失败信息都在日志里。
     */
    public static Result load(Path dir, Path legacyFile, HolderLookup.Provider registries) {
        long start = System.nanoTime();
        duplicatesLogged = 0;
        entryLogsWritten = 0;
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);

        Map<String, PendingCategory> categories = new LinkedHashMap<>();
        // 去重按方向各管各的：同一条配置会在买入、卖出各生成一条商品，两者互不冲突。
        DedupRegistry buyDedup = new DedupRegistry();
        DedupRegistry sellDedup = new DedupRegistry();
        int skipped = 0;
        int files = 0;

        List<Path> paths = collectFiles(dir);
        // 兼容老版本单文件：内容结构与目录里的配置一样，一条物品同时带买入价与回收价。
        if (legacyFile != null && Files.isRegularFile(legacyFile) && !containsPath(paths, legacyFile)) {
            paths.add(legacyFile);
        }
        for (Path file : paths) {
            files++;
            skipped += parseAndMerge(file, categories, buyDedup, sellDedup, ops);
        }

        List<ShopCategory> outCategories = new ArrayList<>(categories.size());
        List<ShopItemEntry> outEntries = new ArrayList<>();
        List<String> emptyCategories = new ArrayList<>();
        boolean itemLimitHit = false;
        for (PendingCategory pending : categories.values()) {
            if (outCategories.size() >= MAX_CATEGORIES) {
                BetterMinecraftShop.LOGGER.warn("[{}] 分类数量超过上限 {}，其余分类被忽略",
                        BetterMinecraftShop.MOD_NAME, MAX_CATEGORIES);
                break;
            }
            List<ShopItemEntry> items = new ArrayList<>(pending.items.size());
            int ordinal = 0;
            for (Candidate candidate : pending.items) {
                if (outEntries.size() >= MAX_ITEMS) {
                    if (!itemLimitHit) {
                        BetterMinecraftShop.LOGGER.warn("[{}] 商品数量超过上限 {}，其余商品被忽略",
                                BetterMinecraftShop.MOD_NAME, MAX_ITEMS);
                        itemLimitHit = true;
                    }
                    break;
                }
                ordinal++;
                // 稳定键：分类 id + 该分类内的最终序号。序号只取决于文件顺序与配置内容，
                // 因此价格 / 库存调整不会改变它，存档里的库存也就不会错位。
                // 买入沿用原来的 {@code 分类.序号}，卖出加 sell. 前缀，两边键不冲突。
                String key = pending.side == ShopSide.BUY
                        ? pending.id + "." + ordinal
                        : SELL_KEY_PREFIX + pending.id + "." + ordinal;
                ShopItemEntry entry = new ShopItemEntry(stableId(key), key, pending.id, candidate.template(),
                        candidate.price(), candidate.sellPrice(), candidate.stock(),
                        candidate.customComponents(), pending.side);
                items.add(entry);
                outEntries.add(entry);
            }
            if (items.isEmpty()) {
                // 分类里的条目要么全部非法、要么都被更高优先级的分类抢走了：
                // 与其在界面上留一个空页签，不如不显示，并在日志里说清楚。
                emptyCategories.add(pending.id);
                continue;
            }
            outCategories.add(new ShopCategory(pending.side, pending.id, pending.displayName, pending.icon, items));
        }
        if (!emptyCategories.isEmpty()) {
            BetterMinecraftShop.LOGGER.info("[{}] 以下分类没有任何可用商品（条目非法或被更高优先级分类占用），已不显示：{}",
                    BetterMinecraftShop.MOD_NAME, emptyCategories);
        }

        // 带组件的条目数按**去重后**的最终目录统计，避免把被丢弃的重复项也算进去。
        int finalComponentEntries = 0;
        for (ShopItemEntry entry : outEntries) {
            if (entry.customComponents()) {
                finalComponentEntries++;
            }
        }

        long millis = (System.nanoTime() - start) / 1_000_000L;
        return new Result(List.copyOf(outCategories), List.copyOf(outEntries), files,
                skipped, buyDedup.duplicates() + sellDedup.duplicates(), finalComponentEntries, millis);
    }

    /**
     * 解析单个配置文件：只读一次、只解析一次 JSON，然后按买入 / 卖出各合并一遍。
     *
     * <p>一条配置会在两个方向各生成一条商品：买入条目取 {@code price} + {@code stock}，
     * 卖出条目取 {@code sell_price}；两边各自去重，互不影响。
     *
     * @return 被跳过的文件 / 条目数
     */
    private static int parseAndMerge(Path file, Map<String, PendingCategory> categories,
                                     DedupRegistry buyDedup, DedupRegistry sellDedup,
                                     RegistryOps<JsonElement> ops) {
        try {
            long size = Files.size(file);
            if (size <= 0L) {
                logEntry(false, "跳过空配置文件：{}", file);
                return 1;
            }
            if (size > MAX_FILE_BYTES) {
                logEntry(true, "配置文件过大（{} 字节 > 上限 {} 字节），已拒绝加载：{}",
                        size, MAX_FILE_BYTES, file);
                return 1;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // Windows 编辑器（记事本 / PowerShell Set-Content）常写出 UTF-8 BOM：
            // 先剔掉它，避免「文件明明是对的却整份解析失败」。
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            JsonElement rootElement = JsonParser.parseString(text);
            if (!rootElement.isJsonObject()) {
                throw new IllegalArgumentException("顶层必须是 JSON 对象：" + file);
            }
            JsonObject root = rootElement.getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : FORMAT_VERSION;
            if (version != FORMAT_VERSION) {
                BetterMinecraftShop.LOGGER.warn("[{}] {} 的 version={}，本模组当前支持 {}，将按 {} 解析",
                        BetterMinecraftShop.MOD_NAME, file.getFileName(), version, FORMAT_VERSION, FORMAT_VERSION);
            }
            int skipped = 0;
            FileParse buy = parseFile(file, root, ops, ShopSide.BUY);
            mergeInto(categories, buy.categories(), buyDedup);
            skipped += buy.skipped();
            FileParse sell = parseFile(file, root, ops, ShopSide.SELL);
            mergeInto(categories, sell.categories(), sellDedup);
            skipped += sell.skipped();
            return skipped;
        } catch (IOException e) {
            // 读取失败（权限 / 文件被占用）：只丢这个文件。
            logEntry(true, "读取配置文件失败，已跳过：{}｜{}", file, e.toString());
            return 1;
        } catch (RuntimeException e) {
            // 解析失败（JSON 语法 / 结构错误）：只丢这个文件，其余配置继续生效。
            logEntry(true, "配置文件解析失败，已跳过该文件：{}｜{}", file, e.toString());
            return 1;
        }
    }

    /** 目录扫描结果里是否已经包含这个文件（用于避免老版本单文件被读两遍）。 */
    private static boolean containsPath(List<Path> paths, Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        for (Path path : paths) {
            if (path.toAbsolutePath().normalize().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把内置默认配置写到配置目录（仅在目录里一个 JSON 都没有时调用）。
     *
     * @return 实际写出的文件名
     */
    public static List<String> writeDefaults(Path dir) {
        List<String> written = new ArrayList<>();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            BetterMinecraftShop.LOGGER.error("[{}] 无法创建配置目录：{}", BetterMinecraftShop.MOD_NAME, dir, e);
            return written;
        }
        for (String name : DEFAULT_FILE_NAMES) {
            Path target = dir.resolve(name);
            try {
                String text = readBundled(name);
                if (text == null) {
                    BetterMinecraftShop.LOGGER.error("[{}] 内置默认配置缺失，无法写出 {}（jar 是否完整？）",
                            BetterMinecraftShop.MOD_NAME, name);
                    continue;
                }
                Files.writeString(target, text, StandardCharsets.UTF_8);
                written.add(name);
            } catch (IOException e) {
                BetterMinecraftShop.LOGGER.error("[{}] 写出默认配置失败：{}", BetterMinecraftShop.MOD_NAME, target, e);
            }
        }
        if (!written.isEmpty()) {
            BetterMinecraftShop.LOGGER.info("[{}] 已生成默认配置 {} 个文件：{}",
                    BetterMinecraftShop.MOD_NAME, written.size(), dir.toAbsolutePath());
            return written;
        }
        // 兜底：连内置资源配置都读不到（jar 损坏 / 打包异常）时，也不要让新服主面对一个空商店。
        Path fallback = dir.resolve(FALLBACK_FILE_NAME);
        try {
            Files.writeString(fallback, FALLBACK_JSON, StandardCharsets.UTF_8);
            written.add(FALLBACK_FILE_NAME);
            BetterMinecraftShop.LOGGER.warn("[{}] 未能读取内置默认配置，已写出最小可用配置：{}",
                    BetterMinecraftShop.MOD_NAME, fallback.toAbsolutePath());
        } catch (IOException e) {
            BetterMinecraftShop.LOGGER.error("[{}] 写出最小配置也失败了：{}", BetterMinecraftShop.MOD_NAME, fallback, e);
        }
        return written;
    }

    // ------------------------------------------------------------------
    // 文件收集
    // ------------------------------------------------------------------

    /** 递归收集目录下的 .json；结果按路径排序，保证多次加载顺序一致。 */
    static List<Path> collectFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(ShopConfigLoader::looksLikeConfig)
                        .sorted(Comparator.comparing((Path path) -> path.toAbsolutePath().toString()))
                        .forEach(files::add);
            } catch (IOException e) {
                BetterMinecraftShop.LOGGER.error("[{}] 遍历配置目录失败：{}", BetterMinecraftShop.MOD_NAME, dir, e);
            }
        }
        return files;
    }

    /** 只认 .json，并跳过编辑器留下的临时 / 隐藏文件。 */
    private static boolean looksLikeConfig(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith(".") || name.endsWith("~") || name.endsWith(".tmp") || name.endsWith(".bak")) {
            return false;
        }
        return name.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    // ------------------------------------------------------------------
    // 单文件解析
    // ------------------------------------------------------------------

    private record FileParse(List<PendingCategory> categories, int skipped) {
    }

    private static FileParse parseFile(Path file, JsonObject root, RegistryOps<JsonElement> ops, ShopSide side) {
        List<PendingCategory> categories = new ArrayList<>();
        int skipped = 0;
        int categoryIndex = 0;

        JsonArray categoryArray = root.has("categories") && root.get("categories").isJsonArray()
                ? root.getAsJsonArray("categories") : new JsonArray();
        if (categoryArray.isEmpty()) {
            BetterMinecraftShop.LOGGER.warn("[{}] {} 里没有任何分类", BetterMinecraftShop.MOD_NAME, file.getFileName());
        }

        for (JsonElement categoryElement : categoryArray) {
            categoryIndex++;
            final int currentCategory = categoryIndex;
            if (!categoryElement.isJsonObject()) {
                logEntry(true, "{} 的第 {} 个分类不是 JSON 对象，已跳过", file.getFileName(), currentCategory);
                skipped++;
                continue;
            }
            JsonObject categoryObject = categoryElement.getAsJsonObject();
            String categoryId = readString(categoryObject, "id").trim().toLowerCase(Locale.ROOT);
            if (categoryId.isEmpty()) {
                logEntry(true, "{} 的第 {} 个分类缺少 id，已跳过", file.getFileName(), currentCategory);
                skipped++;
                continue;
            }
            String categoryName = readString(categoryObject, "display_name").trim();
            if (!CategoryPriority.known(categoryId)) {
                BetterMinecraftShop.LOGGER.info("[{}] 分类 {} 不在内置优先级表内（按 {} 处理）；"
                                + "需要精确控制时可在 JSON 里写 priority。内置顺序：{}",
                        BetterMinecraftShop.MOD_NAME, categoryId, CategoryPriority.UNKNOWN, CategoryPriority.order());
            }
            Integer explicitPriority = readInt(categoryObject, "priority");
            int priority = CategoryPriority.effective(categoryId, explicitPriority);
            List<Candidate> candidates = new ArrayList<>();

            JsonArray itemArray = categoryObject.has("items") && categoryObject.get("items").isJsonArray()
                    ? categoryObject.getAsJsonArray("items") : new JsonArray();
            for (int i = 0; i < itemArray.size(); i++) {
                JsonElement itemElement = itemArray.get(i);
                int line = i + 1;
                String where = file.getFileName() + " 分类 " + categoryId + " 第 " + line + " 条";
                // components 只支持附魔：先剔掉其它组件键（并打印提示），再交给 codec。
                DataResult<RawItem> result = RAW_ITEM_CODEC.parse(ops, sanitizeComponents(itemElement, where));
                // 严格模式：resultOrPartial 会把「附魔解析失败」当成「字段缺失」，
                // 交出一个没有附魔的物品 —— 正是要避免的静默丢数据。
                Optional<RawItem> parsed = result.result();
                if (parsed.isEmpty()) {
                    String message = result.error().map(error -> error.message()).orElse("未知错误");
                    logEntry(true, "{} 分类 {} 第 {} 条商品解析失败：{}｜原始内容 {}",
                            file.getFileName(), categoryId, line, message, itemElement);
                    skipped++;
                    continue;
                }
                Optional<Candidate> candidate = resolve(file, categoryId, line, parsed.get(), ops, priority, side);
                if (candidate.isEmpty()) {
                    skipped++;
                    continue;
                }
                Candidate value = candidate.get();
                // 去重统一在全局注册表里做（跨文件、跨分类），见 DedupRegistry。
                candidates.add(value);
            }
            categories.add(new PendingCategory(side, categoryId, categoryName,
                    resolveIcon(readString(categoryObject, "icon")), priority, candidates));
        }
        return new FileParse(List.copyOf(categories), skipped);
    }

    /**
     * {@code components} 只保留附魔。
     *
     * <p>改名、lore 等其它组件已经取消：这里在解析前把 {@code components} 里除
     * {@code minecraft:enchantments} / {@code minecraft:stored_enchantments} 之外的键剔掉，
     * 并逐条打印提示，避免整条商品因为一个不支持的组件被静默丢弃。
     */
    private static JsonElement sanitizeComponents(JsonElement element, String where) {
        if (!element.isJsonObject()) {
            return element;
        }
        JsonObject item = element.getAsJsonObject();
        if (!(item.get("components") instanceof JsonObject components)) {
            return element;
        }
        List<String> dropped = new ArrayList<>();
        for (String key : List.copyOf(components.keySet())) {
            if (!ENCHANTMENT_COMPONENT_KEYS.contains(key)) {
                dropped.add(key);
            }
        }
        if (dropped.isEmpty()) {
            return element;
        }
        JsonObject copy = item.deepCopy();
        JsonObject sanitized = copy.getAsJsonObject("components");
        for (String key : dropped) {
            sanitized.remove(key);
        }
        logEntry(false, "{} 的 components 只支持附魔（{}），已忽略：{}",
                where, String.join(" / ", ENCHANTMENT_COMPONENT_KEYS), String.join(", ", dropped));
        return copy;
    }

    /** 把一条原始配置解析成候选商品；任何不合法之处都只跳过这一条并明确报错。 */
    private static Optional<Candidate> resolve(Path file, String categoryId, int line, RawItem raw,
                                               RegistryOps<JsonElement> ops, int priority, ShopSide side) {
        String where = file.getFileName() + " 分类 " + categoryId + " 第 " + line + " 条";

        if (!raw.deprecatedDisplayName().isBlank()) {
            logEntry(false, "{} 使用了已移除的 display_name 字段（{}），该字段已被忽略："
                            + "物品名现在由游戏内置本地化提供（跟随客户端语言）。",
                    where, raw.deprecatedDisplayName());
        }

        ResourceLocation itemId = ResourceLocation.tryParse(raw.item().trim());
        if (itemId == null) {
            logEntry(true, "{} 的物品 id 非法：{}", where, raw.item());
            return Optional.empty();
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item == Items.AIR) {
            logEntry(true, "{} 的物品不存在或是空气：{}", where, itemId);
            return Optional.empty();
        }
        // 买入与卖出的价格各读各的列，互不影响：买入栏位只认 price，
        // 卖出栏位只认 sell_price（没写时才退回 price）。同一条配置解析两次，各取所需。
        int price;
        int sellPrice;
        int stock;
        if (side == ShopSide.BUY) {
            if (raw.price() < 1) {
                logEntry(true, "{} 的 price 必须 >= 1（当前 {}），买入栏位已跳过该条", where, raw.price());
                return Optional.empty();
            }
            price = raw.price();
            sellPrice = 0;
            stock = raw.stock();
            if (stock < ShopItemEntry.UNLIMITED) {
                logEntry(false, "{} 的 stock({}) 非法，已按不限量处理", where, stock);
                stock = ShopItemEntry.UNLIMITED;
            }
        } else {
            int payout = raw.sellPrice() > 0 ? raw.sellPrice() : raw.price();
            if (payout < 1) {
                logEntry(true, "{} 没有有效回收价（sell_price 或 price 至少要有一个 >= 1），卖出栏位已跳过该条",
                        where);
                return Optional.empty();
            }
            if (raw.price() >= 1 && payout > raw.price()) {
                // 回收价高于买入价 = 无限刷钱：沿用第一版的规则压回买入价。
                logEntry(false, "{} 的 sell_price({}) 高于 price({})，已压回 {}，避免刷钱",
                        where, raw.sellPrice(), raw.price(), raw.price());
                payout = raw.price();
            }
            price = 0;
            sellPrice = payout;
            // 卖出方向是「玩家把东西卖回商店」，不限制商店库存。
            stock = ShopItemEntry.UNLIMITED;
        }

        try {
            ItemStack template = new ItemStack(item);
            if (!raw.components().isEmpty()) {
                // applyComponents 只写入列出的组件（此处只剩附魔），其余默认组件保持原样。
                template.applyComponents(raw.components());
            }
            String dedupKey = itemId + COMPONENT_KEY_SEPARATOR + canonicalComponents(raw.components(), ops);
            return Optional.of(new Candidate(dedupKey, template, price, sellPrice, stock,
                    !raw.components().isEmpty(), priority, where));
        } catch (RuntimeException e) {
            logEntry(true, "{} 的 components 无法应用到 {}（附魔不合法）｜{}", where, itemId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 组件签名：把 DataComponentPatch 编码成「顶层键有序」的 JSON 字符串，用于判重。
     * 没有组件时返回空串，于是这类条目严格按 item 去重。
     */
    private static String canonicalComponents(DataComponentPatch patch, RegistryOps<JsonElement> ops) {
        if (patch.isEmpty()) {
            return "";
        }
        JsonElement encoded = DataComponentPatch.CODEC.encodeStart(ops, patch).result().orElse(null);
        if (encoded == null || !encoded.isJsonObject()) {
            return patch.toString();
        }
        TreeMap<String, JsonElement> sorted = new TreeMap<>();
        encoded.getAsJsonObject().entrySet().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return GSON.toJson(sorted);
    }

    // ------------------------------------------------------------------
    // 合并与去重
    // ------------------------------------------------------------------

    /** 把一份文件的分类合并进总表；去重交给 {@link DedupRegistry}（跨文件、跨分类全局唯一）。 */
    private static void mergeInto(Map<String, PendingCategory> categories, List<PendingCategory> incoming,
                                  DedupRegistry dedup) {
        for (PendingCategory category : incoming) {
            String mapKey = category.mapKey();
            PendingCategory existing = categories.get(mapKey);
            if (existing == null) {
                // 新分类：先建一个空的归属容器挂进总表，再由注册表逐条决定去留
                //（可能出现「这条已经被别的分类占用了」的情况）。
                existing = new PendingCategory(category.side, category.id, category.displayName, category.icon,
                        category.priority, List.of());
                categories.put(mapKey, existing);
            } else {
                if (existing.displayName.isEmpty() && !category.displayName.isEmpty()) {
                    existing.displayName = category.displayName;
                }
                if (existing.icon.isEmpty() && !category.icon.isEmpty()) {
                    existing.icon = category.icon;
                }
                if (existing.priority != category.priority) {
                    BetterMinecraftShop.LOGGER.warn("[{}] 分类 {} 在多份配置里的 priority 不一致（{} vs {}），"
                                    + "按先出现的 {} 处理", BetterMinecraftShop.MOD_NAME, category.id,
                            existing.priority, category.priority, existing.priority);
                }
            }
            // 快照遍历：注册表可能把条目从某个分类里摘走，不能直接迭代原列表。
            for (Candidate candidate : List.copyOf(category.items)) {
                dedup.accept(categories, existing, candidate);
            }
        }
    }

    /**
     * 全局去重注册表。
     *
     * <p>判重范围是**整份合并后的目录**（跨文件、跨分类），这正是需求「以 item 为唯一标识」
     * 的含义：同一个物品不能在两个分类里各卖一次，否则玩家会看到重复商品、
     * 而两个分类的库存又会互相打架。冲突时优先级更高的分类获胜；
     * 优先级相同时先出现的一方获胜（文件按路径排序，结果确定）。
     */
    private static final class DedupRegistry {
        private final Map<String, Candidate> owners = new LinkedHashMap<>();
        private final Map<String, String> ownerCategories = new LinkedHashMap<>();
        private int duplicates;

        boolean accept(Map<String, PendingCategory> categories, PendingCategory category, Candidate candidate) {
            String key = candidate.dedupKey();
            Candidate previous = owners.get(key);
            if (previous == null) {
                owners.put(key, candidate);
                ownerCategories.put(key, category.id);
                category.items.add(candidate);
                return true;
            }

            duplicates++;
            if (candidate.priority() > previous.priority()) {
                // 新的优先级更高：把旧条目从它原来的分类里摘掉，改挂到新分类下。
                PendingCategory owner = categories.get(ownerCategories.get(key));
                if (owner != null) {
                    owner.items.removeIf(existing -> existing == previous);
                }
                owners.put(key, candidate);
                ownerCategories.put(key, category.id);
                category.items.add(candidate);
                logDuplicate(candidate.source(), category.id, candidate, previous, candidate);
                return true;
            }
            logDuplicate(candidate.source(), category.id, candidate, previous, previous);
            return false;
        }

        int duplicates() {
            return duplicates;
        }
    }

    private static void logDuplicate(String source, String categoryId, Candidate candidate, Candidate previous,
                                     Candidate kept) {
        if (duplicatesLogged >= MAX_LOGGED_DUPLICATES) {
            return;
        }
        duplicatesLogged++;
        String suffix = duplicatesLogged == MAX_LOGGED_DUPLICATES
                ? "（其余重复项提示已省略，可用 /bmcs debug config 查看统计）" : "";
        BetterMinecraftShop.LOGGER.info("[{}] 重复商品：{} 中的 {} 与 {} 冲突（分类 {}），"
                        + "按优先级 {} 保留 {}，丢弃 {}。{}",
                BetterMinecraftShop.MOD_NAME, source, candidate.dedupKey(), previous.source(), categoryId,
                kept.priority(), kept.source(), kept == candidate ? previous.source() : candidate.source(), suffix);
    }

    private static int duplicatesLogged;
    /** 逐条报错 / 告警的条数上限（防止一份系统性写错的配置刷爆日志）。 */
    private static int entryLogsWritten;

    /**
     * 逐条报错 / 告警的统一出口：只打印前 {@link #MAX_LOGGED_ERRORS} 条，之后折叠成一行提示。
     *
     * <p>为什么需要：本机实测时，一份 10000 条全部写错的配置会把 10000 行（含原始 JSON）写进日志，
     * 服务端日志瞬间涨到十几 MB。真正需要的是「前几条精确原因 + 总量统计」。
     */
    private static void logEntry(boolean error, String format, Object... args) {
        if (entryLogsWritten < MAX_LOGGED_ERRORS) {
            entryLogsWritten++;
            writeEntryLog(error, format, args);
            if (entryLogsWritten == MAX_LOGGED_ERRORS) {
                BetterMinecraftShop.LOGGER.warn("[{}] 无效配置条目较多，后续同类报错已省略（总数见本次加载汇总）",
                        BetterMinecraftShop.MOD_NAME);
            }
            return;
        }
        entryLogsWritten++;
    }

    private static void writeEntryLog(boolean error, String format, Object... args) {
        Object[] full = new Object[args.length + 1];
        full[0] = BetterMinecraftShop.MOD_NAME;
        System.arraycopy(args, 0, full, 1, args.length);
        if (error) {
            BetterMinecraftShop.LOGGER.error("[{}] " + format, full);
        } else {
            BetterMinecraftShop.LOGGER.warn("[{}] " + format, full);
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static ItemStack resolveIcon(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId.trim());
        if (id == null) {
            logEntry(false, "分类图标 id 非法：{}", itemId);
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static String readString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static Integer readInt(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()
                ? null : element.getAsInt();
    }

    /** 由配置键派生稳定 UUID：同一份配置在任何存档 / 任何重启后都得到同一批交易 id。 */
    private static UUID stableId(String key) {
        return UUID.nameUUIDFromBytes(("better-minecraft-shop:" + key).getBytes(StandardCharsets.UTF_8));
    }

    /** 读取打包在 jar 里的默认配置（开发期读 build/resources/main，生产期读 mod jar）。 */
    private static String readBundled(String name) {
        String resourcePath = "data/" + BetterMinecraftShop.MOD_ID + "/" + BUNDLED_DIR + "/" + name;
        // 1) 本类所在 mod 的类加载器（NeoForge 把 mod jar 放在类路径上，开发期则是 build/resources/main）
        try (InputStream in = ShopConfigLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            BetterMinecraftShop.LOGGER.error("[{}] 读取默认配置失败：{}", BetterMinecraftShop.MOD_NAME, resourcePath, e);
        }
        // 2) 兜底：以绝对路径再试一次
        try (InputStream in = ShopConfigLoader.class.getResourceAsStream("/" + resourcePath)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            BetterMinecraftShop.LOGGER.error("[{}] 读取默认配置失败：/{}", BetterMinecraftShop.MOD_NAME, resourcePath, e);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 内部数据结构
    // ------------------------------------------------------------------

    /** 内置资源不可用时的最小可用配置（故意不含任何 components，确保一定能解析成功）。 */
    private static final String FALLBACK_JSON = """
            {
              "version": 1,
              "categories": [
                {
                  "id": "material",
                  "icon": "minecraft:iron_ingot",
                  "items": [
                    { "item": "minecraft:stone", "price": 10, "sell_price": 3, "stock": -1 },
                    { "item": "minecraft:iron_ingot", "price": 120, "sell_price": 40, "stock": -1 },
                    { "item": "minecraft:bread", "price": 20, "sell_price": 6, "stock": -1 }
                  ]
                }
              ]
            }
            """;

    private record RawItem(String item, String deprecatedDisplayName, int price, int sellPrice,
                           int stock, DataComponentPatch components) {
    }

    /** 解析出来、但还没分配最终序号的商品。 */
    private record Candidate(String dedupKey, ItemStack template, int price, int sellPrice, int stock,
                             boolean customComponents, int priority, String source) {
    }

    /** 合并中的分类（可变：需要在多文件之间累积条目）。买入与卖出的同名分类互不影响。 */
    private static final class PendingCategory {
        private final ShopSide side;
        private final String id;
        private final int priority;
        private final List<Candidate> items;
        private String displayName;
        private ItemStack icon;

        private PendingCategory(ShopSide side, String id, String displayName, ItemStack icon, int priority,
                                List<Candidate> items) {
            this.side = side;
            this.id = id;
            this.displayName = displayName;
            this.icon = icon;
            this.priority = priority;
            // 解析阶段的条目先装在一个独立可变列表里，接受与否由 DedupRegistry 决定。
            this.items = new ArrayList<>(items);
        }

        /** 合并总表里的键：方向 + 分类 id，保证 in/ 与 out/ 里同名分类各占一项。 */
        private String mapKey() {
            return side.name() + "/" + id;
        }
    }
}
