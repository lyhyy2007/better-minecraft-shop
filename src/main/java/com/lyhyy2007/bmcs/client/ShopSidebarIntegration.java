package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.network.OpenShopPayload;
import dev.architectury.event.EventResult;
import dev.ftb.mods.ftblibrary.ui.CustomClickEvent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * FTB Library 侧边栏按钮的点击处理。
 *
 * <p>集成链路（全部基于 FTB Library 2101.1.37 的真实实现，非猜测）：
 * <ol>
 *   <li>{@code assets/better_minecraft_shop/sidebar_buttons/shop.json} 由 FTB Library 的
 *       {@code SidebarButtonManager}（{@code SimpleJsonResourceReloadListener}，目录名 {@code sidebar_buttons}）加载，
 *       JSON 用 {@code SidebarButtonData.CODEC} 解析；</li>
 *   <li>{@code click} 是一个字符串数组，元素形如 {@code scheme:path}；本按钮写
 *       {@code "better_minecraft_shop:open_shop"}；</li>
 *   <li>点击时 {@code RegisteredSidebarButton#clickButton} 调用 {@code Widget.handleClick(String)}，
 *       按第一个 ':' 拆成 scheme/path 后交给 {@code ClientUtils.handleClick}；
 *       scheme 不是 http/https/file/command/static_method/custom 时走 default 分支，
 *       以完整的 {@code "better_minecraft_shop:open_shop"} 触发 Architectury 事件
 *       {@code CustomClickEvent.EVENT}；</li>
 *   <li>本类注册该事件的监听器，命中即向服务端发 {@link OpenShopPayload}（客户端不自己打开界面，
 *       必须等服务端校验后回发数据，保证「服务端权威」）。</li>
 * </ol>
 *
 * <p>注意：FTB Library 的 JSON 并没有 {@code group} / {@code x} 字段（那是更老版本的 schema），
 * 位置由 {@code sort_index} 与 FTB 自己的客户端配置决定。
 */
public final class ShopSidebarIntegration {
    /** 与 sidebar_buttons/shop.json 里的 click 值必须完全一致。 */
    public static final ResourceLocation OPEN_SHOP_CLICK = BetterMinecraftShop.id("open_shop");

    private ShopSidebarIntegration() {
    }

    public static void register() {
        CustomClickEvent.EVENT.register(event -> {
            if (!OPEN_SHOP_CLICK.equals(event.id())) {
                return EventResult.pass();
            }
            try {
                PacketDistributor.sendToServer(OpenShopPayload.INSTANCE);
            } catch (RuntimeException e) {
                // 未连接服务器时（例如主菜单界面）不应该崩客户端。
                BetterMinecraftShop.LOGGER.warn("[{}] 请求打开商店失败（可能未连接服务器）",
                        BetterMinecraftShop.MOD_NAME, e);
                return EventResult.interruptFalse();
            }
            return EventResult.interruptTrue();
        });
        BetterMinecraftShop.LOGGER.info("[{}] 已注册 FTB Library 侧边栏点击事件 {}",
                BetterMinecraftShop.MOD_NAME, OPEN_SHOP_CLICK);
    }
}
