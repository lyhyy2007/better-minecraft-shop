package com.lyhyy2007.bmcs.network;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.client.ClientShopBridge;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：商店快照的一个<b>分批</b>分片。
 *
 * <p>为什么要分批：10000 条商品如果塞进一个包，序列化出来是数百 KB 甚至上 MB，
 * 会撞上原版自定义载荷 1MB 的硬上限并直接把玩家踢下线。因此服务端按
 * {@link #MAX_BATCH_ENTRIES}（500 条）切片连续发送，客户端在
 * {@code ShopClientData} 里累积，收齐最后一片才装配快照并打开界面。
 *
 * <p>分片字段语义：
 * <ul>
 *   <li>{@code openScreen}：只有「打开商店」请求的第 0 片为 true；</li>
 *   <li>{@code revision}：目录版本，用于丢弃过期分片、并让交易请求能识别旧界面；</li>
 *   <li>{@code balance}：余额（只有第 0 片有效，其余分片为 0）；</li>
 *   <li>{@code categories}：分类（只有第 0 片携带，通常远小于商品数量）；</li>
 *   <li>{@code totalEntries}：本次快照的商品总数，客户端用它做完整性校验。</li>
 * </ul>
 *
 * <p>商品模板以 {@link ItemStack} 过网，走原版 {@code ItemStack.STREAM_CODEC}，
 * 因此附魔、改名等 DataComponent 一併同步，客户端展示的就是真正会拿到的物品
 * （SDMShop 是把整个目录序列化成一坨 NBT 传，客户端只拿到字符串标题）。
 * 展示名不占带宽：客户端用游戏内置本地化自己算。
 */
public record ShopDataPayload(
        boolean openScreen,
        int revision,
        long balance,
        int batchIndex,
        int batchCount,
        int totalEntries,
        List<CategoryData> categories,
        List<EntryData> entries
) implements CustomPacketPayload {
    /** 上限仅用于防御异常 / 恶意数据，正常配置远低于此。 */
    public static final int MAX_CATEGORIES = 256;
    /** 单个网络包最多携带的商品条数（需求：每批 ≤ 500 条）。 */
    public static final int MAX_BATCH_ENTRIES = 500;
    /** 一次同步允许的商品总数上限。 */
    public static final int MAX_TOTAL_ENTRIES = 65536;

    public static final CustomPacketPayload.Type<ShopDataPayload> TYPE =
            new CustomPacketPayload.Type<>(BetterMinecraftShop.id("shop_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopDataPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.openScreen());
                buffer.writeVarInt(payload.revision());
                buffer.writeVarLong(payload.balance());
                buffer.writeVarInt(payload.batchIndex());
                buffer.writeVarInt(payload.batchCount());
                buffer.writeVarInt(payload.totalEntries());
                buffer.writeVarInt(payload.categories().size());
                for (CategoryData category : payload.categories()) {
                    CategoryData.STREAM_CODEC.encode(buffer, category);
                }
                buffer.writeVarInt(payload.entries().size());
                for (EntryData entry : payload.entries()) {
                    EntryData.STREAM_CODEC.encode(buffer, entry);
                }
            },
            buffer -> {
                boolean openScreen = buffer.readBoolean();
                int revision = buffer.readVarInt();
                long balance = buffer.readVarLong();
                int batchIndex = buffer.readVarInt();
                int batchCount = buffer.readVarInt();
                int totalEntries = buffer.readVarInt();
                if (batchCount < 1 || batchCount > MAX_TOTAL_ENTRIES) {
                    throw new DecoderException("bmcs: bad batch count " + batchCount);
                }
                if (batchIndex < 0 || batchIndex >= batchCount) {
                    throw new DecoderException("bmcs: bad batch index " + batchIndex + "/" + batchCount);
                }
                if (totalEntries < 0 || totalEntries > MAX_TOTAL_ENTRIES) {
                    throw new DecoderException("bmcs: bad total entries " + totalEntries);
                }

                int categoryCount = buffer.readVarInt();
                if (categoryCount < 0 || categoryCount > MAX_CATEGORIES) {
                    throw new DecoderException("bmcs: bad category count " + categoryCount);
                }
                List<CategoryData> categories = new ArrayList<>(categoryCount);
                for (int i = 0; i < categoryCount; i++) {
                    categories.add(CategoryData.STREAM_CODEC.decode(buffer));
                }

                int entryCount = buffer.readVarInt();
                if (entryCount < 0 || entryCount > MAX_BATCH_ENTRIES) {
                    throw new DecoderException("bmcs: bad batch entry count " + entryCount);
                }
                List<EntryData> entries = new ArrayList<>(entryCount);
                for (int i = 0; i < entryCount; i++) {
                    entries.add(EntryData.STREAM_CODEC.decode(buffer));
                }
                return new ShopDataPayload(openScreen, revision, balance, batchIndex, batchCount, totalEntries,
                        List.copyOf(categories), List.copyOf(entries));
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopDataPayload payload, IPayloadContext context) {
        // 该 handler 只在客户端执行；ClientShopBridge 是客户端专属类，专用服务器上永远不会被解析到。
        context.enqueueWork(() -> ClientShopBridge.onShopData(payload));
    }

    /** 是否是本批次的最后一片。 */
    public boolean last() {
        return batchIndex == batchCount - 1;
    }

    /** 分类（页签）。{@code displayName} 可为空，空则由客户端的分类翻译键提供名字。 */
    public record CategoryData(String id, String displayName, ItemStack icon) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CategoryData> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeUtf(value.id());
                    buffer.writeUtf(value.displayName());
                    ItemStack.STREAM_CODEC.encode(buffer, value.icon());
                },
                buffer -> new CategoryData(buffer.readUtf(), buffer.readUtf(),
                        ItemStack.STREAM_CODEC.decode(buffer)));
    }

    /** 商品条目（不含展示名：由客户端本地化）。 */
    public record EntryData(
            UUID id,
            String categoryId,
            ItemStack template,
            int price,
            int sellPrice,
            int remaining,
            int maxTrade,
            boolean customComponents
    ) {
        public static final StreamCodec<RegistryFriendlyByteBuf, EntryData> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {
                    UUIDUtil.STREAM_CODEC.encode(buffer, value.id());
                    buffer.writeUtf(value.categoryId());
                    ItemStack.STREAM_CODEC.encode(buffer, value.template());
                    buffer.writeVarInt(value.price());
                    buffer.writeVarInt(value.sellPrice());
                    buffer.writeVarInt(value.remaining());
                    buffer.writeVarInt(value.maxTrade());
                    buffer.writeBoolean(value.customComponents());
                },
                buffer -> new EntryData(
                        UUIDUtil.STREAM_CODEC.decode(buffer),
                        buffer.readUtf(),
                        ItemStack.STREAM_CODEC.decode(buffer),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readBoolean()));

        public boolean unlimited() {
            return remaining < 0;
        }

        public boolean sellable() {
            return sellPrice > 0;
        }
    }
}
