package com.lyhyy2007.bmcs.currency;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 深索币货币系统（全模组唯一货币）。
 *
 * <p>实现方式：玩家数据附件 {@link #ACCOUNT} + Codec 序列化 + {@code copyOnDeath()}。
 * 全部写操作都在服务端主线程调用（命令 / 网络包处理），不存在跨线程竞态。
 *
 * <p>与 SDMShop 的对比：SDMShop 自身不存钱，完全委托给外部模组 SDM-Economy，
 * 且把「编辑模式」这类状态塞进对方的自定义数据里；本模组自带货币，不引入额外前置。
 */
public final class DeepCoinCurrency {
    /** 货币 id（命令与配置里引用）。 */
    public static final String ID = "deepcoin";
    /** 展示名翻译键，例如「%s 深索币」。 */
    public static final String TRANSLATION_KEY = "currency." + BetterMinecraftShop.MOD_ID + ".deepcoin";

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, BetterMinecraftShop.MOD_ID);

    /** 玩家余额附件。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<DeepCoinAccount>> ACCOUNT =
            ATTACHMENTS.register("deepcoin_account", () -> AttachmentType
                    .builder(() -> DeepCoinAccount.EMPTY)
                    .serialize(DeepCoinAccount.CODEC)
                    // 死亡不掉落：玩家实体被重建时把余额复制到新实体，避免“死一次钱没了”。
                    .copyOnDeath()
                    .build());

    private DeepCoinCurrency() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    // ------------------------------------------------------------------
    // 查询 / 修改（仅服务端调用）
    // ------------------------------------------------------------------

    public static long balance(Player player) {
        return player.getData(ACCOUNT).balance();
    }

    public static void setBalance(Player player, long amount) {
        player.setData(ACCOUNT, new DeepCoinAccount(amount));
    }

    public static boolean has(Player player, long amount) {
        return amount <= 0 || balance(player) >= amount;
    }

    /** 扣款；余额不足时不做任何修改并返回 false。 */
    public static boolean withdraw(Player player, long amount) {
        if (amount <= 0) {
            return false;
        }
        long current = balance(player);
        if (current < amount) {
            return false;
        }
        setBalance(player, current - amount);
        return true;
    }

    /** 入账（饱和加法，不会溢出）。 */
    public static void deposit(Player player, long amount) {
        if (amount <= 0) {
            return;
        }
        setBalance(player, DeepCoinAccount.saturatingAdd(balance(player), amount));
    }

    /**
     * 玩家间转账：先扣后入，任何一步失败都回滚，不会凭空造钱。
     *
     * @return 失败原因，成功返回空
     */
    public static java.util.Optional<Component> transfer(ServerPlayer from, ServerPlayer to, long amount) {
        if (amount <= 0) {
            return java.util.Optional.of(Component.translatable("message." + BetterMinecraftShop.MOD_ID + ".invalid_amount"));
        }
        if (from.getUUID().equals(to.getUUID())) {
            return java.util.Optional.of(Component.translatable("message." + BetterMinecraftShop.MOD_ID + ".pay_self"));
        }
        if (!withdraw(from, amount)) {
            return java.util.Optional.of(Component.translatable("message." + BetterMinecraftShop.MOD_ID + ".not_enough_money"));
        }
        try {
            deposit(to, amount);
        } catch (RuntimeException e) {
            // 目标入账异常：把钱退回去，保持总量守恒。
            deposit(from, amount);
            BetterMinecraftShop.LOGGER.error("[{}] 转账入账失败，已回滚", BetterMinecraftShop.MOD_NAME, e);
            return java.util.Optional.of(Component.translatable("message." + BetterMinecraftShop.MOD_ID + ".transaction_failed"));
        }
        return java.util.Optional.empty();
    }

    /** 形如「1,234 深索币」的展示文本。 */
    public static Component format(long amount) {
        return Component.translatable(TRANSLATION_KEY, String.format(Locale.ROOT, "%,d", amount));
    }
}
