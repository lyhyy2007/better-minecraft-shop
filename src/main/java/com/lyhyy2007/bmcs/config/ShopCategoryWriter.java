package com.lyhyy2007.bmcs.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 游戏内「新建分类 / 改名换图标 / 删除商品」的落盘实现（服务端调用，串行执行）。
 *
 * <p>规则：
 * <ul>
 *   <li><b>一个分类一个文件</b>：新建分类写成 {@code <商店名>.json}；名字留空时写成
 *       {@code _0.json}、{@code _1.json}……（文件名不能是空串）；</li>
 *   <li><b>只动自己的文件</b>：手写的多分类文件照旧能加载，但游戏内改名只在
 *       「一个文件恰好一个分类」时执行，图标 / 删商品则可以在原文件里就地改；</li>
 *   <li><b>可编辑标记</b>：写盘时带 {@code "editable": true}，只有带这个标记的分类
 *       才允许游戏内继续编辑（手写分类不会被误改）；</li>
 *   <li><b>原子写入 + 备份</b>：见 {@link CategoryFileIO}；</li>
 *   <li><b>失败即原文件不动</b>：校验在读盘前完成，写盘失败时旧文件保持原样。</li>
 * </ul>
 */
public final class ShopCategoryWriter {
    /** 商店名长度上限（同时限制文件名长度）。 */
    public static final int MAX_NAME_LENGTH = 32;
    /** 名称非法字符（Windows / 跨平台文件名都不允许）。 */
    private static final String ILLEGAL_CHARS = "\\/:*?\"<>|";
    /** 名字留空时的文件名前缀。 */
    public static final String UNNAMED_PREFIX = "_";
    private static final String DEFAULT_ICON = "minecraft:chest";
    private static final Object LOCK = new Object();

    private ShopCategoryWriter() {
    }

    public enum Status {
        OK,
        REJECTED,
        FAILED
    }

    /**
     * 写盘结果。
     *
     * @param messageKey 不带 {@code message.modid.} 前缀的 i18n key
     */
    public record Result(Status status, String messageKey, Object[] args, Path file) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    /** 新建分类：名字 + 图标（图标为空时用箱子兜底）。 */
    public static Result create(Path dir, ShopSide side, String rawName, String iconId) {
        synchronized (LOCK) {
            String name = rawName == null ? "" : rawName.trim();
            Result invalid = checkName(name);
            if (invalid != null) {
                return invalid;
            }
            ItemStack icon = resolveIcon(iconId);
            if (icon.isEmpty()) {
                return rejected("category.icon_invalid", iconId == null ? "" : iconId);
            }
            String base = name.isEmpty() ? nextUnnamedBase(dir) : name;
            if (existsIgnoreCase(dir, base)) {
                return rejected("category.exists", base);
            }
            String id = nextCategoryId(dir);
            Path file = dir.resolve(base + ".json");

            JsonObject category = new JsonObject();
            category.addProperty("id", id);
            category.addProperty("display_name", name);
            category.addProperty("icon", String.valueOf(BuiltInRegistries.ITEM.getKey(icon.getItem())));
            category.addProperty("editable", true);
            category.add("items", new JsonArray());

            JsonArray categories = new JsonArray();
            categories.add(category);
            JsonObject root = new JsonObject();
            root.addProperty("version", ShopConfigLoader.FORMAT_VERSION);
            root.add("categories", categories);
            try {
                CategoryFileIO.write(file, root);
            } catch (IOException e) {
                return failed("category.io_failed", file.toAbsolutePath().toString());
            }
            return new Result(Status.OK, "category.created", new Object[]{label(name, base)}, file);
        }
    }

    /**
     * 改名 / 换图标。
     *
     * <p>改名同时会改文件名（需求：文件名 = 商店名）；图标可以单独改。
     */
    public static Result update(Path dir, String categoryId, String rawName, String iconId) {
        synchronized (LOCK) {
            String name = rawName == null ? "" : rawName.trim();
            Result invalid = checkName(name);
            if (invalid != null) {
                return invalid;
            }
            CategoryFileIO.Found found;
            try {
                found = CategoryFileIO.find(dir, categoryId);
            } catch (IOException e) {
                return failed("category.corrupt_file", categoryId);
            }
            if (found == null) {
                return rejected("category.not_found", categoryId);
            }
            if (!isEditable(found.category())) {
                return rejected("category.not_editable", categoryId);
            }

            ItemStack icon = resolveIcon(iconId);
            if (icon.isEmpty()) {
                return rejected("category.icon_invalid", iconId == null ? "" : iconId);
            }

            String base = name.isEmpty() ? unnamedBaseOf(found.file()) : name;
            if (base == null) {
                base = nextUnnamedBase(dir);
            }
            boolean rename = !base.equals(baseNameOf(found.file()));
            Path target = rename ? dir.resolve(base + ".json") : found.file();
            if (rename && existsIgnoreCase(dir, base)) {
                return rejected("category.exists", base);
            }
            if (rename && categoryCount(found.root()) > 1) {
                // 多分类手写文件不能改名：改文件名会牵连同一文件里的其它分类。
                return rejected("category.shared_file", found.file().getFileName().toString());
            }

            found.category().addProperty("display_name", name);
            found.category().addProperty("icon", String.valueOf(BuiltInRegistries.ITEM.getKey(icon.getItem())));
            found.category().addProperty("editable", true);
            try {
                CategoryFileIO.write(target, found.root());
                if (rename && !target.equals(found.file())) {
                    Files.deleteIfExists(found.file());
                    Path parent = found.file().getParent();
                    if (parent != null) {
                        String oldName = found.file().getFileName().toString();
                        // 旧文件名的备份 / 临时文件一并清掉，别在配置目录里留下幽灵文件。
                        Files.deleteIfExists(parent.resolve(oldName + CategoryFileIO.BACKUP_SUFFIX));
                        Files.deleteIfExists(parent.resolve("." + oldName + ".tmp"));
                    }
                }
            } catch (IOException e) {
                return failed("category.io_failed", target.toAbsolutePath().toString());
            }
            return new Result(Status.OK, "category.updated", new Object[]{label(name, base), base}, target);
        }
    }

    /**
     * 删除分类里的一个商品。
     *
     * @param itemId       物品注册名
     * @param components   该商品的组件签名（{@link CategoryFileIO#normalizeComponents} 的结果）
     */
    public static Result deleteItem(Path dir, ShopSide side, String categoryId, String itemId, String components) {
        synchronized (LOCK) {
            CategoryFileIO.Found found;
            try {
                found = CategoryFileIO.find(dir, categoryId);
            } catch (IOException e) {
                return failed("category.corrupt_file", categoryId);
            }
            if (found == null) {
                return rejected("category.not_found", categoryId);
            }
            if (!isEditable(found.category())) {
                return rejected("category.not_editable", categoryId);
            }
            JsonElement itemsElement = found.category().get("items");
            if (itemsElement == null || !itemsElement.isJsonArray()) {
                return rejected("item.not_found", itemId);
            }
            JsonArray items = itemsElement.getAsJsonArray();
            String wanted = components == null ? "" : components;
            boolean removed = false;
            for (int i = items.size() - 1; i >= 0; i--) {
                JsonElement element = items.get(i);
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (!itemId.equals(CategoryFileIO.readString(entry, "item").trim())) {
                    continue;
                }
                if (!CategoryFileIO.normalizeComponents(entry.get("components")).equals(wanted)) {
                    continue;
                }
                items.remove(i);
                removed = true;
                break;
            }
            if (!removed) {
                return rejected("item.not_found", itemId);
            }
            try {
                CategoryFileIO.write(found.file(), found.root());
            } catch (IOException e) {
                return failed("category.io_failed", found.file().toAbsolutePath().toString());
            }
            return new Result(Status.OK, "item.deleted", new Object[]{itemId}, found.file());
        }
    }

    // ------------------------------------------------------------------
    // 校验与命名
    // ------------------------------------------------------------------

    /** 名称校验；合法返回 {@code null}。 */
    private static Result checkName(String name) {
        if (name.length() > MAX_NAME_LENGTH) {
            return rejected("category.name_length", MAX_NAME_LENGTH);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || ILLEGAL_CHARS.indexOf(c) >= 0) {
                return rejected("category.name_invalid", String.valueOf(c));
            }
        }
        if (name.equals(".") || name.equals("..") || name.endsWith(".")) {
            return rejected("category.name_invalid", name);
        }
        return null;
    }

    /** 展示用标签：留空时用文件名（{@code _0} 这类）。 */
    private static String label(String name, String base) {
        return name.isEmpty() ? base : name;
    }

    private static String baseNameOf(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    private static String unnamedBaseOf(Path file) {
        String base = baseNameOf(file);
        return base.startsWith(UNNAMED_PREFIX) ? base : null;
    }

    /** 目录里还没被占用的 {@code _N}。 */
    private static String nextUnnamedBase(Path dir) {
        for (int i = 0; i < 10_000; i++) {
            String candidate = UNNAMED_PREFIX + i;
            if (!existsIgnoreCase(dir, candidate)) {
                return candidate;
            }
        }
        return UNNAMED_PREFIX + System.nanoTime();
    }

    private static boolean existsIgnoreCase(Path dir, String base) {
        if (dir == null) {
            return false;
        }
        for (Path file : ShopConfigLoader.collectFiles(dir)) {
            if (baseNameOf(file).equalsIgnoreCase(base)) {
                return true;
            }
        }
        return false;
    }

    /** 下一个可用的分类 id：{@code cat_0}、{@code cat_1}……（跨文件查重）。 */
    static String nextCategoryId(Path dir) {
        int max = -1;
        if (dir == null) {
            return "cat_0";
        }
        for (Path file : ShopConfigLoader.collectFiles(dir)) {
            JsonObject root;
            try {
                root = CategoryFileIO.readObject(file);
            } catch (IOException e) {
                continue;
            }
            JsonElement categories = root.get("categories");
            if (categories == null || !categories.isJsonArray()) {
                continue;
            }
            for (JsonElement element : categories.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                String id = CategoryFileIO.readString(element.getAsJsonObject(), "id").trim().toLowerCase(Locale.ROOT);
                if (id.startsWith("cat_")) {
                    try {
                        max = Math.max(max, Integer.parseInt(id.substring(4)));
                    } catch (NumberFormatException ignored) {
                        // 不是自动编号的分类，忽略。
                    }
                }
            }
        }
        return "cat_" + (max + 1);
    }

    static boolean isEditable(JsonObject category) {
        JsonElement element = category.get("editable");
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean();
    }

    private static int categoryCount(JsonObject root) {
        JsonElement categories = root.get("categories");
        return categories != null && categories.isJsonArray() ? categories.getAsJsonArray().size() : 0;
    }

    /** 组件签名：与加载器判重用的格式一致（顶层键排序后的 JSON）。 */
    public static String componentsSignature(JsonElement element) {
        return CategoryFileIO.normalizeComponents(element);
    }

    /** 图标物品：必须是已注册、非空气的物品 id。 */
    public static ItemStack resolveIcon(String iconId) {        if (iconId == null || iconId.isBlank()) {
            return new ItemStack(Items.CHEST);
        }
        ResourceLocation id = ResourceLocation.tryParse(iconId.trim());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
        return stack.isEmpty() ? ItemStack.EMPTY : stack;
    }

    /** 默认图标 id（客户端新建分类时预填）。 */
    public static String defaultIconId() {
        return DEFAULT_ICON;
    }

    private static Result rejected(String key, Object... args) {
        return new Result(Status.REJECTED, key, args, null);
    }

    private static Result failed(String key, Object... args) {
        return new Result(Status.FAILED, key, args, null);
    }
}
