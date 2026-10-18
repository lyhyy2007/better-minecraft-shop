package com.lyhyy2007.bmcs.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 游戏内「新建商品」的落盘实现（服务端调用，单线程串行执行）。
 *
 * <p>写盘策略：
 * <ul>
 *   <li><b>写进选中的分类</b>：商品追加到玩家当前选中的、游戏内新建的分类所在文件
 *       （一个分类一个文件）；手写分类不可写入；</li>
 *   <li><b>读-改-写</b>：读整份文件 → 在该分类的 {@code items} 末尾追加一条 → 原子替换整份文件
 *       （实现见 {@link CategoryFileIO}：临时文件 + {@code ATOMIC_MOVE} + {@code .bak}）；</li>
 *   <li><b>校验</b>：价格 / 库存范围、组件白名单、同物品同组件重复检测、分类必须带
 *       {@code editable:true}；文件不是合法 JSON 时拒绝写入；</li>
 *   <li><b>不静默</b>：失败原因以 i18n key 返回，由 {@code ShopItemEditorService} 回给玩家。</li>
 * </ul>
 *
 * <p>写入的字段严格按目录区分：{@code in/} 写 {@code price} + {@code stock}（+ 可选 {@code components}），
 * {@code out/} 写 {@code sell_price}（+ 可选 {@code components}），不会把两种字段混进同一份文件。
 */
public final class ShopItemWriter {
    /** 价格下限。 */
    public static final int MIN_PRICE = 1;
    /** 价格上限（防手滑写出天文数字）。 */
    public static final int MAX_PRICE = 1_000_000_000;
    /** 库存上限（-1 表示不限量）。 */
    public static final int MAX_STOCK = 1_000_000;

    /** {@code components} 白名单：与加载器一致，只支持附魔。 */
    private static final List<String> ALLOWED_COMPONENTS =
            List.of("minecraft:enchantments", "minecraft:stored_enchantments");
    private static final Object WRITE_LOCK = new Object();

    private ShopItemWriter() {
    }

    /** 保存结果类型。 */
    public enum Status {
        /** 已落盘。 */
        OK,
        /** 输入不合法 / 冲突 / 现有文件坏了：可以修正后重试。 */
        REJECTED,
        /** 磁盘 IO 失败：重试可能也没用。 */
        FAILED
    }

    /**
     * 保存结果。
     *
     * @param status     结果类型
     * @param messageKey 不带 {@code message.modid.} 前缀的 i18n key（例如 {@code editor.saved}）
     * @param args       翻译占位符参数
     * @param file       成功时写入的文件
     */
    public record Result(Status status, String messageKey, Object[] args, Path file) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    /**
     * 把背包里的一件物品追加进「当前选中的分类」。
     *
     * @param dir        目标目录（{@code in} 或 {@code out}）
     * @param side       交易方向
     * @param categoryId 目标分类 id（必须存在且带 {@code editable:true}）
     * @param stack      玩家背包里的物品（只读取，不修改）
     * @param price      买入价（BUY）或回收价（SELL）
     * @param stock      库存；只有 BUY 有效，{@code -1} = 不限量
     * @param registries 服务端注册表（附魔组件编码需要）
     */
    public static Result save(Path dir, ShopSide side, String categoryId, ItemStack stack, int price, int stock,
                              HolderLookup.Provider registries) {
        synchronized (WRITE_LOCK) {
            return saveLocked(dir, side, categoryId, stack, price, stock, registries);
        }
    }

    private static Result saveLocked(Path dir, ShopSide side, String categoryId, ItemStack stack, int price,
                                     int stock, HolderLookup.Provider registries) {
        if (dir == null) {
            return failed("editor.io_failed", "null");
        }
        if (categoryId == null || categoryId.isBlank()) {
            return rejected("editor.need_category");
        }
        if (stack == null || stack.isEmpty()) {
            return rejected("editor.empty");
        }
        if (price < MIN_PRICE || price > MAX_PRICE) {
            return rejected("editor.price_range", MIN_PRICE, MAX_PRICE);
        }
        if (side == ShopSide.BUY && stock != ShopItemEntry.UNLIMITED && (stock < 0 || stock > MAX_STOCK)) {
            return rejected("editor.stock_range", ShopItemEntry.UNLIMITED, MAX_STOCK);
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) {
            return rejected("editor.unknown_item");
        }
        DataComponentPatch patch = stack.getComponentsPatch();
        if (!patch.isEmpty()) {
            for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
                ResourceLocation key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
                if (key == null || !ALLOWED_COMPONENTS.contains(key.toString())) {
                    return rejected("editor.components", key == null ? "?" : key.toString());
                }
            }
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        JsonElement componentsElement = null;
        if (!patch.isEmpty()) {
            componentsElement = DataComponentPatch.CODEC.encodeStart(ops, patch).result().orElse(null);
            if (componentsElement == null || !componentsElement.isJsonObject()) {
                return rejected("editor.components", patch.toString());
            }
        }

        CategoryFileIO.Found found;
        try {
            found = CategoryFileIO.find(dir, categoryId);
        } catch (IOException e) {
            return rejected("editor.corrupt", dir.toAbsolutePath().toString());
        }
        if (found == null) {
            return rejected("editor.category_missing", categoryId);
        }
        if (!ShopCategoryWriter.isEditable(found.category())) {
            return rejected("editor.category_locked", categoryId);
        }

        JsonArray items;
        JsonElement itemsElement = found.category().get("items");
        if (itemsElement != null && itemsElement.isJsonArray()) {
            items = itemsElement.getAsJsonArray();
        } else {
            items = new JsonArray();
            found.category().add("items", items);
        }
        if (CategoryFileIO.readString(found.category(), "icon").isBlank()) {
            found.category().addProperty("icon", itemId.toString());
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("item", itemId.toString());
        if (side == ShopSide.BUY) {
            entry.addProperty("price", price);
            entry.addProperty("stock", stock);
        } else {
            entry.addProperty("sell_price", price);
        }
        if (componentsElement != null) {
            entry.add("components", componentsElement);
        }

        String wantedComponents = CategoryFileIO.normalizeComponents(entry.get("components"));
        for (JsonElement element : items) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject existing = element.getAsJsonObject();
            if (!itemId.toString().equals(CategoryFileIO.readString(existing, "item").trim())) {
                continue;
            }
            if (CategoryFileIO.normalizeComponents(existing.get("components")).equals(wantedComponents)) {
                return rejected("editor.exists", itemId.toString(), side == ShopSide.BUY ? "in" : "out");
            }
        }
        items.add(entry);
        found.root().addProperty("version", ShopConfigLoader.FORMAT_VERSION);

        try {
            CategoryFileIO.write(found.file(), found.root());
        } catch (IOException e) {
            return failed("editor.io_failed", e.toString());
        }
        return new Result(Status.OK, "editor.saved",
                new Object[]{found.file().getFileName().toString(), categoryId}, found.file());
    }

    private static Result rejected(String key, Object... args) {
        return new Result(Status.REJECTED, key, args, null);
    }

    private static Result failed(String key, Object... args) {
        return new Result(Status.FAILED, key, args, null);
    }
}
