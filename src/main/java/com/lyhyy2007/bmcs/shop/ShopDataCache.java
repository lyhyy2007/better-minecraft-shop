package com.lyhyy2007.bmcs.shop;

import com.lyhyy2007.bmcs.config.ShopCategory;
import com.lyhyy2007.bmcs.config.ShopConfig;
import com.lyhyy2007.bmcs.config.ShopItemEntry;
import com.lyhyy2007.bmcs.config.ShopSide;
import com.lyhyy2007.bmcs.network.ShopDataPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * 商店快照的内存缓存（仅服务端主线程使用）。
 *
 * <p>大数据量（10000+ 条目）下，每次打开商店都会走一遍「配置 → 网络数据」的转换：
 * 分类图标要复制、每个商品的模板 ItemStack 要复制一份。这些内容在同一个目录版本内
 * 是**完全不变**的（变的只有余额与剩余库存），所以按目录版本缓存：
 * <ul>
 *   <li>分类数据（含图标堆栈）只构造一次；</li>
 *   <li>商品模板只复制一次，之后所有批次、所有玩家共享同一批不可变模板 ——
 *       只有 {@code remaining} 是每次发送时现取的；</li>
 *   <li>目录版本变化（任何一次成功重载）时整体失效重建。</li>
 * </ul>
 *
 * <p>共享的 ItemStack 只被 {@code ItemStack.STREAM_CODEC} 读取，从不被修改；
 * 交易路径永远走 {@code template.copyWithCount(n)}，因此不存在跨玩家串数据的风险。
 */
public final class ShopDataCache {
    private static final ShopDataCache INSTANCE = new ShopDataCache();

    private int revision = -1;
    private List<ShopDataPayload.CategoryData> categories = List.of();
    private Map<UUID, ItemStack> templates = Map.of();

    private ShopDataCache() {
    }

    public static ShopDataCache get() {
        return INSTANCE;
    }

    /** 当前目录版本对应的分类数据（含图标），必要时重建缓存。 */
    public synchronized List<ShopDataPayload.CategoryData> categories(int revision) {
        ensure(revision);
        return categories;
    }

    /** 当前目录版本对应的商品模板（单件、含组件的副本），必要时重建缓存。 */
    public synchronized ItemStack template(ShopItemEntry entry, int revision) {
        ensure(revision);
        ItemStack cached = templates.get(entry.id());
        return cached == null ? entry.template() : cached;
    }

    /** 当前缓存覆盖的目录版本；{@code -1} 表示尚未建立。 */
    public synchronized int cachedRevision() {
        return revision;
    }

    /** 主动失效（服务端停止时调用，避免把上一局的模板留在内存里）。 */
    public synchronized void invalidate() {
        revision = -1;
        categories = List.of();
        templates = Map.of();
    }

    private void ensure(int wanted) {
        if (revision == wanted && !categories.isEmpty()) {
            return;
        }
        List<ShopCategory> source = ShopConfig.get().categories();
        List<ShopDataPayload.CategoryData> built = new ArrayList<>(source.size());
        Map<UUID, ItemStack> builtTemplates = new LinkedHashMap<>();
        for (ShopCategory category : source) {
            built.add(new ShopDataPayload.CategoryData(category.id(), category.displayName(),
                    category.side() == ShopSide.SELL, category.icon().copy(), category.editable()));
            for (ShopItemEntry entry : category.items()) {
                // 网络包里每一项都必须是「1 件」的独立副本：模板本身不参与交易。
                builtTemplates.put(entry.id(), entry.template().copyWithCount(1));
            }
        }
        this.categories = List.copyOf(built);
        this.templates = Map.copyOf(builtTemplates);
        this.revision = wanted;
    }
}
