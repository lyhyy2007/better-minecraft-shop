# 更好的 Minecraft 商店 · Better Minecraft Shop

**中文** | [English](#english)

> 数据驱动的 Minecraft **1.21.1 / NeoForge** 商店模组：内置单一货币「深索币」，
> 在背包界面左上角提供 FTB Library 侧边栏入口（也可以按 `O` 键），
> 商品价格与限量全部由服务端 JSON 配置驱动 —— 支持任意数量的配置文件、
> 10000+ 条目、虚拟滚动、分批同步与配置热重载。
>
> A data-driven shop mod for **Minecraft 1.21.1 / NeoForge**. It ships one built-in
> currency (*DeepCoin*), adds a FTB Library sidebar button on the inventory screen
> (the `O` key works too), and drives every price/stock value from server-side JSON
> config files — with multi-file loading, 10 000+ entries, virtual scrolling,
> batched syncing and config hot reload.

| 项目 / Item | 值 / Value |
|---|---|
| 模组 id / Mod id | `better_minecraft_shop` |
| 版本 / Version | 1.0.4 |
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
3. 启动游戏 / 服务器。**首次启动**会自动在 `config/better-minecraft-shop/shop_items/`
   生成 8 个默认配置文件（按分类拆分，每条商品同时带买入价与回收价）。
4. 进游戏后：打开背包（`E`）→ 左上角出现「商店」按钮；或者直接按 `O` 键。
   管理员也可以用命令 `/bmcs shop`。

**English**

1. Install Minecraft 1.21.1 with NeoForge 21.1.200+ (21.1.x).
2. Drop **FTB Library**, **Architectury API** and **Better Minecraft Shop** into `mods/`
   (on both client and server).
3. Start the game/server. On the **first launch** the mod writes 8 default config files
   into `config/better-minecraft-shop/shop_items/` (split by category; every entry carries
   both a buy price and a sell price).
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

打开后：左侧是**「买入」/「卖出」两个栏位**（同一份配置：买入按 `price`、卖出按 `sell_price`），
右侧顶部是**分类页签**（武器工具、五金、建材、石材、木材、原材料、食物、种子…），
中间是**商品网格**（图标、本地化名称、价格、剩余库存），右上角是**搜索框**，
底部是**数量输入 + 购买 / 出售**按钮（按钮随栏位切换）与状态提示。

购买会检查你的**深索币（DeepCoin）**余额、把物品放进背包，并扣减限量商品的库存；
出售会从**主背包 + 副手**（不碰护甲槽）取走与商品完全一致的物品并支付回收价。

**English** — the left column has **Buy / Sell** tabs over the same config (Buy settles at
`price` and shows limited stock, Sell settles at `sell_price`); the rest of the screen has
category tabs, an item grid (icon, localized name, price), a search box and a quantity box
with **Buy / Sell** buttons. Buying checks your DeepCoin balance, inserts the items and
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

路径 / Path：`config/better-minecraft-shop/shop_items/**.json`（一个**目录**，任意数量、可含子目录；
与 jar 内 `data/better_minecraft_shop/shop_items/` 的默认模板一一对应）。
一份配置同时供两个栏位使用：买入读 `price`（并展示 `stock`），卖出读 `sell_price`。
兼容旧版单文件 `config/better-minecraft-shop/shop_items.json`。

**中文**

* **一份配置、两个栏位**：界面左侧「买入」「卖出」都读同一个目录。买入栏位按 `price` 结算
  并显示 `stock` 限量；卖出栏位按 `sell_price` 结算且不限量。`sell_price` 不写（或 0）时用
  `price` 兜底；`sell_price` 高于 `price` 会被压回 `price`（防刷钱）。
* 每个文件独立解析：**单个文件写坏只丢这个文件**，不影响其它文件；单条商品写错只丢这一条。
* 单文件 **10MB** 上限，超限拒绝加载；带 UTF-8 BOM 的文件会自动兼容。
* 同一个物品在多处出现时按**分类优先级**去重（武器工具 > 五金 > 建材 > 石材 > 木材 > 原材料 > 食物 > 种子，
  可用分类的 `priority: 0..100` 覆盖）；纯物品条目严格按 `item` 去重，带附魔的条目算不同商品。
* 改完文件**保存即生效**（监听目录自动热重载，约 1.5 秒），也可以 `/bmcs reload` 手动重载。
* 物品**没有** `display_name` 字段：名字由游戏内置本地化提供，跟随客户端语言。
* `components` **只支持附魔**（`minecraft:enchantments` / `minecraft:stored_enchantments`），
  改名、lore 等其它组件已移除；单次交易上限固定取物品堆叠上限（≤64），不再有 `max_stack` 字段。

**English**

* **One config, two tabs**: the Buy and Sell tabs read the same folder. Buy settles at
  `price` and shows `stock`; Sell settles at `sell_price` with no stock limit.
  `sell_price` falls back to `price` when missing (or 0); a `sell_price` above `price`
  is clamped to `price` (anti money-loop).
* Each file is parsed independently: a broken file is skipped without affecting others,
  and a broken entry is skipped without affecting the rest of its file.
* 10 MB per-file limit (larger files are refused); UTF-8 BOM is tolerated.
* Duplicate items are resolved by **category priority**
  (weapons & tools > hardware > building > stone > wood > materials > food > seeds;
  override per category with `priority: 0..100`).
* Saving a file applies immediately (directory watcher, ~1.5 s debounce) — or run `/bmcs reload`.
* There is **no** `display_name` field: item names come from the game's own localization
  and follow the client language.
* `components` supports **enchantments only** (`minecraft:enchantments` /
  `minecraft:stored_enchantments`); renaming and lore are removed, and `max_stack` no longer exists —
  the per-trade limit is the item's stack size (≤64).

```json
// shop_items/weapons_tools.json —— 一条商品同时写买入价与回收价
{
  "version": 1,
  "categories": [
    {
      "id": "weapons_tools",
      "icon": "minecraft:diamond_sword",
      "items": [
        { "item": "minecraft:iron_pickaxe", "price": 300, "sell_price": 90, "stock": -1 },
        { "item": "minecraft:diamond", "price": 1000, "sell_price": 350, "stock": 100 },
        {
          "item": "minecraft:netherite_chestplate",
          "price": 24000, "sell_price": 8000, "stock": 2,
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

| 商品字段 / Item | 必填 / Required | 默认 / Default | 说明 / Description |
|---|---|---|---|
| `item` | ✔ | — | 物品注册名 `命名空间:路径` / registry name |
| `price` | ✔ | — | 买入单价，**必须 ≥ 1**；缺了这件物品就不出现在买入栏位 / buy price, must be ≥ 1 |
| `sell_price` | ✘ | `price` | 回收单价；不写（或 0）用 `price` 兜底，高于 `price` 时压回 / sell price, falls back to `price` |
| `stock` | ✘ | `-1` | 买入限量的初始库存：`-1` 不限量；只影响买入栏位 / `-1` = unlimited (buy tab only) |
| `components` | ✘ | 空 | **只支持附魔**：`minecraft:enchantments`（附魔书用 `minecraft:stored_enchantments`）/ enchantments only |

**只支持附魔 / enchantments only**

```json
"components": {
  "minecraft:enchantments": { "minecraft:sharpness": 5, "minecraft:unbreaking": 3 }
}
```

单次交易上限固定取物品堆叠上限（≤64），没有 `max_stack` 字段；改名、lore 等其它组件已移除。

**限量库存语义 / limited stock semantics**

* 买入扣减；卖出回收补货，补货上限取 `max(配置上限, 当前值)`。
* `/bmcs stock set <键> <数量>` 的设定**不会被重载或重启吃掉**；
  只有当你把配置里的 `stock` 上限改小（例如 100 → 10）时才会收紧，并在日志里提示。
* 命令：`/bmcs stock list | set <键> <数量> | reset <键>`（权限 2）。
* *Buying decrements stock; selling restocks up to `max(configured cap, current)`.
  `/bmcs stock set` survives reloads and restarts; only lowering the configured cap
  tightens it (with a log line).*

**排查用的 debug 命令 / diagnostics (permission level 2)**

```text
/bmcs debug item <物品id>   本地化名称链路（翻译键 / 是否命中语言表 / 渲染结果）+ 在售情况
/bmcs debug sync            跑一遍完整快照同步：条目数、批数、组装/编码耗时、最大单包 KB
/bmcs debug config          配置统计、目录路径、分类优先级顺序、热重载监听状态
```

---

## 5. 从源码构建 / Building from source

**中文**

需要 **JDK 21**（Gradle 由 wrapper 自动下载）。两种依赖方式任选其一：

```bash
git clone <本仓库地址> && cd better-minecraft-shop-1.0.4

# 方式 A：把前置 jar 放进 libs/（离线可用，确切文件名见 libs/README.md）
./gradlew build            # Windows: gradlew.bat build

# 方式 B：libs/ 留空 → 自动从官方 maven 拉取前置（需要网络）
./gradlew build
```

产物：`build/libs/better-minecraft-shop-1.0.4.jar`（约 419 KB，其中模组图标约 273 KB；**不含**前置模组）。

开发运行 / 离线自检：

```bash
./gradlew runServer         # 首次需要 run/eula.txt 写 eula=true
./gradlew runClient         # 进游戏按 O 键或点侧边栏按钮

python tools/verify_assets.py .                                        # 配置/语言/侧边栏/架构不变量
java --class-path build/classes/java/main tools/VirtualGridCheck.java   # 虚拟滚动几何断言
python tools/check_jar.py build/libs/better-minecraft-shop-1.0.4.jar    # 产物内容检查
python tools/gen_stress_config.py run/config/better-minecraft-shop/shop_items 10000   # 10000 条压力配置
```

**English** — you need **JDK 21** (the Gradle wrapper downloads Gradle itself).
Either drop the two prerequisite jars into `libs/` (see `libs/README.md` for the exact
file names; fully offline), or leave `libs/` empty and let Gradle fetch them from the
official Maven repositories. The artifact lands in
`build/libs/better-minecraft-shop-1.0.4.jar` (~419 KB; the mod icon is ~273 KB of that,
prerequisites are not bundled).
`runServer` needs `run/eula.txt` with `eula=true`; `runClient` lets you press `O`.

---

## 6. 目录结构 / Project layout

```text
better-minecraft-shop-1.0.4/
├── LICENSE / README.md / CHANGELOG.md / .gitignore / .gitattributes
├── build.gradle / settings.gradle / gradle.properties / gradlew(.bat) / gradle/wrapper/
├── libs/README.md                         前置 jar 放这里（jar 不入库）
├── src/main/java/com/lyhyy2007/bmcs/
│   ├── BetterMinecraftShop.java           主类：附件 / 网络 / 命令 / 生命周期 / 热重载
│   ├── config/    多文件配置加载、优先级去重、商品条目
│   ├── currency/  深索币（Data Attachment + Codec）
│   ├── shop/      服务端商店：分批快照、增量补丁、库存 SavedData、热重载监听
│   ├── network/   StreamCodec 载荷（打开商店 / 动作 / 快照分片 / 增量补丁）
│   ├── command/   /bmcs 命令族（含 debug 子命令）
│   ├── util/      物品堆栈与本地化名工具
│   └── client/    FTB 侧边栏集成、键位、界面（虚拟滚动）、绘制原语
├── src/main/resources/
│   ├── META-INF/neoforge.mods.toml
│   ├── data/better_minecraft_shop/shop_items/*.json    默认配置模板（首启复制到 config）
│   └── assets/better_minecraft_shop/{lang,sidebar_buttons,textures}/
├── tools/         离线自检脚本（Python + 单文件 Java）
└── docs/          技术说明.md、验证记录.md、SDMShop-缺陷对照表.md、verification-logs/
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
* **Config:** `config/better-minecraft-shop/shop_items/**.json` — one folder with 8 default
  templates generated on first launch; every entry carries both `price` (Buy tab) and
  `sell_price` (Sell tab).
  Per-file and per-entry error isolation, 10 MB limit, UTF-8 BOM tolerant, cross-file duplicate
  resolution by category priority, and hot reload while the server runs.
* **Scales to 10 000+ entries:** virtual scrolling on the client (only the visible
  window is built), batched syncing (≤ 500 entries per packet — 21 packets and a 36 KB
  largest packet for 10 044 entries) and per-trade incremental patches.
* **License:** MIT for this mod; FTB Library (ARR) and the other prerequisites are only
  referenced, never bundled.

Build instructions, the config reference, all commands and the project layout are in the
Chinese/English sections above.
