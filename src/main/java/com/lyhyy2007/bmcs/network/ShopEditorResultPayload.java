package com.lyhyy2007.bmcs.network;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.client.ClientShopBridge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：游戏内「新建商品」的保存结果。
 *
 * <p>失败原因用 {@link Component} 下发（走原版聊天组件序列化，客户端语言本地化），
 * 编辑器界面直接把这句话显示在底部；界面已经关掉时也不会丢失原因——
 * 服务端同时会把失败原因写进玩家聊天栏。
 *
 * @param success    是否保存成功
 * @param message    已本地化的提示文本
 * @param categoryId 成功后希望客户端自动选中的分类 id（空串 = 不改变当前选中）
 */
public record ShopEditorResultPayload(boolean success, Component message, String categoryId)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ShopEditorResultPayload> TYPE =
            new CustomPacketPayload.Type<>(BetterMinecraftShop.id("shop_editor_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopEditorResultPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.success());
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.message());
                buffer.writeUtf(payload.categoryId() == null ? "" : payload.categoryId(), 64);
            },
            buffer -> new ShopEditorResultPayload(buffer.readBoolean(),
                    ComponentSerialization.STREAM_CODEC.decode(buffer), buffer.readUtf(64)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopEditorResultPayload payload, IPayloadContext context) {
        // 只在客户端执行；ClientShopBridge 是客户端专属类，专用服务器上不会被解析到。
        context.enqueueWork(() -> ClientShopBridge.onEditorResult(payload));
    }
}
