package com.lyhyy2007.bmcs.util;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 物品堆叠 / 元数据工具。
 *
 * <p>这里集中处理 SDMShop 出问题最多的三件事：
 * <ol>
 *   <li><b>匹配</b>：统一用 {@link ItemStack#isSameItemSameComponents} 做「同物品且同组件」判定，
 *       附魔、改名、耐久、自定义数据全部参与比较，绝不会把普通钻石剑当成锋利 V 钻石剑收走；</li>
 *   <li><b>数量</b>：所有数量计算都在 {@code int}/{@code long} 里显式转换并夹取，
 *       不存在 SDMShop 里 {@code (int)(count * stack.getCount())} 溢出后反向「加物品」的问题；</li>
 *   <li><b>槽位范围</b>：回收只扫描主背包 + 副手，护甲槽（36..39）永不参与，
 *       避免 SDMShop 那种「卖东西把身上装备一起卖掉」的事故。</li>
 * </ol>
 */
public final class ItemStackUtil {
    /** Inventory 里护甲槽的数量（items 36 + armor 4 + offhand 1）。 */
    private static final int ARMOR_SLOTS = 4;

    private ItemStackUtil() {
    }

    /** 主背包槽位数（默认 36）。 */
    public static int mainSlotCount(Player player) {
        return Math.max(0, player.getInventory().getContainerSize() - ARMOR_SLOTS - 1);
    }

    /** 副手槽位下标（默认 40）。 */
    public static int offhandSlot(Player player) {
        return player.getInventory().getContainerSize() - 1;
    }

    /** 该槽位是否允许被交易触碰（主背包 + 副手）。 */
    public static boolean isTradeableSlot(Player player, int index) {
        return index >= 0 && (index < mainSlotCount(player) || index == offhandSlot(player));
    }

    /** 是否与模板完全一致（物品 + 全部 DataComponent）。 */
    public static boolean matches(ItemStack candidate, ItemStack template) {
        return !candidate.isEmpty() && candidate.getCount() > 0
                && ItemStack.isSameItemSameComponents(candidate, template);
    }

    /** 统计玩家可交易槽位里与模板一致的物品总数。 */
    public static int countMatching(Player player, ItemStack template) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            if (!isTradeableSlot(player, index)) {
                continue;
            }
            ItemStack stack = inventory.getItem(index);
            if (matches(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 精确移除 {@code count} 件与模板一致的物品。
     *
     * @return 实际移除数量；调用方可据此判断是否完整移除（不足时应回滚）
     */
    public static int removeMatching(Player player, ItemStack template, int count) {
        if (count <= 0) {
            return 0;
        }
        Inventory inventory = player.getInventory();
        int remaining = count;
        int removed = 0;
        for (int index = 0; index < inventory.getContainerSize() && remaining > 0; index++) {
            if (!isTradeableSlot(player, index)) {
                continue;
            }
            ItemStack stack = inventory.getItem(index);
            if (!matches(stack, template)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            int left = stack.getCount() - take;
            if (left <= 0) {
                inventory.setItem(index, ItemStack.EMPTY);
            } else {
                // 复制后再写回：不直接改共享的 ItemStack 实例。
                ItemStack shrunk = stack.copy();
                shrunk.setCount(left);
                inventory.setItem(index, shrunk);
            }
            remaining -= take;
            removed += take;
        }
        if (removed > 0) {
            syncInventory(player);
        }
        return removed;
    }

    /**
     * 尝试把 {@code count} 件物品塞进玩家背包（走原版 {@link Inventory#add}，因此护甲/副手规则与原生一致）。
     *
     * <p>注意：{@code Inventory#add} 的返回值只表示「塞进去了一部分」，并不代表全部塞进去了；
     * 因此这里用「剩余数量」来判断是否完整放入，剩余的情况由调用方回滚。
     *
     * @return 实际放入数量与是否完整放入
     */
    public static InsertOutcome insert(Player player, ItemStack template, int count) {
        if (count <= 0) {
            return new InsertOutcome(0, true);
        }
        ItemStack toAdd = template.copyWithCount(count);
        int before = toAdd.getCount();
        player.getInventory().add(toAdd);
        int inserted = before - toAdd.getCount();
        if (inserted > 0) {
            syncInventory(player);
        }
        return new InsertOutcome(inserted, inserted >= count);
    }

    /** 通知客户端背包同步（避免客户端显示与服务器不一致，直到下次打开背包才刷新）。 */
    public static void syncInventory(Player player) {
        if (!player.level().isClientSide) {
            player.inventoryMenu.broadcastChanges();
        }
    }

    /** 单次放入结果。 */
    public record InsertOutcome(int inserted, boolean full) {
    }
}
