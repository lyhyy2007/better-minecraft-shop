package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.network.OpenShopPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 键位绑定：默认 {@code O} 打开商店（可在原版「选项 → 控制 → 按键绑定」里改）。
 *
 * <p>实现要点（都按 1.21.1 的真实 API 来，不用猜）：
 * <ul>
 *   <li>1.21.1 的 {@link KeyMapping} 走「{@code description} + {@code IKeyConflictContext} +
 *       {@code InputConstants.Type} + {@code keyCode} + {@code category 字符串}」这套构造器，
 *       分类是一个翻译键字符串（{@code key.categories.<modid>}），
 *       原版控制菜单会用 {@code Component.translatable(category)} 显示它 ——
 *       因此自定义分类只需要在 lang 里补上这个键；</li>
 *   <li>注册走 {@link RegisterKeyMappingsEvent}（mod 事件总线，仅客户端）；</li>
 *   <li>每 tick 用 {@link KeyMapping#consumeClick()} 取一次点击：**必须无条件消费**，
 *       否则点击会排队到下一次打开界面时被误触发；</li>
 *   <li>冲突处理：{@link KeyConflictContext#IN_GAME} 让别的模组占用同一按键时原版控制菜单会标红提醒；
 *       只要有任何界面打开（{@code minecraft.screen != null}）本模组就不抢按键，
 *       原版本身也只在无界面时才派发 {@code KeyMapping.click}。</li>
 * </ul>
 *
 * <p>默认键 {@code GLFW_KEY_O}(79) 在原版没有任何默认绑定占用（已核对 1.21.1 {@code Options} 的
 * 全部默认键位：W/A/S/D/空格/E/F/Q/T/Tab//P/F2/F5/F11/L/C/X/1-9 等），
 * 因此开箱即用不会与任何原版操作冲突。
 */
public final class ShopKeyMappings {
    /** 分类翻译键：{@code key.categories.better_minecraft_shop}。 */
    public static final String CATEGORY = "key.categories." + BetterMinecraftShop.MOD_ID;
    /** 键位翻译键：{@code key.better_minecraft_shop.open_shop}。 */
    public static final String OPEN_SHOP_NAME = "key." + BetterMinecraftShop.MOD_ID + ".open_shop";
    /** 默认按键：O。 */
    public static final int DEFAULT_KEY = GLFW.GLFW_KEY_O;

    public static final KeyMapping OPEN_SHOP = new KeyMapping(
            OPEN_SHOP_NAME,
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            DEFAULT_KEY,
            CATEGORY);

    private ShopKeyMappings() {
    }

    /** mod 事件总线：注册键位。 */
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SHOP);
        BetterMinecraftShop.LOGGER.info("[{}] 已注册键位 {}（默认 {}，可在原版控制菜单修改）",
                BetterMinecraftShop.MOD_NAME, OPEN_SHOP_NAME, OPEN_SHOP.getDefaultKey().getName());
    }

    /** 游戏事件总线：每 tick 检查按键。 */
    public static void onClientTick(ClientTickEvent.Post event) {
        boolean clicked = false;
        // 无条件清空点击队列，避免按键事件滞留。
        while (OPEN_SHOP.consumeClick()) {
            clicked = true;
        }
        if (!clicked) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            return;
        }
        if (minecraft.screen != null) {
            // 有界面打开：按键归界面处理，不抢。
            return;
        }
        try {
            // 客户端不自己打开界面：必须等服务端校验后回发数据（服务端权威）。
            PacketDistributor.sendToServer(OpenShopPayload.INSTANCE);
        } catch (RuntimeException e) {
            BetterMinecraftShop.LOGGER.warn("[{}] 按键请求打开商店失败（可能正在断开连接）",
                    BetterMinecraftShop.MOD_NAME, e);
        }
    }
}
