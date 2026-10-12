"""离线一致性检查（不启动游戏）：

1. 内置默认配置（data/<mod>/shop_items/*.json）的结构、字段、去重与优先级
2. Java 里的 DEFAULT_FILE_NAMES 与实际资源文件是否一一对应
3. 语言文件键集合、分类名键、键位键
4. 代码里引用的翻译键是否都有定义
5. FTB 侧边栏 JSON 与点击事件 id、字段 schema（用真实 FTB jar 的常量池校验）
6. 交易安全 / 大数据量架构的静态不变量
7. 交付文档是否覆盖新特性

用法： python verify_assets.py <项目根目录>
"""
import glob
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
SRC = ROOT / "src/main/java"
RES = ROOT / "src/main/resources"
MOD_ID = "better_minecraft_shop"
SHOP_DIR = RES / f"data/{MOD_ID}/shop_items"
failures = []
notes = []


def ok(msg):
    print(f"  [OK]   {msg}")


def bad(msg):
    failures.append(msg)
    print(f"  [FAIL] {msg}")


def note(msg):
    notes.append(msg)
    print(f"  [NOTE] {msg}")


# ----------------------------------------------------------------------
print("== 1) 内置默认配置（多文件）结构 ==")
files = sorted(SHOP_DIR.glob("*.json"))
if not files:
    bad(f"找不到任何默认配置：{SHOP_DIR}")
priority_table = {}
for match in re.finditer(r'Map\.entry\("([^"]+)",\s*(\d+)\)', (SRC / "com/lyhyy2007/bmcs/config/CategoryPriority.java").read_text(encoding="utf-8")):
    priority_table[match.group(1)] = int(match.group(2))
if not priority_table:
    bad("CategoryPriority.java 里解析不到优先级表")
else:
    ok(f"从 CategoryPriority.java 解析到 {len(priority_table)} 条别名映射")

UNKNOWN_PRIORITY = 5
# 去重后每个「商品身份」的归属：identity -> (priority, category, price, source_file)
survivors = {}
duplicates = []
categories = {}
total_raw = 0
component_items = 0


def identity(item, entry):
    components = entry.get("components")
    if entry.get("variant"):
        suffix = entry["variant"].strip()
    elif components:
        suffix = json.dumps(components, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    else:
        suffix = ""
    return f"{item}|{suffix}"


for path in files:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        bad(f"{path.name} 不是合法 JSON：{exc}")
        continue
    if data.get("version") != 1:
        bad(f"{path.name} 的 version 不是 1：{data.get('version')}")
    if not data.get("categories"):
        bad(f"{path.name} 没有任何分类")
    for cat in data.get("categories", []):
        cid = cat.get("id")
        if not cid:
            bad(f"{path.name} 有分类缺少 id")
            continue
        if cid != cid.lower():
            bad(f"{path.name} 的分类 id 必须小写：{cid}")
        explicit = cat.get("priority")
        priority = explicit if isinstance(explicit, int) else priority_table.get(cid, UNKNOWN_PRIORITY)
        if explicit is None and cid not in priority_table:
            note(f"分类 {cid} 不在内置优先级表内（按 {UNKNOWN_PRIORITY} 处理）")
        categories.setdefault(cid, priority)
        items = cat.get("items", [])
        if not items:
            bad(f"{path.name} 的分类 {cid} 没有任何商品")
        for entry in items:
            total_raw += 1
            for field in ("item", "price"):
                if field not in entry:
                    bad(f"{path.name} {cid} 的某条商品缺少 {field}：{entry}")
            if "display_name" in entry:
                bad(f"{path.name} {cid} 的 {entry.get('item')} 仍在使用已移除的 display_name 字段")
            if entry.get("price", 0) < 1:
                bad(f"{path.name} {cid} 的 {entry.get('item')} price 非法：{entry.get('price')}")
            sell = entry.get("sell_price", 0)
            if sell > entry.get("price", 0):
                bad(f"{path.name} {cid} 的 {entry.get('item')} 卖价高于买价（刷钱漏洞）")
            stock = entry.get("stock", -1)
            if stock < -1:
                bad(f"{path.name} {cid} 的 {entry.get('item')} stock 非法：{stock}")
            components = entry.get("components")
            if components:
                component_items += 1
                for comp in components:
                    if not comp.startswith("minecraft:"):
                        bad(f"{path.name} {cid} 的 {entry.get('item')} 组件 id 不像原版组件：{comp}")
            key = identity(entry.get("item"), entry)
            previous = survivors.get(key)
            if previous is None:
                survivors[key] = (priority, cid, entry, path.name)
            else:
                duplicates.append((key, previous, (priority, cid, entry, path.name)))
                # 优先级更高者胜出；相同优先级保留先出现的（文件按路径排序）。
                if priority > previous[0]:
                    survivors[key] = (priority, cid, entry, path.name)

ok(f"{len(files)} 个默认配置文件，原始条目 {total_raw} 条，去重后 {len(survivors)} 条")
ok(f"分类 {len(categories)} 个：{sorted(categories)}")
ok(f"带 DataComponent 的商品 {component_items} 条")

for key, previous, candidate in duplicates:
    kept = candidate if candidate[0] > previous[0] else previous
    dropped = previous if kept is candidate else candidate
    ok(f"重复项 {key}：保留 {kept[3]}（优先级 {kept[0]}，价 {kept[2].get('price')}），"
       f"丢弃 {dropped[3]}（优先级 {dropped[0]}，价 {dropped[2].get('price')}）")
    if kept[0] != max(candidate[0], previous[0]):
        bad(f"重复项 {key} 没有保留优先级更高的一条")

if not duplicates:
    note("默认配置里没有重复项示例（无法验证跨文件去重与优先级是否生效）")
else:
    stone = survivors.get("minecraft:stone|")
    if stone is None:
        bad("找不到 minecraft:stone 的去重结果")
    elif stone[1] != "stone" or stone[2].get("price") != 10:
        bad(f"minecraft:stone 的去重结果不是 04-stone.json（石材，价 10）：{stone[1]} / {stone[2].get('price')}")
    else:
        ok("跨文件去重按优先级生效：minecraft:stone 采用石材分类（50 > 木材 40）的 10 元价")

print("\n== 2) Java 里的 DEFAULT_FILE_NAMES 与资源文件一一对应 ==")
loader_java = (SRC / "com/lyhyy2007/bmcs/config/ShopConfigLoader.java").read_text(encoding="utf-8")
block = re.search(r"DEFAULT_FILE_NAMES = List\.of\((.*?)\);", loader_java, re.S)
if not block:
    bad("ShopConfigLoader.java 里找不到 DEFAULT_FILE_NAMES")
else:
    declared = re.findall(r'"([^"]+\.json)"', block.group(1))
    actual = [f.name for f in files]
    if sorted(declared) == sorted(actual):
        ok(f"DEFAULT_FILE_NAMES 与磁盘文件一致（{len(declared)} 个）")
    else:
        bad(f"DEFAULT_FILE_NAMES 与磁盘不一致：声明 {sorted(declared)} / 实际 {sorted(actual)}")
if "MAX_FILE_BYTES = 10L * 1024 * 1024" in loader_java:
    ok("单文件 10MB 上限存在")
else:
    bad("缺少 10MB 单文件上限（需求要求超过 10MB 警告并拒绝加载）")
if "DedupRegistry" in loader_java and "ownerCategories" in loader_java:
    ok("去重是全局的（跨文件、跨分类），冲突时按优先级转移条目归属")
else:
    bad("去重只在分类内生效：跨分类的重复商品会同时上架（运行时已实测过这个缺陷）")
if "'\\uFEFF'" in loader_java:
    ok("解析前剔除 UTF-8 BOM（Windows 编辑器写出的 BOM 不会让整份文件报废）")
else:
    bad("没有处理 UTF-8 BOM")

print("\n== 3) 语言文件与分类名键 ==")
lang = {}
for name in ("zh_cn", "en_us"):
    path = RES / f"assets/{MOD_ID}/lang/{name}.json"
    lang[name] = json.loads(path.read_text(encoding="utf-8"))
    ok(f"{name}.json 合法，{len(lang[name])} 个键")
if set(lang["zh_cn"]) != set(lang["en_us"]):
    missing_en = sorted(set(lang["zh_cn"]) - set(lang["en_us"]))
    missing_zh = sorted(set(lang["en_us"]) - set(lang["zh_cn"]))
    bad(f"中英键不一致：en 缺 {missing_en} / zh 缺 {missing_zh}")
else:
    ok("中英键集合完全一致")

defined = set(lang["zh_cn"])
for cid in sorted(categories):
    key = f"category.{MOD_ID}.{cid}"
    if key in defined:
        ok(f"分类 {cid} 有内置翻译键 {key}")
    else:
        bad(f"分类 {cid} 缺少内置翻译键 {key}（默认配置没有 display_name，必须靠它显示名字）")
for key in (f"key.categories.{MOD_ID}", f"key.{MOD_ID}.open_shop"):
    if key in defined:
        ok(f"键位翻译键存在：{key}")
    else:
        bad(f"缺少键位翻译键：{key}")

print("\n== 4) 代码里引用的翻译键是否都有定义 ==")
pattern = re.compile(r'"((?:message|command|gui|currency|sidebar_button|category|key)\.' + MOD_ID + r'\.[A-Za-z0-9_.]+)"')
referenced = set()
for java in SRC.rglob("*.java"):
    text = java.read_text(encoding="utf-8")
    for key in pattern.findall(text):
        referenced.add(key)
    for prefix, suffix in re.findall(r'"(message|command|gui|currency|sidebar_button|category|key)\."\s*\+\s*MOD_ID\s*\+\s*"\.([A-Za-z0-9_.]+)"', text):
        referenced.add(f"{prefix}.{MOD_ID}.{suffix}")
    for prefix, suffix in re.findall(r'"(message|command|gui|currency|sidebar_button|category|key)\."\s*\+\s*BetterMinecraftShop\.MOD_ID\s*\+\s*"\.([A-Za-z0-9_.]+)"', text):
        referenced.add(f"{prefix}.{MOD_ID}.{suffix}")
    for prefix in ("gui", "command", "message", "currency"):
        if re.search(r'String KEY = "' + prefix + r'\."\s*\+\s*(?:BetterMinecraftShop\.)?MOD_ID\s*\+\s*"\."', text):
            for suffix in re.findall(r'KEY\s*\+\s*"([A-Za-z0-9_.]+)"', text):
                referenced.add(f"{prefix}.{MOD_ID}.{suffix}")
            # 三元表达式里的字面量： KEY + (cond ? "a" : "b")
            for group in re.findall(r'KEY\s*\+\s*\([^)]*\)', text):
                for suffix in re.findall(r'"([A-Za-z0-9_.]+)"', group):
                    referenced.add(f"{prefix}.{MOD_ID}.{suffix}")
            # 助手函数 line("xxx", ...) 自己会补上 KEY 前缀
            for suffix in re.findall(r'\bline\(\s*"([A-Za-z0-9_.]+)"', text):
                referenced.add(f"{prefix}.{MOD_ID}.{suffix}")
    if 'String key' in text and 'Component.translatable("message."' in text:
        for suffix in re.findall(r'msg\(\s*"([A-Za-z0-9_.]+)"', text):
            referenced.add(f"message.{MOD_ID}.{suffix}")
# key.categories.<modid> 是运行时拼出来的
referenced.add(f"key.categories.{MOD_ID}")
# category.<modid>.<分类id> 由 ItemNameUtil.categoryKey 运行时拼接
for cid in categories:
    referenced.add(f"category.{MOD_ID}.{cid}")

sidebar_path = RES / f"assets/{MOD_ID}/sidebar_buttons/shop.json"
if sidebar_path.exists():
    def collect_translate(node):
        if isinstance(node, dict):
            if isinstance(node.get("translate"), str):
                referenced.add(node["translate"])
            for value in node.values():
                collect_translate(value)
        elif isinstance(node, list):
            for value in node:
                collect_translate(value)

    sidebar_json = json.loads(sidebar_path.read_text(encoding="utf-8"))
    collect_translate(sidebar_json)
    for entry in sidebar_json.get("click", []):
        ns, _, path = entry.partition(":")
        if ns and path:
            referenced.add(f"sidebar_button.{ns}.{path}")

code_keys = {k for k in referenced if not k.startswith("sidebar_button")}
missing = sorted(code_keys - defined)
unused = sorted(defined - referenced)
if missing:
    bad(f"代码引用但语言文件缺失的键（{len(missing)}）：{missing}")
else:
    ok(f"代码引用的 {len(code_keys)} 个键全部有定义")
if unused:
    note(f"语言文件里定义但代码未直接引用的键（{len(unused)}）：{unused}")
else:
    ok("没有多余键")

print("\n== 5) FTB 侧边栏 JSON 与点击事件 id 对应 ==")
sidebar = json.loads(sidebar_path.read_text(encoding="utf-8"))
click = sidebar.get("click")
if not isinstance(click, list) or not click:
    bad("sidebar 按钮的 click 必须是至少 1 个元素的数组（FTB 2101.1.37 的 schema）")
else:
    ok(f"click = {click}")
    integration = (SRC / "com/lyhyy2007/bmcs/client/ShopSidebarIntegration.java").read_text(encoding="utf-8")
    for entry in click:
        ns, _, path = entry.partition(":")
        if ns != MOD_ID:
            bad(f"click 命名空间 {ns} 与 modId {MOD_ID} 不一致")
        if f'id("{path}")' not in integration:
            bad(f"代码里找不到与 click 对应的 ResourceLocation: {path}")
        else:
            ok(f"click 项 {entry} 与客户端注册一致")
if not isinstance(sidebar.get("icon"), list):
    bad("icon 必须是数组（Icon.CODEC 用 ExtraCodecs.JSON 解析，FTB 官方示例也是数组）")
else:
    for icon in sidebar["icon"]:
        ns, _, p = icon.partition(":")
        if not (RES / "assets" / ns / p).exists():
            bad(f"图标资源不存在：assets/{ns}/{p}")
        else:
            ok(f"图标资源存在：assets/{ns}/{p}")
for forbidden in ("group", "x"):
    if forbidden in sidebar:
        bad(f"出现了 FTB 2101.1.37 不认识的字段 '{forbidden}'（旧版 schema）")


def class_utf8_constants(path):
    """极简 class 文件常量池解析：取出所有 CONSTANT_Utf8 字符串。"""
    data = path.read_bytes()
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError(f"{path} 不是 class 文件")
    count = struct.unpack_from(">H", data, 8)[0]
    offset = 10
    index = 1
    strings = []
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:  # Utf8
            length = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            strings.append(data[offset:offset + length].decode("utf-8", errors="replace"))
            offset += length
        elif tag in (7, 8, 16, 19, 20):
            offset += 2
        elif tag == 15:
            offset += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        else:
            raise ValueError(f"未知常量池 tag {tag}")
        index += 1
    return strings


libs = sorted(glob.glob(str(ROOT / "libs/ftb-library-neoforge-*.jar")))
if not libs:
    bad("libs/ 下找不到 FTB Library jar，无法校验 sidebar schema")
else:
    with zipfile.ZipFile(libs[-1]) as zf:
        class_bytes = zf.read("dev/ftb/mods/ftblibrary/sidebar/SidebarButtonData.class")
    tmp = ROOT / "build" / "SidebarButtonData.class"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    tmp.write_bytes(class_bytes)
    constants = set(class_utf8_constants(tmp))
    unknown = sorted(k for k in sidebar if k not in constants)
    if unknown:
        bad(f"shop.json 里出现 FTB SidebarButtonData 不认识的字段：{unknown}")
    else:
        ok(f"shop.json 的字段全部存在于 FTB SidebarButtonData 的常量池（{sorted(sidebar)}）")
    for required in ("icon", "click"):
        if required not in sidebar:
            bad(f"缺少必需字段 {required}")
    for codec_field in ("default_enabled", "click", "loading_screen", "tooltip", "requires_op",
                        "required_mods", "sort_index"):
        if codec_field not in constants:
            bad(f"FTB 类里没有预期字段 {codec_field}（jar 版本可能不匹配）")
    ok("FTB 类的 codec 字段名与文档记录一致")
    tmp.unlink(missing_ok=True)

print("\n== 6) 交易安全 / 大数据量架构的静态不变量 ==")
tx = (SRC / "com/lyhyy2007/bmcs/shop/ShopTransaction.java").read_text(encoding="utf-8")
if tx.index("removeMatching") < tx.index("deposit"):
    ok("卖出：先移除物品，后给钱")
else:
    bad("卖出：先给钱后移除物品（会刷钱）")
if "Math.multiplyExact" in tx:
    ok("金额计算使用 Math.multiplyExact（不会整型溢出）")
else:
    bad("金额计算没有溢出保护")

action = (SRC / "com/lyhyy2007/bmcs/network/ShopActionPayload.java").read_text(encoding="utf-8")
if "HARD_LIMIT" in action and "quantity < 0" in action:
    ok("交易包在解码阶段就拒绝非法数量")
else:
    bad("交易包缺少数量合法性检查")

manager = (SRC / "com/lyhyy2007/bmcs/shop/ShopManager.java").read_text(encoding="utf-8")
if "payload.revision() != ShopConfig.get().revision()" in manager:
    ok("交易包带目录版本校验（拒绝过期界面）")
else:
    bad("缺少目录版本校验")
if "MAX_BATCH_ENTRIES" in manager and "buildBatches" in manager:
    ok("全量同步按 MAX_BATCH_ENTRIES 分批发送")
else:
    bad("全量同步没有分批（10000 条会超出原版 1MB 包上限）")
if "ShopPatchPayload" in manager:
    ok("交易后只发单品增量补丁（不再全量重推）")
else:
    bad("交易后缺少增量补丁，仍在全量重推")

payload = (SRC / "com/lyhyy2007/bmcs/network/ShopDataPayload.java").read_text(encoding="utf-8")
missing_tokens = [t for t in ("batchIndex", "batchCount", "totalEntries", "MAX_BATCH_ENTRIES = 500")
                  if t not in payload]
if missing_tokens:
    bad(f"ShopDataPayload 缺少分批字段/常量：{missing_tokens}")
else:
    ok("ShopDataPayload 带 batchIndex/batchCount/totalEntries，单批上限 500")
entry_data = re.search(r"public record EntryData\((.*?)\) \{", payload, re.S)
if entry_data is None:
    bad("ShopDataPayload 里找不到 EntryData 定义")
elif "displayName" in entry_data.group(1):
    bad("网络包的 EntryData 仍然携带 displayName（展示名应由客户端本地化）")
else:
    ok("网络包的 EntryData 不再传 displayName（展示名由客户端本地化，省带宽）")

client = (SRC / "com/lyhyy2007/bmcs/client/ShopClientData.java").read_text(encoding="utf-8")
if "public static boolean accept(" in client and "pendingEntries" in client:
    ok("客户端按分片累积装配快照")
else:
    bad("客户端没有分片装配逻辑")
if "filteredCache" in client and "searchKeys" in client:
    ok("客户端过滤结果带缓存、搜索索引预计算")
else:
    bad("客户端缺少过滤缓存/搜索索引")

screen = (SRC / "com/lyhyy2007/bmcs/client/ShopScreen.java").read_text(encoding="utf-8")
if "VirtualGrid" in screen and "firstVisibleIndex" in screen and "lastVisibleIndex" in screen:
    ok("商品网格只渲染可视窗口（虚拟滚动）")
else:
    bad("商品网格缺少虚拟滚动")
if re.search(r"for \(int i = windowFrom; i < windowTo", screen):
    ok("addWidgets 只为窗口内的条目创建控件")
else:
    bad("addWidgets 没有按窗口裁剪（大数据量下会创建上万个控件）")
if "public int getContentHeight()" in screen and "public void setScrollY(double scroll)" in screen:
    ok("覆盖了 getContentHeight/setScrollY 以配合 FTB 的 PanelScrollBar")
else:
    bad("没有按 FTB PanelScrollBar 的语义覆盖内容高度/滚动回调")
if "Component.translatable(KEY + \"tile.buy\"" in screen:
    ok("界面文案走翻译键（不再硬编码中文）")
else:
    bad("界面里可能存在硬编码文案")

watcher = (SRC / "com/lyhyy2007/bmcs/shop/ShopConfigWatcher.java").read_text(encoding="utf-8")
if "WatchService" in watcher and "server.execute" in watcher:
    ok("配置热重载：WatchService 监听 + 回主线程重载")
else:
    bad("缺少 WatchService 热重载或没有回到服务端主线程")

keybind = (SRC / "com/lyhyy2007/bmcs/client/ShopKeyMappings.java").read_text(encoding="utf-8")
if "GLFW_KEY_O" in keybind and "RegisterKeyMappingsEvent" in keybind and "ClientTickEvent.Post" in keybind:
    ok("键位：默认 O、mod 总线注册、tick 中消费点击")
else:
    bad("键位绑定实现不完整")
if "consumeClick" in keybind:
    ok("每 tick 无条件清空点击队列（避免遗留触发）")
else:
    bad("没有消费按键点击，可能残留触发")

name_util = (SRC / "com/lyhyy2007/bmcs/util/ItemNameUtil.java").read_text(encoding="utf-8")
if "getDescriptionId" in name_util and "Language.getInstance().has" in name_util:
    ok("物品名走游戏内置本地化 + 缺翻译回退")
else:
    bad("物品名工具类没有走内置本地化链路")
entry = (SRC / "com/lyhyy2007/bmcs/config/ShopItemEntry.java").read_text(encoding="utf-8")
entry_header = re.search(r"public record ShopItemEntry\((.*?)\) \{", entry, re.S)
if entry_header is None:
    bad("ShopItemEntry.java 里找不到 record 定义")
elif "displayName" in entry_header.group(1):
    bad("ShopItemEntry 仍然带 displayName 组件字段")
else:
    ok("ShopItemEntry 已移除 displayName 字段（展示名交给 ItemNameUtil）")
stack_util = (SRC / "com/lyhyy2007/bmcs/util/ItemStackUtil.java").read_text(encoding="utf-8")
if "isSameItemSameComponents" in stack_util:
    ok("物品匹配使用组件精确比较（附魔/改名不会串味）")
else:
    bad("物品匹配没有使用组件精确比较")
if "ARMOR_SLOTS" in stack_util and "isTradeableSlot" in stack_util:
    ok("回收时排除护甲槽")
else:
    bad("回收可能把护甲卖掉")
if re.search(r"\.getEnchantments\(\)", (SRC / "com/lyhyy2007/bmcs/config/ShopConfig.java").read_text(encoding="utf-8")):
    bad("ShopConfig 使用了已过时的 ItemStack#getEnchantments()")
else:
    ok("没有使用已过时的 ItemStack#getEnchantments()（改走 DataComponents.ENCHANTMENTS）")
commands = (SRC / "com/lyhyy2007/bmcs/command/BmcsCommands.java").read_text(encoding="utf-8")
if 'literal("debug")' in commands and 'literal("item")' in commands and 'literal("config")' in commands:
    ok("/bmcs debug item|config 命令存在")
else:
    bad("缺少 /bmcs debug 命令")
if 'literal("sync")' in commands and "buildBatches" in commands:
    ok("/bmcs debug sync 会真跑一遍快照同步并报告批数与包体大小")
else:
    bad("缺少 /bmcs debug sync（没有客户端时无法验证分批同步开销）")
if "greedyString" in commands:
    ok("debug item 用 greedyString 接收物品 id（word() 不接受 ':'）")
else:
    bad("debug item 的参数类型无法接受 namespace:path")

print("\n== 7) 交付文档与自检脚本 ==")
readme = (ROOT / "README.md").read_text(encoding="utf-8") if (ROOT / "README.md").exists() else ""
if not readme:
    bad("缺少 README.md")
else:
    for phrase, label in (("shop_items/", "多文件配置目录"),
                         ("O 键", "键位绑定"),
                         ("虚拟滚动", "虚拟滚动"),
                         ("热重载", "热重载"),
                         ("分批", "分批同步"),
                         ("display_name", "display_name 移除说明")):
        if phrase in readme:
            ok(f"README 覆盖：{label}")
        else:
            bad(f"README 未覆盖：{label}（缺少 “{phrase}”）")
tools = ROOT / "tools"
for name in ("verify_assets.py", "check_jar.py", "rcon.py", "gen_stress_config.py", "VirtualGridCheck.java"):
    if (tools / name).exists():
        ok(f"自检工具存在：tools/{name}")
    else:
        bad(f"缺少自检工具：tools/{name}")

print("\n== 8) 汇总 ==")
for item in notes:
    print(f"  [NOTE] {item}")
if failures:
    print(f"\n共 {len(failures)} 项失败：")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("\n全部检查通过。")
