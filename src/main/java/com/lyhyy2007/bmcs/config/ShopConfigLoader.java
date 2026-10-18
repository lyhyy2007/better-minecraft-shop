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
 * <p>目录（两个都直接位于 {@code config/better-minecraft-shop/} 下，都可递归子目录）：
 * <ul>
 *   <li>{@code in/**.json}：玩家<b>买入</b>的商品。每条写 {@code item} + {@code price}
 *       （必填，&gt;= 1），可选 {@code stock}（{@code -1} = 不限量）与 {@code components}（附魔）；</li>
 *   <li>{@code out/**.json}：玩家<b>卖出</b>的商品。每条写 {@code item} + {@code sell_price}
 *       （必填，&gt;= 1），可选 {@code components}；卖出不限量，不读 {@code stock}。</li>
 * </ul>
 * 两个目录各自独立：同名分类、同一物品可以在买入 / 卖出栏位里各出现一次，
 * 两个方向各自去重、各自定价，互不影响。加载器<b>不会</b>向配置目录写出任何默认 JSON ——
 * 两个目录都为空时，商店就是空的。
 *
 * <p>防刷钱：同一物品（含附魔签名）同时出现在 in/ 与 out/ 时，如果 out/ 的回收价高于
 * in/ 的买入价，加载阶段会把回收价压回买入价并在日志里说明，避免「买进立刻卖出」套利。
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
    /** 配置格式版本：1 = 旧版单层结构，2 = in/ 与 out/ 分目录结构（当前）。 */
    public static final int FORMAT_VERSION = 2;
    /** 单文件体积上限：10MB。超过直接拒绝加载。 */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    /** 分类数量上限（同时也是网络包的分类上限）。 */
    public static final int MAX_CATEGORIES = 256;
    /** 商品总条目上限：10000 条需求留出 6 倍余量。 */
    public static final int MAX_ITEMS = 65536;
    /** 买入目录名：直接位于 {@code config/better-minecraft-shop/} 下。 */
    public static final String IN_DIR = "in";
    /** 卖出目录名：直接位于 {@code config/better-minecraft-shop/} 下。 */
    public static final String OUT_DIR = "out";
    /** 旧版商品目录名：只用于检测并提示迁移，不再读取、不会删除。 */
    public static final String LEGACY_DIR = "shop_items";
    /** 旧版单文件名：只用于检测并提示迁移，不再读取、不会删除。 */
    public static final String LEGACY_FILE = "shop_items.json";
    /** 卖出商品在 {@code byKey} 里的键前缀，避免和买入的 {@code 分类.序号} 撞车。 */
    private static final String SELL_KEY_PREFIX = "sell.";

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
    public static Result load(Path buyDir, Path sellDir, HolderLookup.Provider registries) {
        long start = System.nanoTime();
        duplicatesLogged = 0;
        entryLogsWritten = 0;
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);

        Map<String, PendingCategory> categories = new LinkedHashMap<>();
        // 去重按方向各管各的：in/ 与 out/ 是两份独立目录，同名分类在两边各占一项。
        DedupRegistry buyDedup = new DedupRegistry();
        DedupRegistry sellDedup = new DedupRegistry();
        int skipped = 0;
        int files = 0;

        for (Path file : collectFiles(buyDir)) {
            files++;
            skipped += parseAndMerge(file, ShopSide.BUY, categories, buyDedup, ops);
        }
        for (Path file : collectFiles(sellDir)) {
            files++;
            skipped += parseAndMerge(file, ShopSide.SELL, categories, sellDedup, ops);
        }
        // 两个目录都解析完之后再做跨目录的防刷钱校验（out/ 的回收价不得高于 in/ 的买入价）。
        clampSellPrices(categories);

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
                // 空分类照样保留（需求：新建分类后必须能在页签里选中它再加商品），
                // 只记一条日志说明为什么是空的。
                emptyCategories.add(pending.id);
            }
            outCategories.add(new ShopCategory(pending.side, pending.id, pending.displayName, pending.icon, items,
                    pending.editable));
        }
        if (!emptyCategories.isEmpty()) {
            BetterMinecraftShop.LOGGER.info("[{}] 以下分类暂时没有任何可用商品（仍是空分类，页面照常显示）：{}",
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
     * 解析单个配置文件：in/ 目录只按买入解析，out/ 目录只按卖出解析。
     *
     * <p>两个目录互不交叉：in/ 里写了 {@code sell_price} 不会被当成卖出商品，
     * out/ 里写了 {@code price} 也不会被当成买入商品（两种写错目录的字段都会在日志里提示）。
     *
     * @return 被跳过的文件 / 条目数
     */
    private static int parseAndMerge(Path file, ShopSide side, Map<String, PendingCategory> categories,
                                     DedupRegistry dedup, RegistryOps<JsonElement> ops) {
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
            FileParse parse = parseFile(file, root, ops, side);
            mergeInto(categories, parse.categories(), dedup);
            return parse.skipped();
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

    /**
     * 跨目录防刷钱：out/ 里的回收价不得高于 in/ 里同一物品（含附魔签名）的买入价。
     *
     * <p>分目录之后，「卖出价高于买入价」不再能在单条配置里发现，必须在两份目录都解析完之后
     * 按判重键对一遍。被压价的条目只改价格，不丢弃。
     *
     * @return 被压价的条目数
     */
    private static int clampSellPrices(Map<String, PendingCategory> categories) {
        Map<String, Integer> buyPrices = new LinkedHashMap<>();
        for (PendingCategory pending : categories.values()) {
            if (pending.side != ShopSide.BUY) {
                continue;
            }
            for (Candidate candidate : pending.items) {
                buyPrices.putIfAbsent(candidate.dedupKey(), candidate.price());
            }
        }
        if (buyPrices.isEmpty()) {
            return 0;
        }
        int clamped = 0;
        for (PendingCategory pending : categories.values()) {
            if (pending.side != ShopSide.SELL) {
                continue;
            }
            for (int i = 0; i < pending.items.size(); i++) {
                Candidate candidate = pending.items.get(i);
                Integer buyPrice = buyPrices.get(candidate.dedupKey());
                if (buyPrice == null || candidate.sellPrice() <= buyPrice) {
                    continue;
                }
                logEntry(false, "{} 的 sell_price({}) 高于 in/ 里同一物品的 price({})，已压回买入价，避免刷钱",
                        candidate.source(), candidate.sellPrice(), buyPrice);
                pending.items.set(i, new Candidate(candidate.dedupKey(), candidate.template(), candidate.price(),
                        buyPrice, candidate.stock(), candidate.customComponents(), candidate.priority(),
                        candidate.source()));
                clamped++;
            }
        }
        return clamped;
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
            // editable：游戏内「新建分类」写下的标记；手写 JSON 不写就是 false（不可在游戏内改）。
            boolean editable = categoryObject.has("editable")
                    && categoryObject.get("editable").isJsonPrimitive()
                    && categoryObject.get("editable").getAsJsonPrimitive().isBoolean()
                    && categoryObject.get("editable").getAsBoolean();
            categories.add(new PendingCategory(side, categoryId, categoryName,
                    resolveIcon(readString(categoryObject, "icon")), priority, editable, candidates));
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
        // in/ 与 out/ 各认各的字段：in/ 只认 price（+stock），out/ 只认 sell_price。
        int price;
        int sellPrice;
        int stock;
        if (side == ShopSide.BUY) {
            if (raw.price() < 1) {
                logEntry(true, "{} 的 price 必须 >= 1（当前 {}），in/ 里的买入条目已跳过", where, raw.price());
                return Optional.empty();
            }
            if (raw.sellPrice() > 0) {
                logEntry(false, "{} 位于 in/（买入）目录，sell_price 不生效：卖出价请写进 out/", where);
            }
            price = raw.price();
            sellPrice = 0;
            stock = raw.stock();
            if (stock < ShopItemEntry.UNLIMITED) {
                logEntry(false, "{} 的 stock({}) 非法，已按不限量处理", where, stock);
                stock = ShopItemEntry.UNLIMITED;
            }
        } else {
            if (raw.price() > 0) {
                logEntry(false, "{} 位于 out/（卖出）目录，price 不生效：卖出价请写 sell_price", where);
            }
            if (raw.sellPrice() < 1) {
                logEntry(true, "{} 缺少有效的 sell_price（out/ 里的卖出条目必须 >= 1），已跳过该条", where);
                return Optional.empty();
            }
            if (raw.stock() != ShopItemEntry.UNLIMITED) {
                logEntry(false, "{} 位于 out/（卖出）目录，stock 不生效：卖出不限量", where);
            }
            price = 0;
            sellPrice = raw.sellPrice();
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
                        category.priority, category.editable, List.of());
                categories.put(mapKey, existing);
            } else {
                if (existing.displayName.isEmpty() && !category.displayName.isEmpty()) {
                    existing.displayName = category.displayName;
                }
                if (existing.icon.isEmpty() && !category.icon.isEmpty()) {
                    existing.icon = category.icon;
                }
                if (category.editable) {
                    existing.editable = true;
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

    // ------------------------------------------------------------------
    // 内部数据结构
    // ------------------------------------------------------------------

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
        private boolean editable;

        private PendingCategory(ShopSide side, String id, String displayName, ItemStack icon, int priority,
                                boolean editable, List<Candidate> items) {
            this.side = side;
            this.id = id;
            this.displayName = displayName;
            this.icon = icon;
            this.priority = priority;
            this.editable = editable;
            // 解析阶段的条目先装在一个独立可变列表里，接受与否由 DedupRegistry 决定。
            this.items = new ArrayList<>(items);
        }

        /** 合并总表里的键：方向 + 分类 id，保证 in/ 与 out/ 里同名分类各占一项。 */
        private String mapKey() {
            return side.name() + "/" + id;
        }
    }
}
