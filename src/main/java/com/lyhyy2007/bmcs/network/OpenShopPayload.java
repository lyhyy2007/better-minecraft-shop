package com.lyhyy2007.bmcs.network;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.shop.ShopManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求打开商店（侧边栏按钮 / {@code /bmcs shop} 都会走到这里）。
 *
 * <p>无字段，用 {@link StreamCodec#unit} 编码；服务端不信任客户端的任何内容，收到就校验权限并回发数据。
 */
public record OpenShopPayload() implements CustomPacketPayload {
    public static final OpenShopPayload INSTANCE = new OpenShopPayload();
    public static final CustomPacketPayload.Type<OpenShopPayload> TYPE =
            new CustomPacketPayload.Type<>(BetterMinecraftShop.id("open_shop"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenShopPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenShopPayload payload, IPayloadContext context) {
        // 注册器默认已把 handler 调度到主线程，这里再显式 enqueueWork 一次，保证是服务端线程。
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ShopManager.get().openShop(player);
            }
        });
    }
}
