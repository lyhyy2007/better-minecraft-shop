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
 * <p>目录：{@code config/better-minecraft-shop/shop_items/**.json}（递归，任意数量），
 * 外加对老版本单文件 {@code config/better-minecraft-shop/shop_items.json} 的兼容读取。
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
 * 判重键默认是「物品 id + 组件签名」——纯物品条目（没有 {@code components}）严格按 item 去重，
 * 而带附魔 / 改名的条目是**不同的商品**，不会被合并掉；配置里也可以写 {@code variant}
 * 显式区分同一物品的多种卖法。
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
    /** 内置资源全部读取失败时写出的最小配置文件。 */
    public static final String FALLBACK_FILE_NAME = "00-minimal.json";

    private static final int MAX_LOGGED_ERRORS = 40;
    private static final int MAX_LOGGED_DUPLICATES = 12;
    /** 需要做「纯字符串 → 文本组件」规范化的组件名。 */
    private static final List<String> NAME_COMPONENT_KEYS =
            List.of("minecraft:custom_name", "minecraft:item_name");
    private static final String LORE_COMPONENT_KEY = "minecraft:lore";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** 紧凑 Gson：把组件对象序列化成单行 JSON 文档字符串时用（不带缩进，避免把换行塞进名字里）。 */
    private static final Gson COMPACT_GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String COMPONENT_KEY_SEPARATOR = "|";

    /** 逐条解析用的 Codec。物品数据全程走 DataComponent，不出现任何原始 NBT 操作。 */
    private static final Codec<RawItem> RAW_ITEM_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("item").forGetter(RawItem::item),
            // 已废弃字段：仍然读出来，只为打印一条迁移提示，不再参与任何逻辑。
            Codec.STRING.optionalFieldOf("display_name", "").forGetter(RawItem::deprecatedDisplayName),
            Codec.STRING.optionalFieldOf("variant", "").forGetter(RawItem::variant),
            Codec.INT.fieldOf("price").forGetter(RawItem::price),
            Codec.INT.optionalFieldOf("sell_price", 0).forGetter(RawItem::sellPrice),
            Codec.INT.optionalFieldOf("stock", ShopItemEntry.UNLIMITED).forGetter(RawItem::stock),
            Codec.INT.optionalFieldOf("max_stack", 64).forGetter(RawItem::maxStack),
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
        List<Path> files = collectFiles(dir, legacyFile);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);

        Map<String, PendingCategory> categories = new LinkedHashMap<>();
        DedupRegistry dedup = new DedupRegistry();
        int skipped = 0;

        for (Path file : files) {
            try {
                long size = Files.size(file);
                if (size <= 0L) {
                    logEntry(false, "跳过空配置文件：{}", file);
                    skipped++;
                    continue;
                }
                if (size > MAX_FILE_BYTES) {
                    logEntry(true, "配置文件过大（{} 字节 > 上限 {} 字节），已拒绝加载：{}",
                            size, MAX_FILE_BYTES, file);
                    skipped++;
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                // Windows 编辑器（记事本 / PowerShell Set-Content）常写出 UTF-8 BOM：
                // 先剔掉它，避免「文件明明是对的却整份解析失败」。
                if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                    text = text.substring(1);
                }
                FileParse parsed = parseFile(file, text, ops);
                skipped += parsed.skipped();
                mergeInto(categories, parsed.categories(), dedup);
            } catch (IOException e) {
                // 读取失败（权限 / 文件被占用）：只丢这个文件。
                logEntry(true, "读取配置文件失败，已跳过：{}｜{}", file, e.toString());
                skipped++;
            } catch (RuntimeException e) {
                // 解析失败（JSON 语法 / 结构错误）：只丢这个文件，其余配置继续生效。
                logEntry(true, "配置文件解析失败，已跳过该文件：{}｜{}", file, e.toString());
                skipped++;
            }
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
                String key = pending.id + "." + ordinal;
                ShopItemEntry entry = new ShopItemEntry(stableId(key), key, pending.id, candidate.template(),
                        candidate.price(), candidate.sellPrice(), candidate.stock(), candidate.maxTrade(),
                        candidate.customComponents());
                items.add(entry);
                outEntries.add(entry);
            }
            if (items.isEmpty()) {
                // 分类里的条目要么全部非法、要么都被更高优先级的分类抢走了：
                // 与其在界面上留一个空页签，不如不显示，并在日志里说清楚。
                emptyCategories.add(pending.id);
                continue;
            }
            outCategories.add(new ShopCategory(pending.id, pending.displayName, pending.icon, items));
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
        return new Result(List.copyOf(outCategories), List.copyOf(outEntries), files.size(),
                skipped, dedup.duplicates(), finalComponentEntries, millis);
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
            BetterMinecraftShop.LOGGER.info("[{}] 已生成默认商店配置 {} 个文件：{}",
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

    /** 递归收集目录下的 .json，外加兼容用的老单文件；结果按路径排序，保证多次加载顺序一致。 */
    static List<Path> collectFiles(Path dir, Path legacyFile) {
        List<Path> files = new ArrayList<>();
        if (legacyFile != null && Files.isRegularFile(legacyFile)) {
            files.add(legacyFile);
        }
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

    private static FileParse parseFile(Path file, String text, RegistryOps<JsonElement> ops) {
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
                // 兼容处理：把「纯字符串写法的名字」先规范化成文本组件，再交给 codec（见方法注释）。
                DataResult<RawItem> result = RAW_ITEM_CODEC.parse(ops, normalizeNameComponents(itemElement));
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
                Optional<Candidate> candidate = resolve(file, categoryId, line, parsed.get(), ops, priority);
                if (candidate.isEmpty()) {
                    skipped++;
                    continue;
                }
                Candidate value = candidate.get();
                // 去重统一在全局注册表里做（跨文件、跨分类），见 DedupRegistry。
                candidates.add(value);
            }
            categories.add(new PendingCategory(categoryId, categoryName,
                    resolveIcon(readString(categoryObject, "icon")), priority, candidates));
        }
        return new FileParse(List.copyOf(categories), skipped);
    }

    /**
     * 名字组件的兼容规范化。
     *
     * <p>原版把 {@code minecraft:custom_name} / {@code minecraft:item_name} / {@code minecraft:lore}
     * 定义在 {@code ComponentSerialization.FLAT_CODEC} 上：它的值是<b>字符串</b>，
     * 而且这个字符串会<b>再被当成一个 JSON 文档解析一次</b>
     * （{@code JsonParser.parseString}，lenient 模式）。于是：
     * <ul>
     *   <li>{@code "custom_name": "深索纪念胸甲"} 能用 —— 裸文本在 lenient 模式下会被当作纯文本字面量；</li>
     *   <li>{@code "custom_name": "压力测试 #1"} <b>会失败</b> —— {@code #}（以及 {@code = / ; { } [ ]} 等）
     *       是 Gson 的非法字面量字符，直接抛 {@code MalformedJsonException}，
     *       用户看到的现象是「名字里带个 # 整条商品被跳过」（本机实测复现）；</li>
     *   <li>{@code "custom_name": {"text": "..."}} <b>也会失败</b> —— FLAT_CODEC 要求字符串，
     *       对象会报 "Not a string"；对用户来说「最直观的写法反而不被接受」。</li>
     * </ul>
     *
     * <p>这里在解析前统一处理成「一定能用」的形态：
     * <ul>
     *   <li>值是字符串且能被解析成 JSON 文档 → 原样保留；</li>
     *   <li>值是字符串但不能被解析（含 {@code #} 等）→ 包装成 {@code {"text": "..."}} 的 JSON 文档字符串；</li>
     *   <li>值直接是对象 / 数组 → 序列化成 JSON 文档字符串。</li>
     * </ul>
     * 结果是三种写法（裸文本 / JSON 文本组件字符串 / 组件对象）都能用，名字里出现任何字符都不会莫名失败；
     * 只有数字、布尔这类根本不是组件的值才原样丢给 codec 去报错。
     */
    private static JsonElement normalizeNameComponents(JsonElement element) {
        if (!element.isJsonObject()) {
            return element;
        }
        JsonObject item = element.getAsJsonObject();
        if (!(item.get("components") instanceof JsonObject components)) {
            return element;
        }
        JsonObject normalized = null;
        for (String key : NAME_COMPONENT_KEYS) {
            String document = toFlatComponentDocument(components.get(key));
            if (document == null) {
                continue;
            }
            if (normalized == null) {
                normalized = components.deepCopy();
            }
            normalized.addProperty(key, document);
        }
        if (components.get(LORE_COMPONENT_KEY) instanceof JsonArray lines) {
            JsonArray rewritten = new JsonArray(lines.size());
            boolean changed = false;
            for (JsonElement line : lines) {
                String document = toFlatComponentDocument(line);
                if (document == null) {
                    rewritten.add(line);
                } else {
                    rewritten.add(document);
                    changed = true;
                }
            }
            if (changed) {
                if (normalized == null) {
                    normalized = components.deepCopy();
                }
                normalized.add(LORE_COMPONENT_KEY, rewritten);
            }
        }
        if (normalized == null) {
            return element;
        }
        JsonObject newItem = item.deepCopy();
        newItem.add("components", normalized);
        return newItem;
    }

    /** 返回需要替换成的 JSON 文档字符串；{@code null} 表示这个值已经能用、不需要改。 */
    private static String toFlatComponentDocument(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String raw = value.getAsString();
            return isJsonDocument(raw) ? null : textComponentDocument(raw);
        }
        if (value.isJsonObject() || value.isJsonArray()) {
            return COMPACT_GSON.toJson(value);
        }
        return null;
    }

    /** 这个字符串能否被 Gson（lenient，与原版 FLAT_CODEC 完全同样的调用）当成 JSON 文档解析。 */
    private static boolean isJsonDocument(String raw) {
        try {
            JsonParser.parseString(raw);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 把任意文本包成文本组件 {@code {"text": "..."}} 的 JSON 文档字符串。 */
    private static String textComponentDocument(String text) {
        JsonObject component = new JsonObject();
        component.addProperty("text", text);
        return COMPACT_GSON.toJson(component);
    }

    /** 把一条原始配置解析成候选商品；任何不合法之处都只跳过这一条并明确报错。 */
    private static Optional<Candidate> resolve(Path file, String categoryId, int line, RawItem raw,
                                               RegistryOps<JsonElement> ops, int priority) {
        String where = file.getFileName() + " 分类 " + categoryId + " 第 " + line + " 条";

        if (!raw.deprecatedDisplayName().isBlank()) {
            logEntry(false, "{} 使用了已移除的 display_name 字段（{}），该字段已被忽略："
                            + "物品名现在由游戏内置本地化提供（跟随客户端语言），要自定义名字请用 "
                            + "components 里的 minecraft:custom_name。",
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
        if (raw.price() < 1) {
            logEntry(true, "{} 的 price 必须 >= 1（当前 {}），已跳过", where, raw.price());
            return Optional.empty();
        }

        int sellPrice = Math.max(0, raw.sellPrice());
        if (sellPrice > raw.price()) {
            // 卖价高于买价 = 无限刷钱漏洞，直接压回买价并警告，而不是默默放行。
            logEntry(false, "{} 的 sell_price({}) 高于 price({})，会形成刷钱漏洞，已按 price 处理",
                    where, sellPrice, raw.price());
            sellPrice = raw.price();
        }

        int stock = raw.stock();
        if (stock < ShopItemEntry.UNLIMITED) {
            logEntry(false, "{} 的 stock({}) 非法，已按不限量处理", where, stock);
            stock = ShopItemEntry.UNLIMITED;
        }

        int maxTrade = Math.max(1, Math.min(64, raw.maxStack()));
        if (raw.maxStack() != maxTrade) {
            logEntry(false, "{} 的 max_stack({}) 超出 [1,64]，已按 {} 处理", where, raw.maxStack(), maxTrade);
        }

        try {
            ItemStack template = new ItemStack(item);
            if (!raw.components().isEmpty()) {
                // applyComponents 只写入列出的组件，其余（含物品自带默认组件）保持原样。
                template.applyComponents(raw.components());
            }
            String dedupKey = itemId + COMPONENT_KEY_SEPARATOR
                    + (raw.variant().isBlank() ? canonicalComponents(raw.components(), ops) : raw.variant().trim());
            return Optional.of(new Candidate(dedupKey, template, raw.price(), sellPrice, stock, maxTrade,
                    !raw.components().isEmpty(), priority, where));
        } catch (RuntimeException e) {
            logEntry(true, "{} 的 components 无法应用到 {}（附魔/属性可能不合法）｜{}", where, itemId, e.toString());
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
            PendingCategory existing = categories.get(category.id);
            if (existing == null) {
                // 新分类：先建一个空的归属容器挂进总表，再由注册表逐条决定去留
                //（可能出现「这条已经被别的分类占用了」的情况）。
                existing = new PendingCategory(category.id, category.displayName, category.icon,
                        category.priority, List.of());
                categories.put(category.id, existing);
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

    private record RawItem(String item, String deprecatedDisplayName, String variant, int price, int sellPrice,
                           int stock, int maxStack, DataComponentPatch components) {
    }

    /** 解析出来、但还没分配最终序号的商品。 */
    private record Candidate(String dedupKey, ItemStack template, int price, int sellPrice, int stock,
                             int maxTrade, boolean customComponents, int priority, String source) {
    }

    /** 合并中的分类（可变：需要在多文件之间累积条目）。 */
    private static final class PendingCategory {
        private final String id;
        private final int priority;
        private final List<Candidate> items;
        private String displayName;
        private ItemStack icon;

        private PendingCategory(String id, String displayName, ItemStack icon, int priority, List<Candidate> items) {
            this.id = id;
            this.displayName = displayName;
            this.icon = icon;
            this.priority = priority;
            // 解析阶段的条目先装在一个独立可变列表里，接受与否由 DedupRegistry 决定。
            this.items = new ArrayList<>(items);
        }
    }
}
