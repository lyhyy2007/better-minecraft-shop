package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端入口。
 *
 * <p>用 {@code @Mod(value = MOD_ID, dist = Dist.CLIENT)} 声明：这个类只在客户端被加载，
 * 专用服务器上连类都不会解析，所以里面可以放心引用 FTB Library 的客户端 UI 类、LWJGL 与 Architectury 事件。
 *
 * <p>客户端只做三件事：
 * <ol>
 *   <li>注册 FTB Library 侧边栏按钮的点击处理（{@link ShopSidebarIntegration}）；</li>
 *   <li>注册键位与每 tick 的按键检查（{@link ShopKeyMappings}）；</li>
 *   <li>接收服务端数据并打开界面（{@link ClientShopBridge}，由网络包 handler 调用）。</li>
 * </ol>
 */
@Mod(value = BetterMinecraftShop.MOD_ID, dist = Dist.CLIENT)
public final class BmcsClient {
    public BmcsClient(IEventBus modEventBus) {
        ShopSidebarIntegration.register();
        // 键位注册走 mod 事件总线（RegisterKeyMappingsEvent），按键检查走游戏事件总线。
        modEventBus.addListener(ShopKeyMappings::register);
        NeoForge.EVENT_BUS.addListener(ShopKeyMappings::onClientTick);
    }
}
