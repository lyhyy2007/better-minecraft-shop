package com.lyhyy2007.bmcs;

import com.lyhyy2007.bmcs.command.BmcsCommands;
import com.lyhyy2007.bmcs.config.ShopConfig;
import com.lyhyy2007.bmcs.currency.DeepCoinCurrency;
import com.lyhyy2007.bmcs.network.ShopNetwork;
import com.lyhyy2007.bmcs.shop.ShopConfigWatcher;
import com.lyhyy2007.bmcs.shop.ShopManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 更好的 Minecraft 商店（better-minecraft-shop / bmcs）主模组类。
 *
 * <p>设计要点（相对 SDMShop 的重构）：
 * <ul>
 *   <li>只依赖 NeoForge + FTB Library（+ Architectury，FTB Library 的强制前置），
 *       不再依赖 SDM-Economy / SDMCore / SDM-UI-Lib；</li>
 *   <li>货币走 NeoForge Data Attachment（Codec 序列化、随玩家存档、死亡不掉落），
 *       玩家余额不存放在物品堆栈上；物品数据本身全程使用 DataComponent，不碰原始 NBT；</li>
 *   <li>商品目录来自服务端多文件 JSON 配置，买入读 {@code config/better-minecraft-shop/in/**.json}、
 *       卖出读 {@code config/better-minecraft-shop/out/**.json}（不再生成默认配置），
 *       库存走 SavedData，全部服务端权威；</li>
 *   <li>所有交易在服务端主线程校验（数量、余额、库存、背包空间），失败即回滚并给出提示；</li>
 *   <li>客户端入口有三条：FTB Library 侧边栏按钮（背包左上角）、默认 O 键、{@code /bmcs shop}，
 *       三条路径都只是「向服务端请求打开」，界面数据一律由服务端下发。</li>
 * </ul>
 */
@Mod(BetterMinecraftShop.MOD_ID)
public final class BetterMinecraftShop {
    /** loader 侧 modId：FML 4.0.x 的 VALID_MODID 是 ^[a-z][a-z0-9_]{1,63}$，不允许连字符。 */
    public static final String MOD_ID = "better_minecraft_shop";
    /** 品牌名（jar 文件名 / 展示名），保留连字符写法。 */
    public static final String BRAND = "better-minecraft-shop";
    public static final String MOD_NAME = "Better Minecraft Shop";
    /** 配置目录名，按需求使用品牌名。 */
    public static final String CONFIG_DIR = BRAND;
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    public BetterMinecraftShop(IEventBus modEventBus) {
        // 玩家货币附件（AttachmentType 注册在 mod 事件总线上）
        DeepCoinCurrency.register(modEventBus);
        // 网络包（RegisterPayloadHandlersEvent 同样属于 mod 事件总线）
        modEventBus.addListener(ShopNetwork::register);

        // 游戏事件总线：命令、服务端生命周期
        NeoForge.EVENT_BUS.addListener(BetterMinecraftShop::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(BetterMinecraftShop::onServerStarted);
        NeoForge.EVENT_BUS.addListener(BetterMinecraftShop::onServerStopping);

        LOGGER.info("{} 已加载：深索币货币 + FTB Library 侧边栏商店入口 + O 键快捷入口 + 多文件配置", MOD_NAME);
    }

    /** 本模组的资源 / 注册名。 */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        BmcsCommands.register(event.getDispatcher());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        // 配置解析需要注册表上下文（附魔等 DataComponent 的 codec 要查注册表）。
        ShopConfig.get().load(event.getServer().registryAccess());
        ShopManager.get().onServerStarted(event.getServer());
        // 配置就绪后再开始监听目录变更（热重载）。
        ShopConfigWatcher.start(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ShopConfigWatcher.stop();
        ShopManager.get().onServerStopping(event.getServer());
    }
}
