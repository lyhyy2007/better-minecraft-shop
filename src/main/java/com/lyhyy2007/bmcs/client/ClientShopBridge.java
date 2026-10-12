package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.network.ShopDataPayload;
import com.lyhyy2007.bmcs.network.ShopPatchPayload;
import dev.ftb.mods.ftblibrary.ui.ScreenWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端数据 → 客户端界面的桥接。
 *
 * <p>只有客户端会加载这个类（{@code ShopDataPayload#handle} 里的调用只在客户端执行），
 * 专用服务器上永远不会解析到它。
 *
 * <p>「延迟加载」策略落在这里：只有商店界面开着、或者服务端明确要求打开界面时，
 * 才会去装配分片数据；否则连收齐都懒得收（1 万条商品不会在没打开界面时常驻客户端内存）。
 */
public final class ClientShopBridge {
    private ClientShopBridge() {
    }

    public static void onShopData(ShopDataPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ShopScreen open = currentShopScreen();
        boolean interested = open != null || payload.openScreen() || ShopClientData.accumulating();
        if (!interested) {
            // 界面没开、也不是打开请求：直接丢弃（含在途的其他玩家刷新）。
            BetterMinecraftShop.LOGGER.debug("[{}] 未打开商店，忽略商店数据分片 {}", BetterMinecraftShop.MOD_NAME,
                    payload.batchIndex());
            return;
        }

        if (!ShopClientData.accept(payload)) {
            // 还没收齐（或分片被判定为不完整），等后续分片。
            return;
        }

        ShopScreen screen = currentShopScreen();
        if (screen != null) {
            // 界面已打开：只刷新数据，不重建界面（保住滚动位置、搜索框内容、选中项）。
            screen.onDataUpdated();
        } else {
            // 本方法经 enqueueWork 调度，已经在客户端主线程上，可以直接 openGui()。
            new ShopScreen().openGui();
        }
    }

    /** 交易后的增量补丁。 */
    public static void onShopPatch(ShopPatchPayload patch) {
        if (!ShopClientData.applyPatch(patch)) {
            return;
        }
        ShopScreen screen = currentShopScreen();
        if (screen != null) {
            screen.onDataUpdated();
        }
    }

    /** 当前打开的商店界面（没有则返回 null）。 */
    @Nullable
    private static ShopScreen currentShopScreen() {
        Screen current = Minecraft.getInstance().screen;
        if (current instanceof ScreenWrapper wrapper && wrapper.getGui() instanceof ShopScreen screen) {
            return screen;
        }
        return null;
    }
}
