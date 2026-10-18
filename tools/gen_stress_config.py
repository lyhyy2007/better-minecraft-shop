"""生成大数据量压力测试配置（默认 10000 条商品）。

因为原版物品注册表只有约 1300 个物品，要凑出 10000 条**互不重复**的商品条目，
每条都在 components 里带一个唯一的附魔组合（components 只支持附魔；去重键 = 物品 id + 附魔签名，
所以它们属于不同商品；纯物品条目才会按 item 合并）。

1.0.5 起买入与卖出分目录：
    in/*.json   只写 price（+ stock），代表「玩家向商店买入」
    out/*.json  只写 sell_price，代表「玩家把物品卖给商店」

用法：
    python gen_stress_config.py <目标 in 或 out 目录> [条目数] [--side=buy|sell] [--seed=42]

示例（对着开发服的配置目录生成）：
    python gen_stress_config.py ../run/config/better-minecraft-shop/in 10000 --side=buy
    python gen_stress_config.py ../run/config/better-minecraft-shop/out 10000 --side=sell
"""
import json
import random
import sys
from pathlib import Path

ITEMS = [
    "minecraft:stone", "minecraft:cobblestone", "minecraft:dirt", "minecraft:gravel",
    "minecraft:sand", "minecraft:sandstone", "minecraft:glass", "minecraft:obsidian",
    "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:oak_planks",
    "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:copper_ingot",
    "minecraft:netherite_ingot", "minecraft:coal", "minecraft:redstone", "minecraft:lapis_lazuli",
    "minecraft:diamond", "minecraft:emerald", "minecraft:quartz", "minecraft:amethyst_shard",
    "minecraft:apple", "minecraft:bread", "minecraft:cooked_beef", "minecraft:cooked_chicken",
    "minecraft:golden_apple", "minecraft:carrot", "minecraft:potato", "minecraft:wheat",
    "minecraft:wheat_seeds", "minecraft:beetroot_seeds", "minecraft:melon_seeds",
    "minecraft:iron_pickaxe", "minecraft:iron_sword", "minecraft:bow", "minecraft:arrow",
    "minecraft:shield", "minecraft:diamond_sword", "minecraft:diamond_helmet",
    "minecraft:diamond_chestplate", "minecraft:torch", "minecraft:ladder", "minecraft:bricks",
    "minecraft:stick", "minecraft:ender_pearl", "minecraft:experience_bottle",
    "minecraft:blaze_rod", "minecraft:gunpowder", "minecraft:string", "minecraft:leather",
    "minecraft:book", "minecraft:paper", "minecraft:sugar", "minecraft:glowstone_dust",
    "minecraft:slime_ball", "minecraft:bone", "minecraft:feather", "minecraft:flint",
]

CATEGORIES = [
    ("weapons_tools", "minecraft:diamond_sword"),
    ("hardware", "minecraft:iron_ingot"),
    ("building", "minecraft:glass"),
    ("stone", "minecraft:stone"),
    ("wood", "minecraft:oak_log"),
    ("material", "minecraft:diamond"),
    ("food", "minecraft:bread"),
    ("seeds", "minecraft:wheat_seeds"),
]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    seed = 42
    side = "buy"
    target = Path(args[0]) if args else Path(".")
    count = int(args[1]) if len(args) > 1 else 10000
    for extra in sys.argv[1:]:
        if extra.startswith("--seed="):
            seed = int(extra.split("=", 1)[1])
        elif extra.startswith("--side="):
            side = extra.split("=", 1)[1].lower()
    if side not in ("buy", "sell"):
        raise SystemExit("--side 只能是 buy 或 sell")
    random.seed(seed)

    target.mkdir(parents=True, exist_ok=True)
    buckets = {cid: [] for cid, _ in CATEGORIES}
    order = [cid for cid, _ in CATEGORIES]
    for index in range(1, count + 1):
        cid = order[(index - 1) % len(order)]
        item = ITEMS[(index * 7) % len(ITEMS)]
        price = random.randint(1, 5000)
        # 附魔是唯一可用的「商品身份」区分手段（components 只认附魔），
        # 用两级等级组合凑出 255×255 = 65025 个互不相同的商品。
        entry = {
            "item": item,
            "components": {
                "minecraft:enchantments": {
                    "minecraft:sharpness": 1 + index % 255,
                    "minecraft:unbreaking": 1 + (index // 255) % 255,
                }
            },
        }
        if side == "buy":
            entry["price"] = price
            entry["stock"] = -1 if index % 4 else max(1, index % 500)
        else:
            # 卖出只认 sell_price；给一个低于常见买入价的回收价。
            entry["sell_price"] = max(1, price // 3)
        buckets[cid].append(entry)

    categories = []
    for cid, icon in CATEGORIES:
        items = buckets[cid]
        if not items:
            continue
        categories.append({"id": cid, "icon": icon, "items": items})

    payload = {"version": 2, "categories": categories}
    out = target / f"stress-{side}-{count}.json"
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    total = sum(len(c["items"]) for c in categories)
    print(f"已写出 {out}（side={side}）")
    print(f"  分类 {len(categories)} 个 / 商品 {total} 条 / 文件大小 {out.stat().st_size / 1024:.1f} KB")


if __name__ == "__main__":
    main()
