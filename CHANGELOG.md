# 更新日志 · Changelog

本文件记录 noteworthy 变更；版本号与 `gradle.properties` 的 `mod_version` 一致。
*Notable changes; the version matches `mod_version` in `gradle.properties`.*

## [1.0.4] — 2026-10-18

### 中文

* **配置回到第一版的单层结构**：删除 `shop_items/in/`、`shop_items/out/` 两个目录，
  统一读 `config/better-minecraft-shop/shop_items/**.json`，一条商品里同时写 `price` 与 `sell_price`。
* 界面左侧的「买入」「卖出」两个栏位保持不变：买入栏位按 `price` 结算并显示 `stock` 限量，
  卖出栏位按 `sell_price` 结算且不限量；两个栏位读同一份配置，价格互不影响。
* `sell_price` 不写（或 0）时用 `price` 兜底；`sell_price` 高于 `price` 时压回 `price`（防刷钱）。
* 清理所有 `in/`、`out/` 引用：目录常量、`ShopSide.directory()`、`/bmcs debug config` 的两条路径行、
  语言键与默认配置生成逻辑；卖出条目在 `byKey` 里的前缀由 `out.` 改为 `sell.`。
* 背包侧边栏「商店」按钮图标换成 `大肥鱼.png`（jar 根 `shop_icon.png` 同步为同一张图）。
* 版本号 1.0.2 → 1.0.4；历史构建产物仍保留在 `dist/`。

### English

* **Config is back to the v1 flat layout**: the `shop_items/in/` and `shop_items/out/` folders are
  gone; everything is read from `config/better-minecraft-shop/shop_items/**.json`, where one entry
  carries both `price` and `sell_price`.
* The left-hand Buy / Sell tabs stay: Buy settles at `price` and shows `stock`, Sell settles at
  `sell_price` with no stock limit. Both tabs read the same config.
* `sell_price` falls back to `price` when missing (or 0), and is clamped to `price` when higher.
* All `in/` / `out/` references removed (folder constants, `ShopSide.directory()`, debug command
  path lines, lang keys, default-config generation); sell keys now use the `sell.` prefix.
* The inventory sidebar button icon is now `大肥鱼.png` (jar-root `shop_icon.png` matches it).
* Version bumped 1.0.2 → 1.0.4; previous builds stay in `dist/`.

## [1.0.2] — 2026-10-18

### 中文

* 移除 `max_stack`（单次交易数量上限）：上限固定取物品堆叠上限（≤64），配置里写了也会被忽略。
* 移除 `variant`（同物品多卖法区分）：判重键只按「物品 id + 附魔签名」。
* `components` 收窄为**只支持附魔**（`minecraft:enchantments` / `minecraft:stored_enchantments`）；
  改名、lore 等其它组件会被忽略并打印提示，原先的 `custom_name` / `lore` 兼容规范化随之下线。
* 版本号 1.0.0 → 1.0.2；旧版构建产物保留为 `dist/better-minecraft-shop-1.0.0.jar`。

### English

* Removed `max_stack`: the per-trade limit is the item's stack size (≤64) now.
* Removed `variant`: the dedup key is `item` + enchantment signature only.
* `components` is **enchantments only**; renaming, lore and other components are ignored with a log line.

## [1.0.0] — 2026-10-12

### 中文

**核心能力**

* 单一货币「深索币」：NeoForge Data Attachment 存储（随玩家存档、死亡不掉落），
  命令 `/bmcs balance|pay|set|add`。
* 商店入口：FTB Library 侧边栏按钮（背包左上角）、可改键位（默认 `O`）、`/bmcs shop`。
* 数据驱动配置：`config/better-minecraft-shop/shop_items/**.json`，任意数量文件、可递归子目录，
  首启自动生成 8 个默认模板；兼容旧版单文件。
* 商店界面：FTB Library 组件框架 + 自研绘制原语（圆角/渐变/圆/三角/描边）、
  分类页签、本地化名、搜索、数量输入、买/卖按钮。
* 限量库存：世界级 `SavedData`，买入扣减、卖出回收补货；`/bmcs stock list|set|reset`。

**健壮性与规模**

* 单文件隔离（坏文件只丢该文件）、单条目隔离（坏条目只丢该条）、单文件 10MB 上限、
  UTF-8 BOM 兼容、逐条报错折叠（前 40 条明细 + 汇总）。
* 跨文件/跨分类按分类优先级去重（武器工具 > 五金 > 建材 > 石材 > 木材 > 原材料 > 食物 > 种子，
  可用 `priority` 覆盖）。
* `WatchService` 配置热重载（1.5 秒防抖 + 回主线程重载）。
* 物品展示名走游戏内置本地化（移除 `display_name`）；
  名字类组件（`custom_name` / `item_name` / `lore`）的三种写法都能用（含 `#` 等特殊字符）。
* 10000+ 条目：客户端虚拟滚动（只建可视窗口控件）、服务端分批同步（每批 ≤500 条）、
  交易后只发单品增量补丁、按目录版本缓存模板与分类数据。

**实测数据**（10044 条，3.0 MB 配置）

* 配置解析 + 去重 217 ms；快照组装 8 ms；编码 74 ms；21 批；最大单包 36 KB；
  客户端本地化名索引 6 ms。

**许可**

* 本模组以 MIT 发布（见 `LICENSE`）；FTB Library（ARR）等前置仅通过公开 API 集成，不随仓库分发。

### English

**Features** — single DeepCoin currency stored as a NeoForge data attachment
(`/bmcs balance|pay|set|add`); shop entry via the FTB Library sidebar button, a rebindable
`O` key, or `/bmcs shop`; data-driven config under
`config/better-minecraft-shop/shop_items/**.json` (any number of files, recursive, 8 default
templates generated on first launch, legacy single-file still supported); FTB Library based
UI with custom drawing primitives, category tabs, localized names, search, quantity box and
buy/sell buttons; world-level `SavedData` stock with buy-decrement and sell-restock.

**Robustness & scale** — per-file and per-entry error isolation, 10 MB per-file limit,
UTF-8 BOM tolerance, log folding (first 40 detailed lines + summary), cross-file
duplicate resolution by category priority, `WatchService` hot reload, item names from the
game's localization (no `display_name`), three accepted forms for name components,
virtual scrolling for 10 000+ entries, batched syncing (≤ 500 entries per packet) and
per-trade incremental patches.

**Measured** (10 044 entries, 3.0 MB of config) — parse + dedup 217 ms, snapshot assembly
8 ms, encoding 74 ms, 21 batches, largest packet 36 KB, client-side name index 6 ms.

**License** — MIT for this mod; FTB Library (ARR) and other prerequisites are referenced
through their public APIs only and are not redistributed.
