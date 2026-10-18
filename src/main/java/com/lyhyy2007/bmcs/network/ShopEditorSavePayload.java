package com.lyhyy2007.bmcs.network;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.config.ShopSide;
import com.lyhyy2007.bmcs.shop.ShopItemEditorService;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：把背包里的一件物品存成商店商品。
 *
 * <p>服务端不信任这里面的任何数值：
 * <ul>
 *   <li>{@code slot} 只接受玩家主背包 / 副手的槽位（{@link com.lyhyy2007.bmcs.util.ItemStackUtil#isTradeableSlot}），
 *       越界直接丢弃；</li>
 *   <li>{@code itemId} 是客户端当时看到的物品 id，服务端会和槽位里的真实物品比对，
 *       防止「点的是钻石、发出去的是泥土」；</li>
 *   <li>{@code price} / {@code stock} 只做网络层范围检查，业务范围由
 *       {@link com.lyhyy2007.bmcs.config.ShopItemWriter} 再校验一次；</li>
 *   <li>{@code revision} 是客户端看到的目录版本，过期的请求会被拒绝并让客户端重新同步。</li>
 * </ul>
 *
 * @param side       保存到 in/（买入）还是 out/（卖出）
 * @param categoryId 目标分类 id（必须是游戏内新建、可编辑的分类）
 * @param slot       玩家背包槽位下标；{@code -1} 表示玩家从「全部物品」列表里挑的
 *                   （服务端按 {@code itemId} 构造默认形态的物品，不读背包）
 * @param itemId     客户端认为该槽位里的物品注册名
 * @param price      买入价或回收价（&gt;= 1）
 * @param stock      库存；只有买入方向有效，{@code -1} = 不限量
 * @param revision   客户端看到的目录版本号
 */
public record ShopEditorSavePayload(ShopSide side, String categoryId, int slot, String itemId, int price,
                                    int stock, int revision) implements CustomPacketPayload {
    /** 网络层硬上限：只用于挡掉离谱数值，不做业务校验。 */
    public static final int MAX_PRICE = 1_000_000_000;
    /** 网络层库存上限。 */
    public static final int MAX_STOCK = 1_000_000;

    public static final CustomPacketPayload.Type<ShopEditorSavePayload> TYPE =
            new CustomPacketPayload.Type<>(BetterMinecraftShop.id("shop_editor_save"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopEditorSavePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.side());
                buffer.writeUtf(payload.categoryId(), 64);
                buffer.writeVarInt(payload.slot());
                buffer.writeUtf(payload.itemId(), 128);
                buffer.writeVarInt(payload.price());
                buffer.writeVarInt(payload.stock());
                buffer.writeVarInt(payload.revision());
            },
            buffer -> {
                ShopSide side = buffer.readEnum(ShopSide.class);
                String categoryId = buffer.readUtf(64);
                int slot = buffer.readVarInt();
                String itemId = buffer.readUtf(128);
                int price = buffer.readVarInt();
                int stock = buffer.readVarInt();
                int revision = buffer.readVarInt();
                if (slot < -1 || slot > 255) {
                    throw new DecoderException("bmcs: bad editor slot " + slot);
                }
                if (price < 0 || price > MAX_PRICE) {
                    throw new DecoderException("bmcs: bad editor price " + price);
                }
                if (stock < -1 || stock > MAX_STOCK) {
                    throw new DecoderException("bmcs: bad editor stock " + stock);
                }
                return new ShopEditorSavePayload(side, categoryId, slot, itemId, price, stock, revision);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopEditorSavePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ShopItemEditorService.handle(player, payload);
            }
        });
    }
}
