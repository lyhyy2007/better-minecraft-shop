package com.lyhyy2007.bmcs.shop;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.config.ShopItemEntry;
import com.lyhyy2007.bmcs.config.ShopSide;
import com.lyhyy2007.bmcs.currency.DeepCoinCurrency;
import com.lyhyy2007.bmcs.util.ItemStackUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 买卖交易的核心逻辑（全部在服务端主线程执行）。
 *
 * <p>顺序设计（针对 SDMShop 的具体缺陷）：
 * <ol>
 *   <li>先做数量、余额、库存的<b>完整校验</b>，任何一项不满足就直接返回，不做部分修改；</li>
 *   <li>买入：先把物品放进背包（放不下则把已放入的部分取回），再扣钱、再扣库存；
 *       若扣钱/扣库存抛异常，把物品取回 —— 不会出现「钱扣了东西没给」；</li>
 *   <li>卖出：先数清并<b>真正移除</b>物品，移除成功后才进账（SDMShop 是先给钱再尝试删物品，
 *       删失败也没人管，于是「卖空气换钱」）；</li>
 *   <li>数量在服务端二次校验并夹取，客户端的输入从不被信任。</li>
 * </ol>
 */
public final class ShopTransaction {
    private ShopTransaction() {
    }

    /** 交易结果。 */
    public record Result(boolean success, Component message) {
        public static Result ok(Component message) {
            return new Result(true, message);
        }

        public static Result fail(Component message) {
            return new Result(false, message);
        }
    }

    private static Component msg(String key, Object... args) {
        return Component.translatable("message." + BetterMinecraftShop.MOD_ID + "." + key, args);
    }

    /** 买入（只接受 in/ 目录里的条目）。 */
    public static Result buy(ServerPlayer player, ShopItemEntry entry, int quantity, ShopStockManager stock) {
        if (entry.side() != ShopSide.BUY) {
            return Result.fail(msg("wrong_direction"));
        }
        if (quantity <= 0) {
            return Result.fail(msg("invalid_amount"));
        }
        int limit = entry.tradeLimit();
        if (quantity > limit) {
            return Result.fail(msg("too_many", limit));
        }

        final long total;
        try {
            total = Math.multiplyExact((long) entry.price(), (long) quantity);
        } catch (ArithmeticException e) {
            return Result.fail(msg("invalid_amount"));
        }

        int remaining = stock.remaining(entry);
        if (!entry.unlimited() && remaining < quantity) {
            return Result.fail(msg("out_of_stock", entry.displayNameComponent(), Math.max(0, remaining)));
        }
        if (!DeepCoinCurrency.has(player, total)) {
            return Result.fail(msg("not_enough_money"));
        }

        // 1) 先给货。放不下就整体回滚，绝不「扣钱不给货」。
        ItemStackUtil.InsertOutcome outcome = ItemStackUtil.insert(player, entry.template(), quantity);
        if (!outcome.full()) {
            if (outcome.inserted() > 0) {
                ItemStackUtil.removeMatching(player, entry.template(), outcome.inserted());
            }
            return Result.fail(msg("inventory_full", entry.displayNameComponent()));
        }

        // 2) 扣钱 + 扣库存。理论上不会失败（上面已校验），一旦异常就把货收回。
        try {
            if (!DeepCoinCurrency.withdraw(player, total)) {
                throw new IllegalStateException("withdraw failed after balance check");
            }
            if (!stock.consume(entry, quantity)) {
                DeepCoinCurrency.deposit(player, total);
                throw new IllegalStateException("stock consume failed after stock check");
            }
        } catch (RuntimeException e) {
            ItemStackUtil.removeMatching(player, entry.template(), outcome.inserted());
            BetterMinecraftShop.LOGGER.error("[{}] 买入结算失败，已回滚（玩家 {}，商品 {}）",
                    BetterMinecraftShop.MOD_NAME, player.getGameProfile().getName(), entry.key(), e);
            return Result.fail(msg("transaction_failed"));
        }

        return Result.ok(msg("bought", entry.displayNameComponent(), quantity, DeepCoinCurrency.format(total)));
    }

    /** 卖出（只接受 out/ 目录里的条目）。 */
    public static Result sell(ServerPlayer player, ShopItemEntry entry, int quantity, ShopStockManager stock) {
        if (entry.side() != ShopSide.SELL) {
            return Result.fail(msg("not_sellable", entry.displayNameComponent()));
        }
        if (quantity <= 0) {
            return Result.fail(msg("invalid_amount"));
        }
        if (!entry.sellable()) {
            return Result.fail(msg("not_sellable", entry.displayNameComponent()));
        }
        int limit = entry.tradeLimit();
        if (quantity > limit) {
            return Result.fail(msg("too_many", limit));
        }

        final long total;
        try {
            total = Math.multiplyExact((long) entry.sellPrice(), (long) quantity);
        } catch (ArithmeticException e) {
            return Result.fail(msg("invalid_amount"));
        }

        int owned = ItemStackUtil.countMatching(player, entry.template());
        if (owned < quantity) {
            return Result.fail(msg("not_enough_items", entry.displayNameComponent(), owned, quantity));
        }

        // 1) 先真正移除物品，成功后才给钱。
        int removed = ItemStackUtil.removeMatching(player, entry.template(), quantity);
        if (removed < quantity) {
            if (removed > 0) {
                ItemStackUtil.insert(player, entry.template(), removed);
            }
            return Result.fail(msg("transaction_failed"));
        }

        // 2) 入账 + 回收补货（补货不超过配置上限）。
        try {
            DeepCoinCurrency.deposit(player, total);
            stock.restock(entry, quantity);
        } catch (RuntimeException e) {
            ItemStackUtil.insert(player, entry.template(), removed);
            BetterMinecraftShop.LOGGER.error("[{}] 卖出结算失败，已把物品退回（玩家 {}，商品 {}）",
                    BetterMinecraftShop.MOD_NAME, player.getGameProfile().getName(), entry.key(), e);
            return Result.fail(msg("transaction_failed"));
        }

        return Result.ok(msg("sold", entry.displayNameComponent(), quantity, DeepCoinCurrency.format(total)));
    }
}
