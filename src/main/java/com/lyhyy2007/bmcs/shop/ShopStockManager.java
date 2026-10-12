package com.lyhyy2007.bmcs.shop;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.config.ShopItemEntry;
import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 限量库存（全局，世界级持久化）。
 *
 * <p>持久化方式：原版 {@link SavedData}（世界存档 data 目录，由存档管线负责原子写入），
 * 内容用 {@link Codec} 编解码成 NBT —— 不手写世界文件，也不做「每笔交易全量存盘」。
 *
 * <p>语义（修正 SDMShop 的库存缺陷）：
 * <ul>
 *   <li>不限量商品（{@code stock: -1}）不占任何持久化状态，永远可买；</li>
 *   <li>限量商品的剩余量存在这里，<b>买入才扣减</b>；卖出是「回收」，会把剩余量补回去，
 *       但绝不超过配置的初始上限（SDMShop 是买、卖都扣同一个计数器，越卖越少）；</li>
 *   <li>缺键时的默认值是配置里的 {@code stock}，而不是 0 —— 不会因为字段缺失导致商品从商店消失。</li>
 * </ul>
 *
 * <p>关于「配置上限」与「管理员设定」的取舍（本机实测发现的问题）：
 * 早期版本每次重载配置都会把剩余库存夹到 {@code min(现有值, 配置初始值)}，
 * 于是 {@code /bmcs reload} 会把管理员刚设好的 {@code stock set weapons_tools.1 5}（配置里是 4）
 * 悄悄改回 4。现在改为记录「上次看到的配置初始值」：
 * <ul>
 *   <li>配置里的上限<b>没变</b> → 保留当前剩余量（管理员设定不会被重载吃掉）；</li>
 *   <li>配置里的上限<b>变了</b> → 夹到新上限（服主把上限从 100 调到 10 时立刻生效）；</li>
 *   <li>旧存档（只有剩余量、没有上限记录）→ 保留剩余量，不夹取，避免升级时把数据改小；</li>
 *   <li>回收补货始终不超过配置上限（这一条不受管理员设定影响）。</li>
 * </ul>
 */
public final class ShopStockManager extends SavedData {
    public static final int DATA_VERSION = 2;
    private static final String DATA_NAME = BetterMinecraftShop.MOD_ID + "_stock";
    private static final String KEY_VERSION = "data_version";
    private static final String KEY_REVISION = "catalogue_revision";
    private static final String KEY_STOCK = "stock";
    private static final String KEY_INITIAL = "initial";
    private static final Codec<Map<String, Integer>> MAP_CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT);

    /** 商品键 → 剩余库存。 */
    private final Map<String, Integer> remaining = new HashMap<>();
    /** 商品键 → 上次同步时看到的配置初始值（用来区分「配置改了上限」和「管理员手动改过」）。 */
    private final Map<String, Integer> configuredInitial = new HashMap<>();
    private int catalogueRevision = -1;

    public static ShopStockManager get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(ShopStockManager::new, ShopStockManager::load), DATA_NAME);
    }

    public static ShopStockManager load(CompoundTag tag, HolderLookup.Provider provider) {
        ShopStockManager data = new ShopStockManager();
        data.catalogueRevision = tag.getInt(KEY_REVISION);
        readMap(tag, KEY_STOCK, data.remaining);
        readMap(tag, KEY_INITIAL, data.configuredInitial);
        return data;
    }

    private static void readMap(CompoundTag tag, String key, Map<String, Integer> target) {
        if (!tag.contains(key)) {
            return;
        }
        MAP_CODEC.parse(NbtOps.INSTANCE, tag.get(key))
                .resultOrPartial(error -> BetterMinecraftShop.LOGGER.error(
                        "[{}] 库存数据 {} 解析失败，将按配置初始值处理：{}",
                        BetterMinecraftShop.MOD_NAME, key, error))
                .ifPresent(map -> map.forEach((entryKey, value) -> {
                    if (entryKey != null && value != null && value >= 0) {
                        target.put(entryKey, value);
                    }
                }));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt(KEY_VERSION, DATA_VERSION);
        tag.putInt(KEY_REVISION, catalogueRevision);
        writeMap(tag, KEY_STOCK, remaining);
        writeMap(tag, KEY_INITIAL, configuredInitial);
        return tag;
    }

    private static void writeMap(CompoundTag tag, String key, Map<String, Integer> source) {
        MAP_CODEC.encodeStart(NbtOps.INSTANCE, Map.copyOf(source))
                .resultOrPartial(error -> BetterMinecraftShop.LOGGER.error(
                        "[{}] 库存数据 {} 编码失败：{}", BetterMinecraftShop.MOD_NAME, key, error))
                .ifPresent(encoded -> tag.put(key, encoded));
    }

    // ------------------------------------------------------------------
    // 业务方法（仅在服务端主线程调用）
    // ------------------------------------------------------------------

    /** 按配置对齐库存表：补齐缺失键、丢弃已不在配置里的键，并只在配置上限变化时收紧剩余量。 */
    public void syncWithConfig(List<ShopItemEntry> entries, int revision) {
        Map<String, Integer> freshRemaining = new HashMap<>();
        Map<String, Integer> freshInitial = new HashMap<>();
        int tightened = 0;
        for (ShopItemEntry entry : entries) {
            if (entry.unlimited()) {
                continue;
            }
            String key = entry.key();
            int configured = entry.initialStock();
            Integer stored = remaining.get(key);
            Integer previousInitial = configuredInitial.get(key);

            int value;
            if (stored == null) {
                value = configured;
            } else if (previousInitial == null || previousInitial == configured) {
                // 旧存档（没有上限记录）或上限没变：保留当前剩余量。
                value = Math.max(0, stored);
            } else {
                // 服主改了上限：按新上限收紧。
                value = Math.min(Math.max(0, stored), configured);
                if (value != stored) {
                    tightened++;
                }
            }
            freshRemaining.put(key, value);
            freshInitial.put(key, configured);
        }
        remaining.clear();
        remaining.putAll(freshRemaining);
        configuredInitial.clear();
        configuredInitial.putAll(freshInitial);
        catalogueRevision = revision;
        setDirty();
        if (tightened > 0) {
            BetterMinecraftShop.LOGGER.info("[{}] 配置上限变化，已收紧 {} 项限量商品的剩余库存",
                    BetterMinecraftShop.MOD_NAME, tightened);
        }
    }

    /** 剩余库存；{@link ShopItemEntry#UNLIMITED} 表示不限量。 */
    public int remaining(ShopItemEntry entry) {
        if (entry.unlimited()) {
            return ShopItemEntry.UNLIMITED;
        }
        return remaining.getOrDefault(entry.key(), entry.initialStock());
    }

    /** 尝试扣减库存；不限量直接成功。返回 false 表示库存不足（不做任何修改）。 */
    public boolean consume(ShopItemEntry entry, int count) {
        if (entry.unlimited() || count <= 0) {
            return true;
        }
        int current = remaining(entry);
        if (current < count) {
            return false;
        }
        remaining.put(entry.key(), current - count);
        setDirty();
        return true;
    }

    /**
     * 回收补货：补到配置上限为止。
     *
     * <p>上限取 {@code max(配置初始值, 当前剩余量)}：管理员用 {@code stock set} 临时放量到上限之上时，
     * 玩家卖回商品不应该把这次放量「冲掉」（那会变成一个很莫名的副作用）。
     */
    public void restock(ShopItemEntry entry, int count) {
        if (entry.unlimited() || count <= 0) {
            return;
        }
        int current = remaining(entry);
        int cap = Math.max(entry.initialStock(), current);
        int updated = Math.min(cap, current + count);
        if (updated != current) {
            remaining.put(entry.key(), updated);
            setDirty();
        }
    }

    /**
     * 管理员直接设定库存（{@code -1} 表示不限量不写入）。
     * 允许高于配置上限（临时放量），并且会一直保留到配置上限本身被改动为止。
     */
    public void set(ShopItemEntry entry, int value) {
        if (entry.unlimited()) {
            return;
        }
        remaining.put(entry.key(), Math.max(0, value));
        configuredInitial.put(entry.key(), entry.initialStock());
        setDirty();
    }

    /** 重置为配置里的初始值。 */
    public void reset(ShopItemEntry entry) {
        if (entry.unlimited()) {
            return;
        }
        remaining.put(entry.key(), entry.initialStock());
        configuredInitial.put(entry.key(), entry.initialStock());
        setDirty();
    }

    public int catalogueRevision() {
        return catalogueRevision;
    }
}
