package com.lyhyy2007.bmcs.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 分类文件的读-改-写底座（包内共用，服务端主线程调用）。
 *
 * <p>约定（见 {@code docs/技术说明.md}）：游戏内新建的<b>一个分类对应一个 JSON 文件</b>，
 * 文件里只有一个分类。手写的多分类文件照旧能被加载，但游戏内编辑只会落在
 * 单个分类的文件上，不会把别人的文件拆开或合并。
 *
 * <p>写盘策略与商品编辑器一致：临时文件 + {@code ATOMIC_MOVE}，替换前留一份 {@code .bak}。
 */
final class CategoryFileIO {
    static final String BACKUP_SUFFIX = ".bak";
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private CategoryFileIO() {
    }

    /** 找到包含指定分类 id 的文件；找不到返回 {@code null}。 */
    static Found find(Path dir, String categoryId) throws IOException {
        if (dir == null) {
            return null;
        }
        for (Path file : ShopConfigLoader.collectFiles(dir)) {
            JsonObject root = readObject(file);
            JsonElement categories = root.get("categories");
            if (categories == null || !categories.isJsonArray()) {
                continue;
            }
            for (JsonElement element : categories.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject category = element.getAsJsonObject();
                if (categoryId.equalsIgnoreCase(readString(category, "id").trim())) {
                    return new Found(file, root, category);
                }
            }
        }
        return null;
    }

    /** 读成一个 JSON 对象；空文件 / 坏 JSON / 非对象都抛 {@link IOException}。 */
    static JsonObject readObject(Path file) throws IOException {
        long size = Files.size(file);
        if (size > ShopConfigLoader.MAX_FILE_BYTES) {
            throw new IOException("file too large: " + size);
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        JsonElement element = JsonParser.parseString(text);
        if (!element.isJsonObject()) {
            throw new IOException("not a json object");
        }
        return element.getAsJsonObject();
    }

    /** 原子写入（临时文件 + 备份 + 替换）。 */
    static void write(Path file, JsonObject root) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = parent == null ? Path.of("." + file.getFileName() + ".tmp")
                : parent.resolve("." + file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        if (Files.isRegularFile(file)) {
            Path backup = parent == null ? Path.of(file.getFileName() + BACKUP_SUFFIX)
                    : parent.resolve(file.getFileName() + BACKUP_SUFFIX);
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 网络盘不支持原子移动：退化成普通替换，备份已经先写好。
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String readString(JsonObject object, String field) {
        JsonElement element = object == null ? null : object.get(field);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    /** 组件签名：顶层键排序后的 JSON；没有组件时为空串（于是这类条目严格按 item 判重）。 */
    static String normalizeComponents(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return "";
        }
        java.util.TreeMap<String, JsonElement> sorted = new java.util.TreeMap<>();
        element.getAsJsonObject().entrySet().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return GSON.toJson(sorted);
    }

    /** 命中的文件 + 整份 JSON + 该分类节点（都是可变的 Gson 对象）。 */
    record Found(Path file, JsonObject root, JsonObject category) {
    }
}
