package com.lyhyy2007.bmcs.network;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.client.ClientShopBridge;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：单条商品的增量补丁（交易后刷新）。
 *
 * <p>为什么需要它：SDMShop 每完成一笔交易就把整个目录向所有在线玩家全量重推一遍。
 * 在本模组支持 10000+ 条目的前提下，「全量重推」意味着每次点击买卖都要传几百 KB ——
 * 既浪费带宽又会造成可见的卡顿。这里只传「谁的库存变成多少 + 我的余额」，
 * 客户端就地替换快照里的这一条，界面刷新成本与目录规模无关。
 *
 * <p>{@code remaining} 为负表示不限量；{@code revision} 与客户端快照不一致时客户端会忽略该补丁
 * （说明目录已经变化，服务端会在下一次请求时推全量）。
 */
public record ShopPatchPayload(int revision, long balance, UUID entryId, int remaining)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShopPatchPayload> TYPE =
            new CustomPacketPayload.Type<>(BetterMinecraftShop.id("shop_patch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopPatchPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.revision());
                buffer.writeVarLong(payload.balance());
                UUIDUtil.STREAM_CODEC.encode(buffer, payload.entryId());
                buffer.writeVarInt(payload.remaining());
            },
            buffer -> new ShopPatchPayload(buffer.readVarInt(), buffer.readVarLong(),
                    UUIDUtil.STREAM_CODEC.decode(buffer), buffer.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopPatchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientShopBridge.onShopPatch(payload));
    }
}
