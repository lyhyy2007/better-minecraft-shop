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
 * 客户端 → 服务端：编辑模式里「改名 / 换图标」。
 *
 * <p>改名的同时会把文件改名（文件名 = 商店名），所以只对「一个文件一个分类」且带
 * {@code editable:true} 的分类生效。
 *
 * @param side       交易方向
 * @param categoryId 被编辑的分类 id
 * @param name       新名字（留空则保持 {@code _N} 文件名）
 * @param iconId     新图标物品 id
 * @param revision   客户端看到的目录版本号
 */
public record ShopCategoryUpdatePayload(ShopSide side, String categoryId, String name, String iconId, int revision)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ShopCategoryUpdatePayload> TYPE =
            new CustomPacketPayload.Type<>(BetterMinecraftShop.id("shop_category_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopCategoryUpdatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.side());
                buffer.writeUtf(payload.categoryId(), 64);
                buffer.writeUtf(payload.name() == null ? "" : payload.name(), 64);
                buffer.writeUtf(payload.iconId() == null ? "" : payload.iconId(), 128);
                buffer.writeVarInt(payload.revision());
            },
            buffer -> new ShopCategoryUpdatePayload(buffer.readEnum(ShopSide.class), buffer.readUtf(64),
                    buffer.readUtf(64), buffer.readUtf(128), buffer.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopCategoryUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ShopItemEditorService.handleCategoryUpdate(player, payload);
            }
        });
    }
}
