package com.lyhyy2007.bmcs.network;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.config.ShopSide;
import com.lyhyy2007.bmcs.shop.ShopItemEditorService;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：删除分类里的一个商品（客户端会先弹二次确认）。
 *
 * @param side       交易方向
 * @param categoryId 商品所在分类 id
 * @param entryId    商品条目的稳定 id（与商店列表里的一致）
 * @param revision   客户端看到的目录版本号
 */
public record ShopItemDeletePayload(ShopSide side, String categoryId, UUID entryId, int revision)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ShopItemDeletePayload> TYPE =
            new CustomPacketPayload.Type<>(BetterMinecraftShop.id("shop_item_delete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopItemDeletePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.side());
                buffer.writeUtf(payload.categoryId(), 64);
                buffer.writeUUID(payload.entryId());
                buffer.writeVarInt(payload.revision());
            },
            buffer -> new ShopItemDeletePayload(buffer.readEnum(ShopSide.class), buffer.readUtf(64),
                    buffer.readUUID(), buffer.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopItemDeletePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ShopItemEditorService.handleItemDelete(player, payload);
            }
        });
    }
}
