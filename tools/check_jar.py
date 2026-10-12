"""检查构建产物 jar 的关键内容（资源、默认配置、语言键、类文件）。

用法： python check_jar.py build/libs/better-minecraft-shop-1.0.0.jar
"""
import json
import sys
import zipfile

MOD_ID = "better_minecraft_shop"
EXPECTED_CLASSES = [
    "com/lyhyy2007/bmcs/BetterMinecraftShop.class",
    "com/lyhyy2007/bmcs/config/ShopConfig.class",
    "com/lyhyy2007/bmcs/config/ShopConfigLoader.class",
    "com/lyhyy2007/bmcs/config/CategoryPriority.class",
    "com/lyhyy2007/bmcs/client/ShopScreen.class",
    "com/lyhyy2007/bmcs/client/ShopKeyMappings.class",
    "com/lyhyy2007/bmcs/client/ui/VirtualGrid.class",
    "com/lyhyy2007/bmcs/shop/ShopConfigWatcher.class",
    "com/lyhyy2007/bmcs/shop/ShopDataCache.class",
    "com/lyhyy2007/bmcs/network/ShopDataPayload.class",
    "com/lyhyy2007/bmcs/network/ShopPatchPayload.class",
    "com/lyhyy2007/bmcs/util/ItemNameUtil.class",
]
EXPECTED_SHOP_FILES = [
    "01-weapons_tools.json", "02-hardware.json", "03-building.json", "04-stone.json",
    "05-wood.json", "06-material.json", "07-food.json", "08-seeds.json",
]
EXPECTED_LANG_KEYS = [
    f"key.categories.{MOD_ID}", f"key.{MOD_ID}.open_shop",
    f"gui.{MOD_ID}.counter", f"gui.{MOD_ID}.tile.buy", f"gui.{MOD_ID}.tip.item_id",
    f"command.{MOD_ID}.debug.item.header", f"command.{MOD_ID}.debug.config.header",
]

jar = sys.argv[1]
z = zipfile.ZipFile(jar)
names = set(z.namelist())
failures = []


def check(condition, message):
    print(("  [OK]   " if condition else "  [FAIL] ") + message)
    if not condition:
        failures.append(message)


toml = z.read("META-INF/neoforge.mods.toml").decode("utf-8")
lang = json.loads(z.read(f"assets/{MOD_ID}/lang/zh_cn.json").decode("utf-8"))
lang_en = json.loads(z.read(f"assets/{MOD_ID}/lang/en_us.json").decode("utf-8"))
sidebar = json.loads(z.read(f"assets/{MOD_ID}/sidebar_buttons/shop.json").decode("utf-8"))

print("== jar 基本内容 ==")
print("  toml 第一行（ascii-safe）:", ascii(toml.splitlines()[0][:40]))
print("  中文标题（ascii-safe）:", ascii(lang[f"gui.{MOD_ID}.title"]))
check(lang[f"gui.{MOD_ID}.title"] == "\u66f4\u597d\u7684 Minecraft \u5546\u5e97", "jar 内中文资源没有乱码")
check('logoFile="shop_icon.png"' in toml, "toml 声明了 logoFile")
check(f'modId="{MOD_ID}"' in toml, "modId 合法（无连字符）")
check('modId="ftblibrary"' in toml and 'modId="architectury"' in toml, "声明了 FTB Library / Architectury 前置")
check(sidebar["click"] == [f"{MOD_ID}:open_shop"], f"侧边栏 click = {sidebar['click']}")
check(f"assets/{MOD_ID}/textures/shop_icon.png" in names, "侧边栏图标资源在 jar 内")

print("\n== 类文件 ==")
for cls in EXPECTED_CLASSES:
    check(cls in names, f"包含 {cls}")

print("\n== 默认配置（多文件） ==")
for name in EXPECTED_SHOP_FILES:
    path = f"data/{MOD_ID}/shop_items/{name}"
    if path not in names:
        check(False, f"缺少默认配置 {path}")
        continue
    data = json.loads(z.read(path).decode("utf-8"))
    items = [i for c in data.get("categories", []) for i in c.get("items", [])]
    has_display_name = any("display_name" in i for i in items)
    check(not has_display_name and bool(items), f"{name} 合法且不含 display_name（{len(items)} 条）")

print("\n== 语言键 ==")
check(set(lang) == set(lang_en), "zh_cn / en_us 键集合一致")
for key in EXPECTED_LANG_KEYS:
    check(key in lang and key in lang_en, f"语言键存在：{key}")

print()
if failures:
    print(f"共 {len(failures)} 项失败：")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("jar 内容检查全部通过。")
