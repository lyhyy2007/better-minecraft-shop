"""检查构建产物 jar 的关键内容（资源、语言键、类文件、版本号）。

用法： python check_jar.py build/libs/better-minecraft-shop-1.0.5.jar
"""
import json
import sys
import zipfile

MOD_ID = "better_minecraft_shop"
MOD_VERSION = "1.0.5"
EXPECTED_CLASSES = [
    "com/lyhyy2007/bmcs/BetterMinecraftShop.class",
    "com/lyhyy2007/bmcs/config/ShopConfig.class",
    "com/lyhyy2007/bmcs/config/ShopConfigLoader.class",
    "com/lyhyy2007/bmcs/config/CategoryPriority.class",
    "com/lyhyy2007/bmcs/config/ShopItemEntry.class",
    "com/lyhyy2007/bmcs/config/ShopItemWriter.class",
    "com/lyhyy2007/bmcs/config/ShopCategoryWriter.class",
    "com/lyhyy2007/bmcs/config/CategoryFileIO.class",
    "com/lyhyy2007/bmcs/config/ShopCategory.class",
    "com/lyhyy2007/bmcs/client/ShopScreen.class",
    "com/lyhyy2007/bmcs/client/ShopItemEditorScreen.class",
    "com/lyhyy2007/bmcs/client/ShopCategoryEditorScreen.class",
    "com/lyhyy2007/bmcs/client/IconPickerScreen.class",
    "com/lyhyy2007/bmcs/client/ShopConfirmScreen.class",
    "com/lyhyy2007/bmcs/client/EditorResultSink.class",
    "com/lyhyy2007/bmcs/client/ShopKeyMappings.class",
    "com/lyhyy2007/bmcs/client/ui/VirtualGrid.class",
    "com/lyhyy2007/bmcs/shop/ShopConfigWatcher.class",
    "com/lyhyy2007/bmcs/shop/ShopDataCache.class",
    "com/lyhyy2007/bmcs/shop/ShopItemEditorService.class",
    "com/lyhyy2007/bmcs/network/ShopDataPayload.class",
    "com/lyhyy2007/bmcs/network/ShopPatchPayload.class",
    "com/lyhyy2007/bmcs/network/ShopEditorSavePayload.class",
    "com/lyhyy2007/bmcs/network/ShopEditorResultPayload.class",
    "com/lyhyy2007/bmcs/network/ShopCategoryCreatePayload.class",
    "com/lyhyy2007/bmcs/network/ShopCategoryUpdatePayload.class",
    "com/lyhyy2007/bmcs/network/ShopItemDeletePayload.class",
    "com/lyhyy2007/bmcs/util/ItemNameUtil.class",
]
# R2：1.0.5 起 jar 里不再携带任何默认商店配置
FORBIDDEN_PREFIXES = (
    f"data/{MOD_ID}/shop_items/",
    f"data/{MOD_ID}/in/",
    f"data/{MOD_ID}/out/",
)
EXPECTED_LANG_KEYS = [
    f"key.categories.{MOD_ID}", f"key.{MOD_ID}.open_shop",
    f"gui.{MOD_ID}.counter", f"gui.{MOD_ID}.tile.buy", f"gui.{MOD_ID}.tip.item_id",
    f"gui.{MOD_ID}.editor.open", f"gui.{MOD_ID}.editor.title.buy",
    f"gui.{MOD_ID}.editor.title.sell", f"gui.{MOD_ID}.editor.save",
    f"gui.{MOD_ID}.editor.cancel", f"gui.{MOD_ID}.editor.price",
    f"gui.{MOD_ID}.editor.stock", f"gui.{MOD_ID}.editor.pick",
    f"gui.{MOD_ID}.editor.open_item", f"gui.{MOD_ID}.editor.need_category",
    f"gui.{MOD_ID}.edit.button", f"gui.{MOD_ID}.edit.tip",
    f"gui.{MOD_ID}.icon.title", f"gui.{MOD_ID}.icon.search",
    f"gui.{MOD_ID}.category.title.new", f"gui.{MOD_ID}.category.name",
    f"gui.{MOD_ID}.category.icon", f"gui.{MOD_ID}.confirm.delete",
    f"message.{MOD_ID}.editor.saved", f"message.{MOD_ID}.editor.no_permission",
    f"message.{MOD_ID}.category.created", f"message.{MOD_ID}.category.updated",
    f"message.{MOD_ID}.item.deleted",
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
check(f'version="{MOD_VERSION}"' in toml, f"mods.toml 版本 = {MOD_VERSION}")
check('logoFile="shop_icon.png"' in toml, "toml 声明了 logoFile")
check(f'modId="{MOD_ID}"' in toml, "modId 合法（无连字符）")
check('modId="ftblibrary"' in toml and 'modId="architectury"' in toml, "声明了 FTB Library / Architectury 前置")
check(sidebar["click"] == [f"{MOD_ID}:open_shop"], f"侧边栏 click = {sidebar['click']}")
check(f"assets/{MOD_ID}/textures/shop_icon.png" in names, "侧边栏图标资源在 jar 内")

print("\n== 类文件 ==")
for cls in EXPECTED_CLASSES:
    check(cls in names, f"包含 {cls}")

print("\n== 默认配置（1.0.5 起不再打包） ==")
shipped = sorted(n for n in names if n.startswith(FORBIDDEN_PREFIXES) and n.endswith(".json"))
check(not shipped, "jar 内没有任何默认商店配置（首启只建空的 in/ 与 out/）")
for name in shipped[:8]:
    print(f"    多余：{name}")

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
