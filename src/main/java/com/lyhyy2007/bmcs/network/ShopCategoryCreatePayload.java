package com.lyhyy2007.bmcs.network;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.config.ShopSide;
import com.lyhyy2007.bmcs.shop.ShopItemEditorService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：游戏内「新建分类」。
 *
 * @param side     写到 in/ 还是 out/
 * @param name     商店名（也是文件名；留空则用 {@code _0}、{@code _1}……）
 * @param iconId   分类图标物品 id
 * @param revision 客户端看到的目录版本号
 */
public record ShopCategoryCreatePayload(ShopSide side, String name, String iconId, int revision)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ShopCategoryCreatePayload> TYPE =
            new CustomPacketPayload.Type<>(BetterMinecraftShop.id("shop_category_create"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopCategoryCreatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.side());
                buffer.writeUtf(payload.name() == null ? "" : payload.name(), 64);
                buffer.writeUtf(payload.iconId() == null ? "" : payload.iconId(), 128);
                buffer.writeVarInt(payload.revision());
            },
            buffer -> new ShopCategoryCreatePayload(buffer.readEnum(ShopSide.class), buffer.readUtf(64),
                    buffer.readUtf(128), buffer.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopCategoryCreatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ShopItemEditorService.handleCategoryCreate(player, payload);
            }
        });
    }
}
