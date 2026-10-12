package com.lyhyy2007.bmcs.network;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.shop.ShopManager;
import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：买入 / 卖出。
 *
 * <p>寻址方式刻意不用「列表下标」：SDMShop 用 {@code indexOf(tovar)} 定位商品，
 * 客户端与服务端列表只要不同步（刷新中、被过滤、恶意构造），下标就会指到别的商品，
 * 而且 {@code index = -1} 会直接把服务端 tick 线程打崩。这里用稳定的商品 UUID，
 * 并附带目录版本号，版本不符就直接拒绝并让客户端刷新。
 *
 * @param action   动作
 * @param entryId  商品 UUID（由配置键派生，跨重启稳定）
 * @param quantity 交易件数；服务端会再校验一次范围
 * @param revision 客户端看到的目录版本号
 */
public record ShopActionPayload(Action action, UUID entryId, int quantity, int revision)
        implements CustomPacketPayload {
    /** 单次请求的硬上限，用于限制恶意的超大数值（服务端还会按商品再收紧）。 */
    public static final int HARD_LIMIT = 4096;

    public static final CustomPacketPayload.Type<ShopActionPayload> TYPE =
            new CustomPacketPayload.Type<>(BetterMinecraftShop.id("shop_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopActionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.action());
                UUIDUtil.STREAM_CODEC.encode(buffer, payload.entryId());
                buffer.writeVarInt(payload.quantity());
                buffer.writeVarInt(payload.revision());
            },
            buffer -> {
                Action action = buffer.readEnum(Action.class);
                UUID entryId = UUIDUtil.STREAM_CODEC.decode(buffer);
                int quantity = buffer.readVarInt();
                int revision = buffer.readVarInt();
                if (quantity < 0 || quantity > HARD_LIMIT) {
                    throw new DecoderException("bmcs: quantity out of range: " + quantity);
                }
                return new ShopActionPayload(action, entryId, quantity, revision);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ShopManager.get().handleAction(player, payload);
            }
        });
    }

    /** 交易动作。 */
    public enum Action {
        BUY,
        SELL
    }
}
