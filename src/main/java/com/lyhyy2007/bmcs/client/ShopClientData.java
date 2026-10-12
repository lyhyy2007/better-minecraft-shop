package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.network.ShopDataPayload;
import com.lyhyy2007.bmcs.network.ShopPatchPayload;
import com.lyhyy2007.bmcs.util.ItemNameUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;

/**
 * 客户端商店快照（只读缓存，仅在客户端主线程访问）。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>分片装配</b>：服务端按 500 条一批推送，这里累积分片，收齐最后一片才装配出完整快照 ——
 *       服务端只重载、不增量时不会出现「半个目录」被渲染出来；</li>
 *   <li><b>增量补丁</b>：交易后只收到一条 {@link ShopPatchPayload}，就地替换对应商品的剩余库存；</li>
 *   <li><b>过滤缓存</b>：分类 + 搜索的过滤结果缓存起来，10000 条数据下每帧滚动不再重复扫描；
 *       搜索文本（本地化名 + 物品注册名）在装配时预计算一次，按键时只做子串匹配。</li>
 * </ol>
 *
 * <p>服务端每次推全量都会整体替换，不保留跨包的增量状态 —— 也就没有 SDMShop 那种
 * 「静态 CLIENT 列表 + 静态选中项 + 静态搜索结果」在单人存档里被服务端逻辑读到的问题。
 */
public final class ShopClientData {
    /** 装配中的分片（只在客户端主线程访问）。 */
    private static final List<ShopDataPayload.EntryData> pendingEntries = new ArrayList<>();
    private static List<ShopDataPayload.CategoryData> pendingCategories = List.of();
    private static int pendingRevision = -1;
    private static int pendingBatches;
    private static int pendingTotal;
    private static int pendingReceived;
    private static long pendingBalance;
    private static boolean pendingOpen;
    private static boolean accumulating;

    private static ShopDataPayload snapshot;
    private static String[] searchKeys;
    private static List<ShopDataPayload.EntryData> filteredCache;
    private static String filteredCategory = "\u0000";
    private static String filteredSearch = "\u0000";

    private static String categoryId = "";
    private static String search = "";
    private static UUID selectedId;
    private static int quantity = 1;
    private static Component status = Component.empty();

    private ShopClientData() {
    }

    // ------------------------------------------------------------------
    // 分片装配
    // ------------------------------------------------------------------

    /**
     * 收下一片快照。
     *
     * @return 是否已经收齐并装配出完整快照
     */
    public static boolean accept(ShopDataPayload payload) {
        if (payload.batchIndex() == 0) {
            // 新的全量同步开始：丢弃上一次没装配完的残片。
            pendingEntries.clear();
            pendingCategories = payload.categories();
            pendingRevision = payload.revision();
            pendingBatches = payload.batchCount();
            pendingTotal = payload.totalEntries();
            pendingBalance = payload.balance();
            pendingOpen = payload.openScreen();
            pendingReceived = 0;
            accumulating = true;
        } else if (!accumulating || payload.revision() != pendingRevision) {
            BetterMinecraftShop.LOGGER.warn("[{}] 收到无法归属的商店分片（第 {} 片，版本 {}/{}），已忽略",
                    BetterMinecraftShop.MOD_NAME, payload.batchIndex(), payload.revision(), pendingRevision);
            return false;
        }

        pendingEntries.addAll(payload.entries());
        pendingReceived++;
        if (!payload.last()) {
            return false;
        }

        if (pendingReceived != pendingBatches || pendingEntries.size() != pendingTotal) {
            BetterMinecraftShop.LOGGER.warn("[{}] 商店快照分片不完整（收到 {}/{} 片，{} / {} 条），已丢弃并等待下一次同步",
                    BetterMinecraftShop.MOD_NAME, pendingReceived, pendingBatches, pendingEntries.size(), pendingTotal);
            accumulating = false;
            return false;
        }

        // 装配完成的快照把分片字段归一（batchCount=1、batchIndex=0），
        // 界面层只看到「一份完整目录」，不需要知道分片细节。
        install(new ShopDataPayload(pendingOpen, pendingRevision, pendingBalance, 0, 1, pendingTotal,
                pendingCategories, List.copyOf(pendingEntries)));
        accumulating = false;
        return true;
    }

    private static void install(ShopDataPayload assembled) {
        snapshot = assembled;
        rebuildSearchIndex();

        // 选中的分类若已不存在（配置被改过），回到「全部」。
        if (!categoryId.isEmpty()
                && assembled.categories().stream().noneMatch(category -> category.id().equals(categoryId))) {
            categoryId = "";
        }
        // 选中的商品若已下架，清除选择。
        if (selectedId != null && assembled.entries().stream().noneMatch(entry -> entry.id().equals(selectedId))) {
            selectedId = null;
        }
    }

    /** 交易后的增量补丁：替换一条商品的剩余库存与本人余额。 */
    public static boolean applyPatch(ShopPatchPayload patch) {
        ShopDataPayload current = snapshot;
        if (current == null) {
            return false;
        }
        if (patch.revision() != current.revision()) {
            // 目录已经变了：补丁按旧版本给的数据不可信，等下一次全量同步。
            BetterMinecraftShop.LOGGER.debug("[{}] 忽略过期补丁（版本 {}/{}）",
                    BetterMinecraftShop.MOD_NAME, patch.revision(), current.revision());
            return false;
        }
        List<ShopDataPayload.EntryData> updated = new ArrayList<>(current.entries());
        boolean found = false;
        for (int i = 0; i < updated.size(); i++) {
            ShopDataPayload.EntryData entry = updated.get(i);
            if (entry.id().equals(patch.entryId())) {
                updated.set(i, new ShopDataPayload.EntryData(entry.id(), entry.categoryId(), entry.template(),
                        entry.price(), entry.sellPrice(), patch.remaining(), entry.maxTrade(),
                        entry.customComponents()));
                found = true;
                break;
            }
        }
        // 库存不参与过滤，因此过滤缓存无需失效；余额照常更新。
        snapshot = new ShopDataPayload(current.openScreen(), current.revision(), patch.balance(), 0, 1,
                current.totalEntries(), current.categories(), List.copyOf(updated));
        return found;
    }

    /** 关闭界面时清掉快照，避免把 10000 条商品常驻内存（下次打开重新请求）。 */
    public static void clear() {
        snapshot = null;
        searchKeys = null;
        filteredCache = null;
        filteredCategory = "\u0000";
        filteredSearch = "\u0000";
        pendingEntries.clear();
        pendingCategories = List.of();
        pendingRevision = -1;
        pendingBatches = 0;
        pendingTotal = 0;
        pendingReceived = 0;
        pendingBalance = 0L;
        pendingOpen = false;
        accumulating = false;
        selectedId = null;
        search = "";
        status = Component.empty();
    }

    /** 本次快照是否由「打开商店」请求触发。 */
    public static boolean openRequested() {
        return snapshot != null && snapshot.openScreen();
    }

    /** 是否正在等待后续分片（决定是否继续接收 openScreen=false 的后续片）。 */
    public static boolean accumulating() {
        return accumulating;
    }

    private static void rebuildSearchIndex() {
        List<ShopDataPayload.EntryData> entries = snapshot.entries();
        String[] keys = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            ShopDataPayload.EntryData entry = entries.get(i);
            // 本地化名（跟随客户端语言）+ 注册名（方便按 id 搜），都转小写。
            keys[i] = (ItemNameUtil.plainText(entry.template()) + " "
                    + entry.template().getItem().getDescriptionId()).toLowerCase(Locale.ROOT);
        }
        searchKeys = keys;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    public static ShopDataPayload snapshot() {
        return snapshot;
    }

    public static boolean hasData() {
        return snapshot != null;
    }

    public static int revision() {
        return snapshot == null ? -1 : snapshot.revision();
    }

    public static long balance() {
        return snapshot == null ? 0L : snapshot.balance();
    }

    public static List<ShopDataPayload.EntryData> entries() {
        return snapshot == null ? List.of() : snapshot.entries();
    }

    public static List<ShopDataPayload.CategoryData> categories() {
        return snapshot == null ? List.of() : snapshot.categories();
    }

    /** 当前分类 + 搜索词过滤后的商品列表（带缓存）。 */
    public static List<ShopDataPayload.EntryData> filtered() {
        ShopDataPayload current = snapshot;
        if (current == null) {
            return List.of();
        }
        if (filteredCache != null && filteredCategory.equals(categoryId) && filteredSearch.equals(search)) {
            return filteredCache;
        }
        List<ShopDataPayload.EntryData> all = current.entries();
        String[] keys = searchKeys;
        List<ShopDataPayload.EntryData> result = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            ShopDataPayload.EntryData entry = all.get(i);
            if (!categoryId.isEmpty() && !entry.categoryId().equals(categoryId)) {
                continue;
            }
            if (!search.isEmpty()) {
                if (keys == null || i >= keys.length || !keys[i].contains(search)) {
                    continue;
                }
            }
            result.add(entry);
        }
        filteredCache = List.copyOf(result);
        filteredCategory = categoryId;
        filteredSearch = search;
        return filteredCache;
    }

    public static String categoryId() {
        return categoryId;
    }

    public static void setCategoryId(String id) {
        String value = id == null ? "" : id;
        if (!value.equals(categoryId)) {
            categoryId = value;
            filteredCache = null;
        }
    }

    public static String search() {
        return search;
    }

    public static void setSearch(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals(search)) {
            search = normalized;
            filteredCache = null;
        }
    }

    public static Optional<ShopDataPayload.EntryData> selected() {
        if (selectedId == null) {
            return Optional.empty();
        }
        return entries().stream().filter(entry -> entry.id().equals(selectedId)).findFirst();
    }

    public static UUID selectedId() {
        return selectedId;
    }

    public static void select(UUID id) {
        selectedId = id;
    }

    public static int quantity() {
        return Math.max(1, quantity);
    }

    public static void setQuantity(int value) {
        quantity = Math.max(1, Math.min(4096, value));
    }

    public static Component status() {
        return status;
    }

    public static void setStatus(Component value) {
        status = value == null ? Component.empty() : value;
    }
}
