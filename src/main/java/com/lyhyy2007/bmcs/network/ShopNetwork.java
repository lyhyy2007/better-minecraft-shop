package com.lyhyy2007.bmcs.network;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络包注册（NeoForge {@code RegisterPayloadHandlersEvent}，走 mod 事件总线）。
 *
 * <p>载荷一律使用 {@code StreamCodec}；handler 由注册器包装到主线程执行，
 * 因此交易逻辑天然运行在服务端主线程上，不需要额外加锁（SDMShop 的 {@code context.queue} 之外
 * 还有一堆静态可变状态被两端共用，那才是并发问题的根源）。
 *
 * <p>包清单：
 * <ul>
 *   <li>{@link OpenShopPayload} 客户端 → 服务端：请求打开商店；</li>
 *   <li>{@link ShopActionPayload} 客户端 → 服务端：买入 / 卖出；</li>
 *   <li>{@link ShopEditorSavePayload} 客户端 → 服务端：把背包物品存成商店商品（仅 OP）；</li>
 *   <li>{@link ShopCategoryCreatePayload} 客户端 → 服务端：新建分类（仅 OP）；</li>
 *   <li>{@link ShopCategoryUpdatePayload} 客户端 → 服务端：分类改名 / 换图标（仅 OP）；</li>
 *   <li>{@link ShopItemDeletePayload} 客户端 → 服务端：删除商品（仅 OP）；</li>
 *   <li>{@link ShopDataPayload} 服务端 → 客户端：全量快照（按 500 条分批）；</li>
 *   <li>{@link ShopPatchPayload} 服务端 → 客户端：交易后的单品增量补丁；</li>
 *   <li>{@link ShopEditorResultPayload} 服务端 → 客户端：编辑操作的结果。</li>
 * </ul>
 */
public final class ShopNetwork {
    /**
     * 协议版本：双方不一致时 NeoForge 会直接拒绝连接，避免字段错位。
     *
     * <p>1.0.5 起升到 {@code 2}：{@link ShopDataPayload} 增加了 {@code canEdit} 字段，
     * 并新增了编辑器相关包；{@code 3}：新增分类创建 / 改名 / 删商品三个包、
     * {@link ShopEditorSavePayload} 增加 {@code categoryId}、结果包增加 {@code categoryId}。
     */
    private static final String VERSION = "3";

    private ShopNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(OpenShopPayload.TYPE, OpenShopPayload.STREAM_CODEC, OpenShopPayload::handle);
        registrar.playToServer(ShopActionPayload.TYPE, ShopActionPayload.STREAM_CODEC, ShopActionPayload::handle);
        registrar.playToServer(ShopEditorSavePayload.TYPE, ShopEditorSavePayload.STREAM_CODEC,
                ShopEditorSavePayload::handle);
        registrar.playToServer(ShopCategoryCreatePayload.TYPE, ShopCategoryCreatePayload.STREAM_CODEC,
                ShopCategoryCreatePayload::handle);
        registrar.playToServer(ShopCategoryUpdatePayload.TYPE, ShopCategoryUpdatePayload.STREAM_CODEC,
                ShopCategoryUpdatePayload::handle);
        registrar.playToServer(ShopItemDeletePayload.TYPE, ShopItemDeletePayload.STREAM_CODEC,
                ShopItemDeletePayload::handle);
        registrar.playToClient(ShopDataPayload.TYPE, ShopDataPayload.STREAM_CODEC, ShopDataPayload::handle);
        registrar.playToClient(ShopPatchPayload.TYPE, ShopPatchPayload.STREAM_CODEC, ShopPatchPayload::handle);
        registrar.playToClient(ShopEditorResultPayload.TYPE, ShopEditorResultPayload.STREAM_CODEC,
                ShopEditorResultPayload::handle);
        BetterMinecraftShop.LOGGER.debug("[{}] 网络包已注册（协议版本 {}）", BetterMinecraftShop.MOD_NAME, VERSION);
    }
}
