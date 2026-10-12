package com.lyhyy2007.bmcs.command;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.config.CategoryPriority;
import com.lyhyy2007.bmcs.config.ShopConfig;
import com.lyhyy2007.bmcs.config.ShopItemEntry;
import com.lyhyy2007.bmcs.currency.DeepCoinCurrency;
import com.lyhyy2007.bmcs.network.ShopDataPayload;
import com.lyhyy2007.bmcs.shop.ShopConfigWatcher;
import com.lyhyy2007.bmcs.shop.ShopManager;
import com.lyhyy2007.bmcs.shop.ShopStockManager;
import com.lyhyy2007.bmcs.util.ItemNameUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /bmcs} 命令族。
 *
 * <p>权限：查询与转账人人可用；改写余额 / 库存 / 重载配置 / debug 需要权限等级 2（管理员）。
 * 权限判定一律走 {@link CommandSourceStack#hasPermission(int)}，与 SDMShop 那种
 * 「客户端说自己在编辑模式就放行」的做法完全不同。
 *
 * <p>命令清单：
 * <pre>
 *   /bmcs balance [玩家]              查询余额
 *   /bmcs pay &lt;玩家&gt; &lt;金额&gt;          转账
 *   /bmcs set|add &lt;玩家&gt; &lt;金额&gt;     管理员改余额
 *   /bmcs shop [玩家]                 打开商店界面（等价于侧边栏按钮 / O 键）
 *   /bmcs reload                      重新加载配置目录（热重载也可以自动触发）
 *   /bmcs stock list|set|reset        限量库存查询 / 调整 / 重置
 *   /bmcs debug item &lt;物品id&gt;         查本地化名称链路与在售价（排查「名字不对」）
 *   /bmcs debug sync [玩家]           跑一遍完整快照同步：批数、耗时、最大单包 KB
 *   /bmcs debug config                配置统计、目录、分类优先级、热重载状态
 * </pre>
 */
public final class BmcsCommands {
    private static final String KEY = "command." + BetterMinecraftShop.MOD_ID + ".";

    private BmcsCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bmcs")
                .then(Commands.literal("balance")
                        .executes(ctx -> balanceSelf(ctx.getSource()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> balanceOther(ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("pay")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> pay(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                LongArgumentType.getLong(ctx, "amount"))))))
                .then(Commands.literal("set")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(ctx -> setBalance(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                LongArgumentType.getLong(ctx, "amount"))))))
                .then(Commands.literal("add")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> addBalance(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                LongArgumentType.getLong(ctx, "amount"))))))
                .then(Commands.literal("shop")
                        .executes(ctx -> openShop(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> openShop(ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("stock")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> stockList(ctx.getSource()))
                        .then(Commands.literal("list")
                                .executes(ctx -> stockList(ctx.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument("entry", StringArgumentType.word())
                                        .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                                .executes(ctx -> stockSet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "entry"),
                                                        (int) Math.min(Integer.MAX_VALUE,
                                                                LongArgumentType.getLong(ctx, "amount")))))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("entry", StringArgumentType.word())
                                        .executes(ctx -> stockReset(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "entry"))))))
                .then(Commands.literal("debug")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("item")
                                .then(Commands.argument("item", StringArgumentType.greedyString())
                                        .executes(ctx -> debugItem(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "item")))))
                        .then(Commands.literal("sync")
                                .executes(ctx -> debugSync(ctx.getSource(), ctx.getSource().getPlayer()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> debugSync(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("config")
                                .executes(ctx -> debugConfig(ctx.getSource())))));
    }

    // ------------------------------------------------------------------
    // 货币
    // ------------------------------------------------------------------

    private static int balanceSelf(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        source.sendSuccess(() -> Component.translatable(KEY + "balance.self",
                DeepCoinCurrency.format(DeepCoinCurrency.balance(player))), false);
        return 1;
    }

    private static int balanceOther(CommandSourceStack source, ServerPlayer target) {
        source.sendSuccess(() -> Component.translatable(KEY + "balance.other",
                target.getGameProfile().getName(),
                DeepCoinCurrency.format(DeepCoinCurrency.balance(target))), false);
        return 1;
    }

    private static int pay(CommandSourceStack source, ServerPlayer target, long amount)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer sender = source.getPlayerOrException();
        Optional<Component> error = DeepCoinCurrency.transfer(sender, target, amount);
        if (error.isPresent()) {
            source.sendFailure(error.get());
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(KEY + "pay.sender",
                DeepCoinCurrency.format(amount), target.getGameProfile().getName()), false);
        target.sendSystemMessage(Component.translatable(KEY + "pay.receiver",
                DeepCoinCurrency.format(amount), sender.getGameProfile().getName()));
        return 1;
    }

    private static int setBalance(CommandSourceStack source, ServerPlayer target, long amount) {
        DeepCoinCurrency.setBalance(target, amount);
        source.sendSuccess(() -> Component.translatable(KEY + "set",
                target.getGameProfile().getName(), DeepCoinCurrency.format(DeepCoinCurrency.balance(target))), true);
        return 1;
    }

    private static int addBalance(CommandSourceStack source, ServerPlayer target, long amount) {
        DeepCoinCurrency.deposit(target, amount);
        source.sendSuccess(() -> Component.translatable(KEY + "add",
                DeepCoinCurrency.format(amount), target.getGameProfile().getName(),
                DeepCoinCurrency.format(DeepCoinCurrency.balance(target))), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // 商店
    // ------------------------------------------------------------------

    private static int openShop(CommandSourceStack source, ServerPlayer target) {
        ShopManager.get().openShop(target);
        source.sendSuccess(() -> Component.translatable(KEY + "shop.opened", target.getGameProfile().getName()), false);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        ShopManager.get().reload(source.getServer());
        ShopConfig config = ShopConfig.get();
        if (config.entries().isEmpty()) {
            source.sendFailure(Component.translatable(KEY + "reload.failed"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(KEY + "reload", config.statistics()), true);
        return 1;
    }

    private static int stockList(CommandSourceStack source) {
        ShopStockManager stock = ShopManager.get().stock();
        List<ShopItemEntry> entries = ShopConfig.get().entries();
        if (stock == null) {
            source.sendFailure(Component.translatable(KEY + "stock.unavailable"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(KEY + "stock.header", entries.size()), false);
        for (ShopItemEntry entry : entries) {
            int remaining = stock.remaining(entry);
            source.sendSuccess(() -> Component.translatable(KEY + "stock.line",
                    entry.key(), entry.displayNameComponent(),
                    remaining < 0 ? Component.translatable(KEY + "stock.unlimited") : Component.literal(String.valueOf(remaining))), false);
        }
        return 1;
    }

    private static int stockSet(CommandSourceStack source, String key, int amount) {
        ShopStockManager stock = ShopManager.get().stock();
        if (stock == null) {
            source.sendFailure(Component.translatable(KEY + "stock.unavailable"));
            return 0;
        }
        Optional<ShopItemEntry> entry = ShopConfig.get().byKey(key);
        if (entry.isEmpty()) {
            source.sendFailure(Component.translatable(KEY + "stock.unknown", key));
            return 0;
        }
        if (entry.get().unlimited()) {
            source.sendFailure(Component.translatable(KEY + "stock.is_unlimited", key));
            return 0;
        }
        stock.set(entry.get(), amount);
        source.sendSuccess(() -> Component.translatable(KEY + "stock.set", key, amount), true);
        return 1;
    }

    private static int stockReset(CommandSourceStack source, String key) {
        ShopStockManager stock = ShopManager.get().stock();
        if (stock == null) {
            source.sendFailure(Component.translatable(KEY + "stock.unavailable"));
            return 0;
        }
        Optional<ShopItemEntry> entry = ShopConfig.get().byKey(key);
        if (entry.isEmpty()) {
            source.sendFailure(Component.translatable(KEY + "stock.unknown", key));
            return 0;
        }
        stock.reset(entry.get());
        source.sendSuccess(() -> Component.translatable(KEY + "stock.reset", key, entry.get().initialStock()), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // debug：排查「物品名字不对 / 配置没生效」
    // ------------------------------------------------------------------

    /**
     * {@code /bmcs debug item <物品id>}：打印本地化名称链路与在售情况。
     *
     * <p>名字问题（模组物品没带 lang、键拼错、只翻译了 en_us）大多靠这一条命令就能定位：
     * 它会同时给出翻译键、服务端语言表是否命中、命中时的渲染结果、以及缺翻译时的回退文本。
     */
    private static int debugItem(CommandSourceStack source, String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw.trim());
        if (id == null) {
            source.sendFailure(Component.translatable(KEY + "debug.item.invalid", raw));
            return 0;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) {
            source.sendFailure(Component.translatable(KEY + "debug.item.missing", id.toString()));
            return 0;
        }
        ItemStack stack = new ItemStack(item);
        String translationKey = ItemNameUtil.descriptionKey(stack);
        boolean localized = ItemNameUtil.isLocalized(stack);

        source.sendSuccess(() -> Component.translatable(KEY + "debug.item.header", Component.literal(id.toString())),
                false);
        source.sendSuccess(() -> line("debug.item.key", translationKey), false);
        source.sendSuccess(() -> line("debug.item.localized", String.valueOf(localized)), false);
        source.sendSuccess(() -> line("debug.item.translated",
                Component.translatable(translationKey).getString()), false);
        if (!localized) {
            source.sendSuccess(() -> line("debug.item.fallback", ItemNameUtil.fallbackText(translationKey)), false);
        }

        List<ShopItemEntry> matches = new ArrayList<>();
        for (ShopItemEntry entry : ShopConfig.get().entries()) {
            if (entry.template().getItem() == item) {
                matches.add(entry);
            }
        }
        if (matches.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(KEY + "debug.item.not_in_catalogue"), false);
        } else {
            ShopStockManager stock = ShopManager.get().stock();
            for (ShopItemEntry entry : matches) {
                int remaining = stock == null ? entry.initialStock() : stock.remaining(entry);
                Component stockText = remaining < 0
                        ? Component.translatable(KEY + "stock.unlimited")
                        : Component.literal(String.valueOf(remaining));
                source.sendSuccess(() -> Component.translatable(KEY + "debug.item.in_catalogue",
                        entry.key(),
                        DeepCoinCurrency.format(entry.price()),
                        DeepCoinCurrency.format(entry.sellPrice()),
                        stockText), false);
            }
        }
        source.sendSuccess(() -> Component.translatable(KEY + "debug.item.note"), false);
        return 1;
    }

    /** {@code /bmcs debug config}：配置统计 / 路径 / 优先级表 / 热重载状态。 */    private static int debugConfig(CommandSourceStack source) {
        ShopConfig config = ShopConfig.get();
        source.sendSuccess(() -> Component.translatable(KEY + "debug.config.header", config.statistics()), false);
        source.sendSuccess(() -> Component.translatable(KEY + "debug.config.path",
                config.dir().toAbsolutePath().toString()), false);
        source.sendSuccess(() -> Component.translatable(KEY + "debug.config.priority", CategoryPriority.order()), false);
        boolean watching = ShopConfigWatcher.active();
        source.sendSuccess(() -> Component.translatable(KEY + "debug.config.watch",
                Component.translatable(KEY + (watching ? "debug.config.watch.on" : "debug.config.watch.off")),
                ShopConfigWatcher.reloadCount()), false);
        return 1;
    }

    private static Component line(String key, Object value) {
        return Component.translatable(KEY + "debug.item.line", Component.translatable(KEY + key), value);
    }

    /**
     * {@code /bmcs debug sync [玩家]}：把「同步一份完整快照」真正跑一遍并报告耗时与包体大小。
     *
     * <p>用途：没有客户端也能验证大数据量下的同步开销 ——
     * 分几批、每批多少条、最大单包多少 KB（原版自定义载荷硬上限 1MB）、
     * 以及客户端装配快照时必须做的本地化名索引耗时。数据会被丢弃，不发给任何人。
     *
     * @param target 指定玩家时按其余额组装；控制台直接执行时按余额 0 组装
     */
    private static int debugSync(CommandSourceStack source, @Nullable ServerPlayer target) {
        long start = System.nanoTime();
        List<ShopDataPayload> batches = ShopManager.get().buildBatches(target, true);
        long assembled = System.nanoTime();

        int entries = 0;
        int maxBytes = 0;
        long totalBytes = 0L;
        RegistryAccess registries = target == null ? source.getServer().registryAccess() : target.server.registryAccess();
        for (ShopDataPayload payload : batches) {
            entries += payload.entries().size();
            // 真正编码一次：ItemStack 的组件编解码需要注册表上下文，
            // 所以这里用带注册表的 RegistryFriendlyByteBuf（与网络层实际用的完全一致）。
            // 注意：NeoForge 已把 (ByteBuf, RegistryAccess) 构造器与单参 decorator 标记为过时，
            // 官方入口是带 ConnectionType 的 decorator（服务端本地连接即 NEOFORGE）。
            RegistryFriendlyByteBuf buffer = RegistryFriendlyByteBuf
                    .decorator(registries, ConnectionType.NEOFORGE)
                    .apply(Unpooled.buffer());
            try {
                ShopDataPayload.STREAM_CODEC.encode(buffer, payload);
                int size = buffer.readableBytes();
                maxBytes = Math.max(maxBytes, size);
                totalBytes += size;
            } finally {
                buffer.release();
            }
        }
        long encoded = System.nanoTime();

        // 客户端 ShopClientData 装配快照时会为每条商品算一次本地化名（搜索索引），这里同样跑一遍。
        long indexStart = System.nanoTime();
        int chars = 0;
        for (ShopItemEntry entry : ShopConfig.get().entries()) {
            chars += ItemNameUtil.plainText(entry.template()).length();
        }
        long indexEnd = System.nanoTime();

        final int entryCount = entries;
        final int batchCount = batches.size();
        final long assembleMillis = (assembled - start) / 1_000_000L;
        final long encodeMillis = (encoded - assembled) / 1_000_000L;
        final int maxKb = maxBytes / 1024;
        final long totalKb = totalBytes / 1024;
        final long indexMillis = (indexEnd - indexStart) / 1_000_000L;
        final int charCount = chars;
        source.sendSuccess(() -> Component.translatable(KEY + "debug.sync.header", entryCount, batchCount), false);
        source.sendSuccess(() -> Component.translatable(KEY + "debug.sync.assemble", assembleMillis), false);
        source.sendSuccess(() -> Component.translatable(KEY + "debug.sync.encode", encodeMillis, maxKb, totalKb),
                false);
        source.sendSuccess(() -> Component.translatable(KEY + "debug.sync.index", indexMillis, charCount), false);
        return 1;
    }
}
