"""生成大数据量压力测试配置（默认 10000 条商品）。

因为原版物品注册表只有约 1300 个物品，要凑出 10000 条**互不重复**的商品条目，
每条都在 components 里带一个唯一的 minecraft:custom_name（去重键 = 物品 id + 组件签名，
所以它们属于不同商品；纯物品条目才会按 item 合并）。

用法：
    python gen_stress_config.py <目标 shop_items 目录> [条目数] [--seed 42]

示例（对着开发服的配置目录生成）：
    python gen_stress_config.py ../run/config/better-minecraft-shop/shop_items 10000
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
    target = Path(args[0]) if args else Path(".")
    count = int(args[1]) if len(args) > 1 else 10000
    for extra in sys.argv[1:]:
        if extra.startswith("--seed="):
            seed = int(extra.split("=", 1)[1])
    random.seed(seed)

    target.mkdir(parents=True, exist_ok=True)
    buckets = {cid: [] for cid, _ in CATEGORIES}
    order = [cid for cid, _ in CATEGORIES]
    for index in range(1, count + 1):
        cid = order[(index - 1) % len(order)]
        item = ITEMS[(index * 7) % len(ITEMS)]
        price = random.randint(1, 5000)
        # 名字两种写法各占一半：奇数条用纯字符串（最自然的写法），偶数条用显式文本组件。
        # 这样压力测试同时覆盖「字符串 → 文本组件」的兼容规范化路径。
        name = f"压力测试 #{index}"
        custom_name = name if index % 2 else {"text": name}
        entry = {
            "item": item,
            "price": price,
            "sell_price": price // 3,
            "stock": -1 if index % 4 else max(1, index % 500),
            "max_stack": 1 if item.endswith(("sword", "pickaxe", "bow", "shield", "helmet",
                                             "chestplate")) else 64,
            "components": {
                "minecraft:custom_name": custom_name,
            },
        }
        # 每 10 条再加一行 lore（同样测一遍列表内的字符串规范化）。
        if index % 10 == 0:
            entry["components"]["minecraft:lore"] = [f"第 {index} 号压力测试商品", "带 # 的说明 #tag"]
        # 每 10 条加一次附魔，确保带组件解析路径也被压到（附魔走注册表查询）。
        if index % 10 == 0:
            entry["components"]["minecraft:enchantments"] = {"minecraft:unbreaking": 1 + index % 3}
        buckets[cid].append(entry)

    categories = []
    for cid, icon in CATEGORIES:
        items = buckets[cid]
        if not items:
            continue
        categories.append({"id": cid, "icon": icon, "items": items})

    payload = {"version": 1, "categories": categories}
    out = target / f"stress-{count}.json"
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    total = sum(len(c["items"]) for c in categories)
    print(f"已写出 {out}")
    print(f"  分类 {len(categories)} 个 / 商品 {total} 条 / 文件大小 {out.stat().st_size / 1024:.1f} KB")


if __name__ == "__main__":
    main()
