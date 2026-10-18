package com.lyhyy2007.bmcs.shop;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.config.ShopConfig;
import com.lyhyy2007.bmcs.config.ShopItemEntry;
import com.lyhyy2007.bmcs.config.ShopSide;
import com.lyhyy2007.bmcs.currency.DeepCoinCurrency;
import com.lyhyy2007.bmcs.network.ShopActionPayload;
import com.lyhyy2007.bmcs.network.ShopDataPayload;
import com.lyhyy2007.bmcs.network.ShopPatchPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端商店管理器：把配置、库存、余额三者拼成同步给客户端的快照，并处理交易请求。
 *
 * <p>同步策略（大数据量下的关键设计）：
 * <ul>
 *   <li><b>打开 / 重载</b>：按 {@link ShopDataPayload#MAX_BATCH_ENTRIES}（500 条）分批推全量，
 *       客户端收齐最后一片才装配快照；</li>
 *   <li><b>交易后</b>：只推一条 {@link ShopPatchPayload}（这项商品的剩余库存 + 我的余额），
 *       成本与目录规模无关，不再像 SDMShop 那样每笔交易全量重推；</li>
 *   <li><b>目录版本</b>：客户端交易时回传版本号，不一致直接拒绝并重新同步，
 *       避免「按旧序号买到别的东西」。</li>
 * </ul>
 *
 * <p>只有服务端会调用这里的写方法；客户端拿到数据后只负责渲染。
 */
public final class ShopManager {
    private static final ShopManager INSTANCE = new ShopManager();
    /** 超过这个条目数就在日志里报告分批同步耗时，便于服主确认性能。 */
    private static final int SLOW_SYNC_LOG_THRESHOLD = 1000;

    private volatile ShopStockManager stock;

    private ShopManager() {
    }

    public static ShopManager get() {
        return INSTANCE;
    }

    public ShopStockManager stock() {
        return stock;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public void onServerStarted(MinecraftServer server) {
        this.stock = ShopStockManager.get(server);
        this.stock.syncWithConfig(ShopConfig.get().entries(), ShopConfig.get().revision());
        ShopDataCache.get().invalidate();
        BetterMinecraftShop.LOGGER.info("[{}] 商店就绪：目录版本 {}，限量商品 {} 项",
                BetterMinecraftShop.MOD_NAME, ShopConfig.get().revision(), countLimited());
    }

    public void onServerStopping(MinecraftServer server) {
        if (stock != null) {
            // 库存挂在世界存档的 SavedData 上，存档流程会负责落盘；这里只是显式标脏，确保一定写入。
            stock.setDirty();
        }
        ShopDataCache.get().invalidate();
        BetterMinecraftShop.LOGGER.info("[{}] 商店已停止", BetterMinecraftShop.MOD_NAME);
    }

    private int countLimited() {
        return (int) ShopConfig.get().entries().stream().filter(entry -> !entry.unlimited()).count();
    }

    /** 库存表尚未就绪时补建（正常流程里服务端启动时已建好）。 */
    private ShopStockManager ensureStock(MinecraftServer server) {
        ShopStockManager current = stock;
        if (current == null) {
            synchronized (this) {
                if (stock == null) {
                    stock = ShopStockManager.get(server);
                    stock.syncWithConfig(ShopConfig.get().entries(), ShopConfig.get().revision());
                }
                current = stock;
            }
        }
        return current;
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    /** 打开商店：把快照分批发给该玩家，并让客户端打开界面。 */
    public void openShop(ServerPlayer player) {
        sendData(player, true);
    }

    /** 只刷新数据（目录变化、界面已打开时用）。 */
    public void refresh(ServerPlayer player) {
        sendData(player, false);
    }

    private void sendData(ServerPlayer player, boolean openScreen) {
        for (ShopDataPayload batch : buildBatches(player, openScreen)) {
            PacketDistributor.sendToPlayer(player, batch);
        }
    }

    /**
     * 组装一份快照的全部批次。返回列表而不是单个包，是为了让「一个快照 = N 个包」这件事
     * 在类型上就成立。
     *
     * @param viewer     接收者；{@code null} 表示控制台诊断（余额按 0 处理，不发送给任何人）
     * @param openScreen 是否是「打开商店」请求（只有第 0 批会带上该标记）
     */
    public List<ShopDataPayload> buildBatches(@Nullable ServerPlayer viewer, boolean openScreen) {
        long start = System.nanoTime();
        ShopStockManager stockManager = stock;
        if (stockManager == null) {
            if (viewer == null) {
                throw new IllegalStateException("stock manager is not ready yet");
            }
            stockManager = ensureStock(viewer.server);
        }
        int revision = ShopConfig.get().revision();
        long balance = viewer == null ? 0L : DeepCoinCurrency.balance(viewer);
        // 「新建」入口只对有权限的玩家可见；界面只做展示，真正的权限校验在服务端保存时再做一次。
        boolean canEdit = viewer != null && ShopItemEditorService.canEdit(viewer);
        List<ShopDataPayload.CategoryData> categoryData = ShopDataCache.get().categories(revision);
        List<ShopItemEntry> entries = ShopConfig.get().entries();

        int batchSize = ShopDataPayload.MAX_BATCH_ENTRIES;
        int batchCount = Math.max(1, (entries.size() + batchSize - 1) / batchSize);
        List<ShopDataPayload> batches = new ArrayList<>(batchCount);
        for (int batch = 0; batch < batchCount; batch++) {
            int from = batch * batchSize;
            int to = Math.min(entries.size(), from + batchSize);
            List<ShopDataPayload.EntryData> chunk = new ArrayList<>(Math.max(1, to - from));
            for (int i = from; i < to; i++) {
                ShopItemEntry entry = entries.get(i);
                chunk.add(new ShopDataPayload.EntryData(
                        entry.id(),
                        entry.categoryId(),
                        entry.side() == ShopSide.SELL,
                        // 缓存里的模板副本：附魔等组件原样过网，
                        // 客户端展示的就是真正会拿到的东西。
                        ShopDataCache.get().template(entry, revision),
                        entry.price(),
                        entry.sellPrice(),
                        stockManager.remaining(entry),
                        entry.customComponents()));
            }
            batches.add(new ShopDataPayload(
                    openScreen && batch == 0,
                    revision,
                    batch == 0 ? balance : 0L,
                    batch == 0 && canEdit,
                    batch,
                    batchCount,
                    entries.size(),
                    batch == 0 ? categoryData : List.of(),
                    List.copyOf(chunk)));
        }

        if (entries.size() >= SLOW_SYNC_LOG_THRESHOLD) {
            long millis = (System.nanoTime() - start) / 1_000_000L;
            BetterMinecraftShop.LOGGER.info("[{}] 向 {} 同步 {} 条商品：{} 批，组装耗时 {} ms",
                    BetterMinecraftShop.MOD_NAME,
                    viewer == null ? "（控制台诊断）" : viewer.getGameProfile().getName(),
                    entries.size(), batchCount, millis);
        }
        return batches;
    }

    /** 交易后的轻量补丁：只同步这一项商品的剩余库存与本人余额。 */
    private void sendPatch(ServerPlayer player, ShopItemEntry entry) {
        ShopStockManager stockManager = ensureStock(player.server);
        PacketDistributor.sendToPlayer(player, new ShopPatchPayload(
                ShopConfig.get().revision(), DeepCoinCurrency.balance(player),
                entry.id(), stockManager.remaining(entry)));
    }

    // ------------------------------------------------------------------
    // 交易
    // ------------------------------------------------------------------

    /** 处理客户端买卖请求：一切输入都当作不可信。 */
    public void handleAction(ServerPlayer player, ShopActionPayload payload) {
        ShopStockManager stockManager = ensureStock(player.server);
        // 目录版本不一致 = 客户端看到的是旧目录，直接拒绝并重新同步，避免「按旧序号买到别的东西」。
        if (payload.revision() != ShopConfig.get().revision()) {
            player.sendSystemMessage(Component.translatable("message." + BetterMinecraftShop.MOD_ID + ".stale_catalogue"));
            refresh(player);
            return;
        }
        Optional<ShopItemEntry> found = ShopConfig.get().byId(payload.entryId());
        if (found.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message." + BetterMinecraftShop.MOD_ID + ".unknown_entry"));
            refresh(player);
            return;
        }
        ShopItemEntry entry = found.get();

        // 买入 / 卖出彻底分离：in/ 目录的条目只能买，out/ 目录的条目只能卖；方向不符直接拒绝。
        boolean wantsBuy = payload.action() == ShopActionPayload.Action.BUY;
        if (wantsBuy != (entry.side() == ShopSide.BUY)) {
            player.sendSystemMessage(Component.translatable(
                    "message." + BetterMinecraftShop.MOD_ID + ".wrong_direction"));
            refresh(player);
            return;
        }

        ShopTransaction.Result result;
        try {
            result = switch (payload.action()) {
                case BUY -> ShopTransaction.buy(player, entry, payload.quantity(), stockManager);
                case SELL -> ShopTransaction.sell(player, entry, payload.quantity(), stockManager);
            };
        } catch (RuntimeException e) {
            // 兜底：交易逻辑内部已做回滚，这里保证任何意外都不会把异常抛到 tick 线程上。
            BetterMinecraftShop.LOGGER.error("[{}] 交易处理异常（玩家 {}，商品 {}）",
                    BetterMinecraftShop.MOD_NAME, player.getGameProfile().getName(), entry.key(), e);
            result = ShopTransaction.Result.fail(
                    Component.translatable("message." + BetterMinecraftShop.MOD_ID + ".transaction_failed"));
        }

        player.sendSystemMessage(result.message());
        // 只把「这一项商品的新库存 + 新余额」回给发起者：
        // 成功时校正客户端显示；失败时同样校正（例如库存被别的玩家买空）。
        sendPatch(player, entry);
    }

    /** 重载配置（{@code /bmcs reload}）。 */
    public void reload(MinecraftServer server) {
        boolean ok = ShopConfig.get().load(server.registryAccess());
        if (ok) {
            ShopDataCache.get().invalidate();
            if (stock != null) {
                stock.syncWithConfig(ShopConfig.get().entries(), ShopConfig.get().revision());
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refresh(player);
        }
    }

    /**
     * 游戏内「新建」保存成功后重载配置：保存者重新打开商店，其余玩家只刷新已有列表。
     *
     * @param saver 刚保存商品的玩家
     * @return 配置是否加载成功（失败时调用方会把原因回给玩家）
     */
    public boolean reloadFromEditor(ServerPlayer saver) {
        MinecraftServer server = saver.server;
        if (server == null) {
            return false;
        }
        boolean ok = ShopConfig.get().load(server.registryAccess());
        if (ok) {
            ShopDataCache.get().invalidate();
            if (stock != null) {
                stock.syncWithConfig(ShopConfig.get().entries(), ShopConfig.get().revision());
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player != saver) {
                refresh(player);
            }
        }
        if (ok) {
            // 编辑器这时正在关闭：紧接着的一包会把商店界面重新打开，玩家看到的就是刚存进去的商品。
            openShop(saver);
        }
        return ok;
    }
}
