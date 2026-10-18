# 更好的 Minecraft 商店 · Better Minecraft Shop

**中文** | [English](#english)

> 数据驱动的 Minecraft **1.21.1 / NeoForge** 商店模组：内置单一货币「深索币」，
> 在背包界面左上角提供 FTB Library 侧边栏入口（也可以按 `O` 键），
> 商品价格与限量全部由服务端 JSON 配置驱动 —— 买入、卖出分目录存放，
> 支持任意数量的配置文件、10000+ 条目、虚拟滚动、分批同步与配置热重载；
> 界面上还能用**全部物品**或**背包里的物品**新建商品并即时落盘。
>
> A data-driven shop mod for **Minecraft 1.21.1 / NeoForge**. It ships one built-in
> currency (*DeepCoin*), adds a FTB Library sidebar button on the inventory screen
> (the `O` key works too), and drives every price/stock value from server-side JSON
> config files — split into `in/` (buy) and `out/` (sell) folders, with 10 000+ entries,
> virtual scrolling, batched syncing, config hot reload and an in-game **New item** editor.

| 项目 / Item | 值 / Value |
|---|---|
| 模组 id / Mod id | `better_minecraft_shop` |
| 版本 / Version | 1.0.5 |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.200+（构建用 21.1.233 / built against 21.1.233） |
| 前置 / Requires | FTB Library `2101.1.x`、Architectury API `13.0.6+` |
| 环境 / Side | 客户端 + 服务端（双端都要装）/ client **and** server |
| 许可 / License | **MIT**（本模组自身；前置模组各自遵循其许可）/ **MIT** for this mod; prerequisites keep their own licenses |

---

## 1. 前置依赖 / Requirements

**必须安装（客户端与服务端都要）/ Required on both client and server:**

| 前置模组 / Prerequisite | 版本 / Version | 下载 / Download | 说明 / Notes |
|---|---|---|---|
| **FTB Library** (NeoForge) | `2101.1.x`（实测 2101.1.37） | https://maven.ftb.dev/releases/dev/ftb/mods/ftb-library-neoforge/2101.1.37/ ・ https://www.curseforge.com/minecraft/mc-mods/ftb-library-forge | 提供侧边栏按钮系统与界面框架 / provides the sidebar button system and the UI framework |
| **Architectury API** (NeoForge) | `13.0.6+` | https://maven.architectury.dev/dev/architectury/architectury-neoforge/13.0.6/ ・ https://www.curseforge.com/minecraft/mc-mods/architectury-api | FTB Library 的强制前置；侧边栏点击事件用到 / hard dependency of FTB Library; used for the sidebar click event |
| **NeoForge** | `21.1.200+`（21.1.x） | https://neoforged.net/ | 模组加载器 / mod loader |
| **Minecraft Java Edition** | `1.21.1` | — | 需要正版账号 / a valid account is required |

**可选 / Optional:**

* 想在游戏里看到**中文界面**：本模组自带 `zh_cn` 与 `en_us` 语言文件，跟随客户端语言设置即可。
  *Chinese UI ships with the mod (`zh_cn` + `en_us`); it follows your client language.*
* 只有**服务端**安装、客户端不装时，玩家看不到入口。请两端都装。
  *Install it on both sides; the entry button lives on the client.*

> 前置模组**不随本仓库分发**。FTB Library 为 All Rights Reserved，
> 本模组只通过它的公开 API 与 JSON（`assets/<ns>/sidebar_buttons/*.json`）集成，未修改也未转载其代码。
> *Prerequisites are not bundled. FTB Library is All Rights Reserved; this mod only
> integrates through its public API and JSON, without modifying or redistributing it.*

---

## 2. 安装 / Installation

**中文**

1. 安装 Minecraft 1.21.1 + NeoForge 21.1.200 或更高（21.1.x）。
2. 把 **FTB Library**、**Architectury API**、**更好的 Minecraft 商店** 三个 jar 一起放进
   `mods/` 目录（客户端与服务端都要放）。
3. 启动游戏 / 服务器。**首次启动只创建两个空目录**：
   `config/better-minecraft-shop/in/`（买入）与 `config/better-minecraft-shop/out/`（卖出），
   **不会**再写入任何默认 JSON —— 商店一开始是空的，由服主自己放配置或用界面里的「新建」添加。
4. 进游戏后：打开背包（`E`）→ 左上角出现「商店」按钮；或者直接按 `O` 键。
   管理员也可以用命令 `/bmcs shop`。

**English**

1. Install Minecraft 1.21.1 with NeoForge 21.1.200+ (21.1.x).
2. Drop **FTB Library**, **Architectury API** and **Better Minecraft Shop** into `mods/`
   (on both client and server).
3. Start the game/server. On the **first launch** only two empty folders are created:
   `config/better-minecraft-shop/in/` (buy) and `config/better-minecraft-shop/out/` (sell).
   **No default JSON is written any more** — the shop starts empty; ship your own files or
   use the in-game **New item** editor.
4. In game: press `E` for the inventory → click the **Shop** button in the top-left
   sidebar, or simply press `O`. Admins can also use `/bmcs shop`.

---

## 3. 快速上手 / Quick start

**中文**

| 入口 / Entry | 操作 / How |
|---|---|
| 背包侧边栏 / Inventory sidebar | `E` 打开背包 → 左上角「商店」按钮 / press `E`, click **Shop** |
| 键位 / Keybind | 默认 `O`，可在「选项 → 控制 → 按键绑定 → 更好的 Minecraft 商店」里改 / default `O`, rebindable in vanilla Controls |
| 命令 / Command | `/bmcs shop`（管理员可 `/bmcs shop <玩家>`） |

打开后：左侧是**「买入」/「卖出」两个栏位**（分别读 `in/` 与 `out/`，分类与页签互不影响），
右侧顶部是**分类页签**，中间是**商品网格**（图标、本地化名称、价格、剩余库存），
右上角最右是**搜索框**，左边是「新建分类」按钮（**只在编辑模式下出现**；单人存档任何人可见，专用服务器仅 OP 可见），
底部是**数量输入 + 购买 / 出售**按钮（按钮随栏位切换）与状态提示。

购买会检查你的**深索币（DeepCoin）**余额、把物品放进背包，并扣减限量商品的库存；
出售会从**主背包 + 副手**（不碰护甲槽）取走与商品完全一致的物品并支付回收价。

**English** — the left column has **Buy / Sell** tabs that read `in/` and `out/` independently.
The screen also has category tabs, an item grid (icon, localized name, price, stock),
a search box, a **New** button to its left (everyone in single-player, OP only on a
dedicated server) and a quantity box with
**Buy / Sell** buttons. Buying checks your DeepCoin balance, inserts the items and
decrements limited stock; selling removes matching items from your main inventory and
offhand (never from armor slots) and pays the recycle price.

余额查询 / 转账 / 管理员改钱 / Check balance, pay, admin:

```text
/bmcs balance                     查看自己的余额 / your balance
/bmcs balance <玩家>              查看他人余额（权限 2）
/bmcs pay <玩家> <金额>           转账 / transfer DeepCoin
/bmcs set|add <玩家> <金额>       管理员设置 / 增加余额（权限 2）
```

---

## 4. 配置文件 / Configuration

**中文**

1.0.5 起买入与卖出**分成两个平级目录**，各自独立的分类与商品：

| 交易方向 / Direction | 路径 / Path | 商品字段 / Fields |
|---|---|---|
| 玩家**买入**（花深索币买物品）/ buy | `config/better-minecraft-shop/in/**.json` | `price`（必填，≥1）、`stock`（可选，默认 `-1` 不限量） |
| 玩家**卖出**（把物品换成深索币）/ sell | `config/better-minecraft-shop/out/**.json` | `sell_price`（必填，≥1） |

* **目录位置**：`config/better-minecraft-shop/` 下**直接**是 `in/` 与 `out/`（不再有 `shop_items/`）。
  两个目录都在启动时自动创建；**不会写入任何默认 JSON**（见 R2）。
* **格式版本**：`"version": 2`。`in/` 里写 `sell_price`、`out/` 里写 `price` 都会被忽略并打日志提示。
* **防刷钱**：同一件商品（物品 id + 附魔签名）如果 `out/` 的 `sell_price` 高于 `in/` 的 `price`，
  载入时会被压回 `price` 并记录日志。
* **旧数据**：`config/better-minecraft-shop/shop_items/` 与旧单文件 `shop_items.json`
  **不会被读取、修改或删除**；启动日志会给出绝对路径与迁移提示。迁移用
  `python tools/migrate_shop_items.py`（默认只预览，加 `--apply` 才写入 `in/`、`out/`，
  源文件原样保留）。
* 每个文件独立解析：**单个文件写坏只丢这个文件**；单条商品写错只丢这一条。
* 单文件 **10MB** 上限，超限拒绝加载；带 UTF-8 BOM 的文件会自动兼容。
* 同一个目录内同名物品按**分类优先级**去重（武器工具 > 五金 > 建材 > 石材 > 木材 > 原材料 > 食物 > 种子，
  可用分类的 `priority: 0..100` 覆盖）；纯物品条目严格按 `item` 去重，带附魔的条目算不同商品。
* 改完文件**保存即生效**（监听目录自动热重载，约 1.5 秒），也可以 `/bmcs reload` 手动重载。
* 物品**没有** `display_name` 字段：名字由游戏内置本地化提供，跟随客户端语言。
* `components` **只支持附魔**（`minecraft:enchantments` / `minecraft:stored_enchantments`）。

**English**

Since 1.0.5 buy and sell live in **two sibling folders**, each with its own categories:

| Direction | Path | Fields |
|---|---|---|
| Player **buys** (pays DeepCoin) | `config/better-minecraft-shop/in/**.json` | `price` (required, ≥1), `stock` (optional, `-1` = unlimited) |
| Player **sells** (gets DeepCoin) | `config/better-minecraft-shop/out/**.json` | `sell_price` (required, ≥1) |

* Both folders are created at startup and **no default JSON is written**.
* Format `"version": 2`; `sell_price` inside `in/` and `price` inside `out/` are ignored with a log line.
* Anti money-loop: when `out/`'s `sell_price` is above `in/`'s `price` for the same
  item (+ enchantment signature), it is clamped to `price` while loading.
* Legacy `shop_items/` and the old `shop_items.json` are **never read, modified or deleted**;
  a startup warning points at them. Migrate with `python tools/migrate_shop_items.py`
  (preview only by default; `--apply` writes `in/` and `out/`, sources are left untouched).
* Per-file and per-entry error isolation, 10 MB per-file limit, UTF-8 BOM tolerated,
  cross-category dedup by category priority, hot reload on save (or `/bmcs reload`).
* 分类字段：`id`（必填，稳定标识；游戏内新建的分类为 `cat_0`、`cat_1`…）、`display_name`（可选，
  翻译键或纯文本）、`icon`（可选，物品 id）、`priority`（可选，去重优先级）、
  `editable`（可选，`true` = 游戏内可改名 / 换图标 / 加删商品）。
* 游戏内新建的分类**一个分类一个文件**，文件名 = 分类名（留空则为 `_0`、`_1`…）；
  手写的多分类文件照旧能加载，但游戏内不会改写它们。空分类会保留并显示，方便往里加商品。

```json
// in/weapons_tools.json —— 玩家买入：只写 price 与 stock
{
  "version": 2,
  "categories": [
    {
      "id": "weapons_tools",
      "icon": "minecraft:diamond_sword",
      "items": [
        { "item": "minecraft:iron_pickaxe", "price": 300, "stock": -1 },
        { "item": "minecraft:diamond", "price": 1000, "stock": 100 },
        {
          "item": "minecraft:netherite_chestplate",
          "price": 24000, "stock": 2,
          "components": {
            "minecraft:enchantments": { "minecraft:protection": 4, "minecraft:mending": 1 }
          }
        }
      ]
    }
  ]
}
```

```json
// out/weapons_tools.json —— 玩家卖出：只写 sell_price
{
  "version": 2,
  "categories": [
    {
      "id": "weapons_tools",
      "icon": "minecraft:diamond",
      "items": [
        { "item": "minecraft:iron_pickaxe", "sell_price": 90 },
        { "item": "minecraft:diamond", "sell_price": 350 },
        {
          "item": "minecraft:netherite_chestplate", "sell_price": 8000,
          "components": {
            "minecraft:enchantments": { "minecraft:protection": 4, "minecraft:mending": 1 }
          }
        }
      ]
    }
  ]
}
```

| 分类字段 / Category | 必填 / Required | 默认 / Default | 说明 / Description |
|---|---|---|---|
| `id` | ✔ | — | 分类 id（小写）；商品键前缀；内置翻译键 `category.better_minecraft_shop.<id>` |
| `display_name` | ✘ | 空 | 分类名（支持翻译键）；留空即用内置翻译键 / optional, falls back to the built-in translation key |
| `icon` | ✘ | 空 | 页签图标物品 id / tab icon item id |
| `priority` | ✘ | 查表 | 去重优先级 `0..100`，越大越优先 / dedup priority, higher wins |
| `items` | ✔ | — | 商品数组 / item array |

| 商品字段 / Item | `in/` | `out/` | 说明 / Description |
|---|---|---|---|
| `item` | ✔ | ✔ | 物品注册名 `命名空间:路径` / registry name |
| `price` | ✔ | ✘ | 买入单价，**≥ 1** / buy price, must be ≥ 1 |
| `sell_price` | ✘ | ✔ | 回收单价，**≥ 1**（不再用 `price` 兜底）/ recycle price, must be ≥ 1 |
| `stock` | ✘ | ✘ | 买入限量的初始库存：`-1` 不限量（默认）/ `-1` = unlimited (buy only) |
| `components` | ✘ | ✘ | **只支持附魔**：`minecraft:enchantments`（附魔书用 `minecraft:stored_enchantments`）/ enchantments only |

**只支持附魔 / enchantments only**

```json
"components": {
  "minecraft:enchantments": { "minecraft:sharpness": 5, "minecraft:unbreaking": 3 }
}
```

单次交易上限固定取物品堆叠上限（≤64），没有 `max_stack` 字段；改名、lore 等其它组件已移除。

**游戏内新建分类 / 商品 · 编辑模式 / In-game editor（单人任意 · 专用服务器 OP）**

* 买入页、卖出页**右上角**只有「新建分类」一个按钮（仅编辑模式下显示），搜索框在最右边；「新建商品」不占顶栏，而是编辑模式下、当前页签是游戏内新建的分类时，商品网格里**固定的第一格**，
  两两间距 6 px，互不遮挡；不是单人存档又没有权限等级 2 时这两个按钮不显示，服务端也会拒绝。
* **先建分类，再建商品**：点「新建分类」填分类名（中英文都行，**分类名就是文件名**；留空则自动
  用 `_0`、`_1`…，重名会被拒绝），点图标打开**图标选择器**（可搜索、可分页，列出全部已注册物品，
  含模组物品）；保存后自动选中新分类，再点商品网格第一格「新建商品」即可往里加物品。
* 停在「全部」页签、或选中的是手写 JSON 的分类时，网格里**根本没有**「新建商品」这一格；服务端同样会再校验（`editor.need_category` / `editor.category_locked`）。手写 JSON 里的分类（没有
  `"editable": true`）在游戏内**只读**：不能改名、不能加商品、不能删商品。
* **编辑模式**：左侧栏「编辑」开关打开后，左键点商品删除（带确认框），右键点分类页签改名 /
  换图标（改名会同步重命名文件；文件里还有别的分类时拒绝改名，避免牵连手写数据）。
* 商品编辑器：点选**背包格**取 1 个物品作为模板（附魔等组件原样保留），填写价格与库存后保存。
* 校验：价格 `1..1000000000`；库存 `-1` 或 `0..1000000`；同一分类里「物品 id + 组件」完全一致的
  条目会被拒绝；分类文件解析失败时拒绝写入（不覆盖坏文件）；只读 / IO 失败时原文件不变。
* 写入方式：临时文件 → `.bak` 备份 → 原子替换，落盘后自动重载并刷新所有在线玩家的列表。
  新建分类的 id 固定为 `cat_0`、`cat_1`…，改名不改 id，所以库存记录不会因为改名而丢。

**限量库存语义 / limited stock semantics**

* 买入扣减；卖出回收补货，补货上限取 `max(配置上限, 当前值)`。
* `/bmcs stock set <键> <数量>` 的设定**不会被重载或重启吃掉**；
  只有当你把配置里的 `stock` 上限改小（例如 100 → 10）时才会收紧，并在日志里提示。
* 命令：`/bmcs stock list | set <键> <数量> | reset <键>`（权限 2）。

**排查用的 debug 命令 / diagnostics (permission level 2)**

```text
/bmcs debug item <物品id>   本地化名称链路（翻译键 / 是否命中语言表 / 渲染结果）+ 在售情况
/bmcs debug sync            跑一遍完整快照同步：条目数、批数、组装/编码耗时、最大单包 KB
/bmcs debug config          配置统计、in/ 与 out/ 路径、分类优先级顺序、热重载监听状态
```

---

## 5. 从源码构建 / Building from source

**中文**

需要 **JDK 21**（Gradle 由 wrapper 自动下载）。两种依赖方式任选其一：

```bash
git clone <本仓库地址> && cd better-minecraft-shop-1.0.5

# 方式 A：把前置 jar 放进 libs/（离线可用，确切文件名见 libs/README.md）
./gradlew build            # Windows: gradlew.bat build

# 方式 B：libs/ 留空 → 自动从官方 maven 拉取前置（需要网络）
./gradlew build
```

产物：`build/libs/better-minecraft-shop-1.0.5.jar`（约 746 KB，其中模组图标约 273 KB；**不含**前置模组）。
**不要**用 `gradlew clean` —— `build/libs/` 里保留着 1.0.0 / 1.0.2 / 1.0.4 的历史产物。

开发运行 / 离线自检：

```bash
./gradlew runServer         # 首次需要 run/eula.txt 写 eula=true
./gradlew runClient         # 进游戏按 O 键或点侧边栏按钮

python tools/verify_assets.py .                                        # 配置/语言/侧边栏/架构不变量
java --class-path build/classes/java/main tools/VirtualGridCheck.java   # 虚拟滚动几何断言
python tools/check_jar.py build/libs/better-minecraft-shop-1.0.5.jar    # 产物内容检查
python tools/migrate_shop_items.py --apply                             # 旧 shop_items → in/ + out/
python tools/gen_stress_config.py run/config/better-minecraft-shop/in 10000 --side=buy    # 压力配置
```

**English** — you need **JDK 21** (the Gradle wrapper downloads Gradle itself).
Either drop the two prerequisite jars into `libs/` (see `libs/README.md` for the exact
file names; fully offline), or leave `libs/` empty and let Gradle fetch them from the
official Maven repositories. The artifact lands in
`build/libs/better-minecraft-shop-1.0.5.jar` (~746 KB; the mod icon is ~273 KB of that,
prerequisites are not bundled). Do **not** run `gradlew clean`: `build/libs/` keeps the
1.0.0 / 1.0.2 / 1.0.4 artifacts.

---

## 6. 目录结构 / Project layout

```text
better-minecraft-shop-1.0.5/
├── LICENSE / README.md / CHANGELOG.md / .gitignore / .gitattributes
├── build.gradle / settings.gradle / gradle.properties / gradlew(.bat) / gradle/wrapper/
├── libs/README.md                         前置 jar 放这里（jar 不入库）
├── src/main/java/com/lyhyy2007/bmcs/
│   ├── BetterMinecraftShop.java           主类：附件 / 网络 / 命令 / 生命周期 / 热重载
│   ├── config/    in/out 加载、优先级去重、商品条目、新建商品的写入与校验
│   ├── currency/  深索币（Data Attachment + Codec）
│   ├── shop/      服务端商店：分批快照、增量补丁、库存 SavedData、热重载监听、编辑器服务
│   ├── network/   StreamCodec 载荷（打开商店 / 动作 / 快照分片 / 增量补丁 / 编辑器保存与回执）
│   ├── command/   /bmcs 命令族（含 debug 子命令）
│   ├── util/      物品堆栈与本地化名工具
│   └── client/    FTB 侧边栏集成、键位、商店界面、新建分类/商品与图标选择界面、绘制原语
├── src/main/resources/
│   ├── META-INF/neoforge.mods.toml
│   └── assets/better_minecraft_shop/{lang,sidebar_buttons,textures}/   （1.0.5 起不含默认配置）
├── tools/         离线自检脚本 + 迁移脚本（Python + 单文件 Java）
├── docs/
│   ├── examples/legacy-shop_items/  旧格式样例（只作文档/迁移参考，不打进 jar）
│   └── examples/in/、examples/out/  新格式样例
└── dist/          历史构建产物（1.0.0 / 1.0.2 / 1.0.4 / 1.0.5）
```

---

## 7. 许可 / License

**中文** —— 本模组自身（源码、资源、构建脚本、工具、文档）以 **MIT** 许可发布，
见 [LICENSE](LICENSE)。前置模组不在本仓库内、也不在本许可范围内：
FTB Library 为 All Rights Reserved（仅通过公开 API / JSON 集成），
Architectury、Minecraft、NeoForge 各自遵循其许可。SDMShop / SDM-UI-Lib 仅作为
缺陷分析与时序参考被阅读，**没有复制其代码**（详见 [docs/SDMShop-缺陷对照表.md](docs/SDMShop-缺陷对照表.md)）。

**English** — this mod's own code, assets, build scripts, tools and docs are released
under the **MIT** license, see [LICENSE](LICENSE). Prerequisites are neither bundled nor
covered by it: FTB Library is All Rights Reserved (integrated only through its public API
and JSON), and Architectury, Minecraft and NeoForge keep their own terms. SDMShop and
SDM-UI-Lib were only *read* for defect analysis and rendering-primitive reference —
no code was copied.

---

## 8. 已知限制 / Known limitations

* 商品单位固定为「1 件」，不支持「一捆 N 件按一份价格卖」。
  *Items are sold per piece; bundles are not supported yet.*
* 「新建」只会改动**带 `"editable": true`** 的分类文件（一个文件一个分类）；手写 JSON 不会被改写。
  *The editor only touches category files marked `"editable": true`; hand-written files are never rewritten.*
* 游戏内新建的分类 id 固定为 `cat_N`，改名只改 `display_name` 与文件名，库存记录不受影响。
  *In-game categories keep a stable `cat_N` id, so renaming never resets stock.*
* 界面视觉需要真实客户端（OpenGL）验证；虚拟滚动几何与分层逻辑有离线断言。
  *The GUI needs a real client to be seen; the virtual-scroll geometry is unit-checked offline.*
* 侧边栏按钮的显示位置由 FTB Library 的侧边栏配置决定。
  *The sidebar button position is controlled by FTB Library itself.*

---

## 9. 更多文档 / Further reading

| 文档 / Document | 内容 / Content |
|---|---|
| [docs/技术说明.md](docs/技术说明.md) | 完整技术说明：架构、网络协议、配置参考、命令、与 SDMShop 的差异、验收步骤 |
| [docs/验证记录.md](docs/验证记录.md) | 实测证据：日志原文、10000 条性能数据、发现并修复的缺陷 |
| [docs/SDMShop-缺陷对照表.md](docs/SDMShop-缺陷对照表.md) | SDMShop 3.3.1 逐条缺陷对照表 |
| [docs/verification-logs/](docs/verification-logs/) | 运行时日志与 RCON 回包原文 |

---

## English

*(Short English overview; every section above carries its English counterpart.)*

**Better Minecraft Shop** is a data-driven shop for **Minecraft 1.21.1 / NeoForge**.

* **Prerequisites (both sides):** FTB Library `2101.1.x`, Architectury API `13.0.6+`,
  NeoForge `21.1.200+`. Install the mod itself on client **and** server.
* **Open the shop** with the FTB Library sidebar button on the inventory screen, the
  rebindable `O` key, or `/bmcs shop`.
* **One currency:** DeepCoin, stored as a NeoForge data attachment (survives death),
  managed with `/bmcs balance|pay|set|add`.
* **Config:** `config/better-minecraft-shop/in/**.json` (player buys: `price` + `stock`)
  and `config/better-minecraft-shop/out/**.json` (player sells: `sell_price`), format
  `version: 2`. The folders start empty — no default JSON is generated. Legacy
  `shop_items/` is left untouched; migrate it with `tools/migrate_shop_items.py`.
  Per-file/per-entry isolation, 10 MB limit, UTF-8 BOM tolerance, dedup by category
  priority, hot reload, and a cross-folder anti money-loop clamp.
* **In-game editor (single-player: everyone, server: OP):** two buttons at the top-right of both
  pages — **New category** and **New item**. A category name becomes the file name (`_0`, `_1`…
  when blank) and the icon picker lists every registered item with search. Categories created in
  game carry `"editable": true`; **edit mode** in the left bar lets you rename / re-icon them and
  delete their items. Hand-written JSON files stay read-only and are never rewritten.
* **Scales to 10 000+ entries:** virtual scrolling on the client (only the visible
  window is built), batched syncing (≤ 500 entries per packet) and per-trade patches.
* **License:** MIT for this mod; FTB Library (ARR) and the other prerequisites are only
  referenced, never bundled.

Build instructions, the config reference, all commands and the project layout are in the
Chinese/English sections above.
