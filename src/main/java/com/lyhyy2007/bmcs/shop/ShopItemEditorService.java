package com.lyhyy2007.bmcs.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.config.ShopCategoryWriter;
import com.lyhyy2007.bmcs.config.ShopConfig;
import com.lyhyy2007.bmcs.config.ShopItemEntry;
import com.lyhyy2007.bmcs.config.ShopItemWriter;
import com.lyhyy2007.bmcs.config.ShopSide;
import com.lyhyy2007.bmcs.network.ShopCategoryCreatePayload;
import com.lyhyy2007.bmcs.network.ShopCategoryUpdatePayload;
import com.lyhyy2007.bmcs.network.ShopEditorResultPayload;
import com.lyhyy2007.bmcs.network.ShopEditorSavePayload;
import com.lyhyy2007.bmcs.network.ShopItemDeletePayload;
import com.lyhyy2007.bmcs.util.ItemStackUtil;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端编辑入口：新建分类 / 改名换图标 / 新建商品 / 删除商品。
 *
 * <p>统一流程：权限 → 目录版本 → 业务校验 → 落盘（{@code ShopCategoryWriter} /
 * {@code ShopItemWriter}）→ 重载配置并重新打开商店，客户端拿到成功包后自动选中新分类。
 *
 * <p>权限：单人存档（集成服务器）任何人可用；专用服务器只允许 OP（权限等级
 * {@value #MIN_PERMISSION_LEVEL}）。普通玩家的请求一律拒绝，且不会触碰磁盘；
 * 客户端也只在 {@code canEdit} 为 true 时显示这些按钮，两边用同一套判断。
 */
public final class ShopItemEditorService {
    /** 专用服务器需要的权限等级：2 = OP / 命令方块级别。 */
    public static final int MIN_PERMISSION_LEVEL = 2;
    private static final String KEY = "message." + BetterMinecraftShop.MOD_ID + ".";

    private ShopItemEditorService() {
    }

    /** 编辑权限：单人存档随意编辑；专用服务器仍需 OP。 */
    public static boolean canEdit(ServerPlayer player) {
        var server = player.getServer();
        if (server != null && server.isSingleplayer()) {
            return true;
        }
        return player.hasPermissions(MIN_PERMISSION_LEVEL);
    }

    // ------------------------------------------------------------------
    // 新建分类
    // ------------------------------------------------------------------

    public static void handleCategoryCreate(ServerPlayer player, ShopCategoryCreatePayload payload) {
        if (!checkEditable(player, payload.revision())) {
            return;
        }
        Path dir = dirOf(payload.side());
        ShopCategoryWriter.Result result = ShopCategoryWriter.create(dir, payload.side(), payload.name(),
                payload.iconId());
        if (result.ok()) {
            String newId = firstCategoryId(result.file());
            reply(player, true, KEY + result.messageKey(), result.args(), newId);
            reloadOrComplain(player);
        } else {
            reply(player, false, KEY + result.messageKey(), result.args(), "");
        }
    }

    // ------------------------------------------------------------------
    // 改名 / 换图标
    // ------------------------------------------------------------------

    public static void handleCategoryUpdate(ServerPlayer player, ShopCategoryUpdatePayload payload) {
        if (!checkEditable(player, payload.revision())) {
            return;
        }
        Path dir = dirOf(payload.side());
        ShopCategoryWriter.Result result = ShopCategoryWriter.update(dir, payload.categoryId(), payload.name(),
                payload.iconId());
        if (result.ok()) {
            reply(player, true, KEY + result.messageKey(), result.args(), payload.categoryId());
            reloadOrComplain(player);
        } else {
            reply(player, false, KEY + result.messageKey(), result.args(), "");
        }
    }

    // ------------------------------------------------------------------
    // 删除商品
    // ------------------------------------------------------------------

    public static void handleItemDelete(ServerPlayer player, ShopItemDeletePayload payload) {
        if (!checkEditable(player, payload.revision())) {
            return;
        }
        Optional<ShopItemEntry> found = ShopConfig.get().byId(payload.entryId());
        if (found.isEmpty()) {
            reply(player, false, KEY + "editor.stale", new Object[0], "");
            ShopManager.get().refresh(player);
            return;
        }
        ShopItemEntry entry = found.get();
        if (entry.side() != payload.side()) {
            reply(player, false, KEY + "editor.stale", new Object[0], "");
            ShopManager.get().refresh(player);
            return;
        }
        if (!entry.categoryId().equalsIgnoreCase(payload.categoryId())) {
            reply(player, false, KEY + "editor.stale", new Object[0], "");
            ShopManager.get().refresh(player);
            return;
        }
        String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(entry.template().getItem()));
        String components = componentsSignature(entry.template(), player);
        Path dir = dirOf(payload.side());
        ShopCategoryWriter.Result result = ShopCategoryWriter.deleteItem(dir, payload.side(), payload.categoryId(),
                itemId, components);
        if (result.ok()) {
            reply(player, true, KEY + result.messageKey(), result.args(), payload.categoryId());
            reloadOrComplain(player);
        } else {
            reply(player, false, KEY + result.messageKey(), result.args(), "");
        }
    }

    // ------------------------------------------------------------------
    // 新建商品
    // ------------------------------------------------------------------

    public static void handle(ServerPlayer player, ShopEditorSavePayload payload) {
        if (!checkEditable(player, payload.revision())) {
            return;
        }
        ItemStack stack;
        if (payload.slot() < 0) {
            // 从「全部物品」列表里挑的：按 id 构造默认形态，不读背包，也就没有「物品被换掉」的问题。
            ResourceLocation id = ResourceLocation.tryParse(payload.itemId());
            Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                reply(player, false, KEY + "editor.unknown_item", new Object[]{payload.itemId()}, "");
                return;
            }
            stack = new ItemStack(item);
        } else {
            if (!ItemStackUtil.isTradeableSlot(player, payload.slot())) {
                reply(player, false, KEY + "editor.bad_slot", new Object[0], "");
                return;
            }
            stack = player.getInventory().getItem(payload.slot());
            if (stack.isEmpty()) {
                reply(player, false, KEY + "editor.empty", new Object[0], "");
                return;
            }
            String actualId = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (!actualId.equals(payload.itemId())) {
                // 玩家在发送期间换了手里的东西：拒绝，而不是把另一件物品存进商店。
                reply(player, false, KEY + "editor.changed", new Object[0], "");
                return;
            }
        }

        Path dir = dirOf(payload.side());
        ShopItemWriter.Result result = ShopItemWriter.save(dir, payload.side(), payload.categoryId(), stack,
                payload.price(), payload.stock(), player.server.registryAccess());
        if (result.ok()) {
            // 先回成功，再重载并重新打开商店：客户端拿到成功包就先关掉编辑器，
            // 随后到达的商店数据直接打开商店界面（不会闪一下桌面）。
            reply(player, true, KEY + result.messageKey(), result.args(), payload.categoryId());
            reloadOrComplain(player);
            return;
        }
        reply(player, false, KEY + result.messageKey(), result.args(), "");
    }

    // ------------------------------------------------------------------
    // 共用
    // ------------------------------------------------------------------

    /** 权限 + 目录版本校验；不通过时已经回过玩家了。 */
    private static boolean checkEditable(ServerPlayer player, int revision) {
        if (!canEdit(player)) {
            reply(player, false, KEY + "editor.no_permission", new Object[0], "");
            return false;
        }
        if (revision != ShopConfig.get().revision()) {
            // 目录已经变了：让玩家看一眼新列表再改，避免写进「已经改过的分类」。
            reply(player, false, KEY + "editor.stale", new Object[0], "");
            ShopManager.get().refresh(player);
            return false;
        }
        return true;
    }

    private static Path dirOf(ShopSide side) {
        return side.sells() ? ShopConfig.get().sellDir() : ShopConfig.get().buyDir();
    }

    private static void reloadOrComplain(ServerPlayer player) {
        if (!ShopManager.get().reloadFromEditor(player)) {
            reply(player, false, KEY + "editor.reload_failed", new Object[0], "");
        }
    }

    /** 组件签名：与加载器判重用的格式一致（顶层键排序后的 JSON）。 */
    private static String componentsSignature(ItemStack stack, ServerPlayer player) {
        DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.isEmpty()) {
            return "";
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, player.server.registryAccess());
        JsonElement encoded = DataComponentPatch.CODEC.encodeStart(ops, patch).result().orElse(null);
        if (encoded == null || !encoded.isJsonObject()) {
            return "";
        }
        return ShopCategoryWriter.componentsSignature(encoded);
    }

    /** 读取刚写出的文件里第一个分类的 id。 */
    private static String firstCategoryId(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            JsonArray categories = root.getAsJsonObject().getAsJsonArray("categories");
            if (categories != null && !categories.isEmpty() && categories.get(0).isJsonObject()) {
                JsonObject category = categories.get(0).getAsJsonObject();
                JsonElement id = category.get("id");
                return id == null || id.isJsonNull() ? "" : id.getAsString();
            }
        } catch (IOException | RuntimeException ignored) {
            // 取不到 id 只是「不自动选中分类」，不影响已经写成功的文件。
        }
        return "";
    }

    private static void reply(ServerPlayer player, boolean success, String key, Object[] args, String categoryId) {
        Component message = Component.translatable(key, args == null ? new Object[0] : args);
        PacketDistributor.sendToPlayer(player, new ShopEditorResultPayload(success, message, categoryId));
        if (!success) {
            // 编辑器被关掉 / 界面被替换时，原因仍然能在聊天栏看到。
            player.sendSystemMessage(message);
        }
    }
}
