package com.lyhyy2007.bmcs.currency;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 深索币账户（挂在玩家身上的可变余额）。
 *
 * <p>为什么不用 DataComponent：DataComponent 描述的是 <b>ItemStack</b> 的数据；玩家余额属于实体存档，
 * 在 NeoForge 1.21.1 里对应的是 Data Attachment（{@code AttachmentType}）。附件由
 * {@code AttachmentHolder} 负责序列化，绑在实体的存档里，天然不会出现 SDMShop 那种
 * “自己写世界文件 + 手写 NBT + 非原子写盘”导致的坏档。
 *
 * @param balance 余额，恒在 [0, {@link #MAX_BALANCE}] 区间内
 */
public record DeepCoinAccount(long balance) {
    /** 余额上限：避免溢出，也便于展示。 */
    public static final long MAX_BALANCE = 999_999_999_999L;
    public static final DeepCoinAccount EMPTY = new DeepCoinAccount(0L);

    /** 存档格式由 Codec 描述，字段缺失时按 0 处理（老存档可平滑升级）。 */
    public static final Codec<DeepCoinAccount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("balance").orElse(0L).forGetter(DeepCoinAccount::balance)
    ).apply(instance, DeepCoinAccount::new));

    public DeepCoinAccount {
        balance = clamp(balance);
    }

    public static long clamp(long value) {
        return Math.max(0L, Math.min(MAX_BALANCE, value));
    }

    /** 饱和加法，永不溢出。 */
    public static long saturatingAdd(long left, long right) {
        if (right <= 0) {
            return clamp(left);
        }
        return left > MAX_BALANCE - right ? MAX_BALANCE : left + right;
    }
}
