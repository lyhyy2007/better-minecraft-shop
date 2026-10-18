# 更新日志 · Changelog

本文件记录 noteworthy 变更；版本号与 `gradle.properties` 的 `mod_version` 一致。
*Notable changes; the version matches `mod_version` in `gradle.properties`.*

## [1.0.5] — 2026-10-18

### 中文

* **买入 / 卖出配置分目录**：`config/better-minecraft-shop/in/*.json` 只放**买入**商品
  （`price` + `stock`），`config/better-minecraft-shop/out/*.json` 只放**卖出**商品
  （`sell_price`）；两个目录平级、各自独立的分类与页签，取代原来的单层 `shop_items/`。
* **配置格式版本 1 → 2**：`in/` 里写 `sell_price`、`out/` 里写 `price` 都会被忽略并打日志；
  卖出价不再用 `price` 兜底。
* **不再输出默认配置**：首启只创建空的 `in/` 与 `out/`，不写任何默认 JSON；
  jar 里的 8 个默认模板移到 `docs/examples/legacy-shop_items/`（只作文档与迁移参考，不打进 jar）。
* **旧数据不自动迁移**：`shop_items/` 与旧单文件一律不读、不改、不删；启动日志给出绝对路径与
  迁移提示，并提供 `tools/migrate_shop_items.py`（默认预览，`--apply` 才写入，源文件保留）。
* **跨目录防刷钱**：同一件商品（物品 id + 附魔签名）在 `out/` 的 `sell_price` 高于 `in/` 的
  `price` 时，载入时压回 `price` 并记录日志。
* **新增游戏内「新建」编辑器**：买入页、卖出页右上角各一个「新建」按钮（搜索框左侧 6 px，
  仅权限等级 2 可见），点选背包格取模板、填价格 / 库存，保存到**选中分类**所在的 JSON 文件
  （临时文件 + `.bak` 备份 + 原子替换），成功后自动重载并刷新所有在线玩家。
* **新增「新建分类」与编辑模式**：右上角「新建分类」「新建商品」两个按钮并排（搜索框左侧，
  间距 6 px）；分类名支持中英文、留空自动 `_0`/`_1`…，**分类名即文件名**，重名拒绝；图标选择器
  可搜索、可分页，列出全部已注册物品（含模组物品）。**一个分类一个 JSON 文件**，文件内写
  `"editable": true`，分类 id 固定 `cat_N`（改名不改 id，库存不丢）。手写 JSON 分类在游戏内只读。
  左侧栏「编辑」开关打开后：左键删商品（确认框）、右键分类页签改名 / 换图标（改名同步重命名
  文件；多分类手写文件拒绝改名）。空分类保留并显示，方便往里加商品。
* **游戏内写入改为分类文件**：商品不再固定写 `custom.json`，而是写进选中分类所在的文件
  （`ShopCategoryWriter` + `CategoryFileIO`，`ShopItemWriter` 只负责追加/校验条目）。
* 版本号 1.0.4 → 1.0.5；`dist/` 保留 1.0.0 / 1.0.2 / 1.0.4，不删除不覆盖。
* **修复「商店数据尚未同步」误报**：从商店界面点「新建」时旧界面会被关闭并释放客户端快照，
  编辑器随即认为没有数据。现在切换到编辑器时保留快照，并把打开瞬间的目录版本一起带进编辑器。
* **新建入口收进编辑模式**：右上角只留「新建分类」（仅编辑模式显示，文案由「新建」改为「新建分类」）；「新建商品」也移到编辑模式下的商品网格里（第一格，未选中游戏内新建的分类时不显示），顶栏不再有它；编辑器内新增「从全部物品选择」（与分类图标选择器同款的可搜索全物品网格，含模组物品），与下方背包格并存。
* **修复编辑模式按钮文字不随状态刷新**：点「退出编辑」后按钮仍显示「退出编辑」。切换编辑模式时现在会同时刷新侧栏按钮文字。
* **修复分类页签文字显示不全**：页签宽度改为「左内边距 + 图标 + 间距 + 完整文字 + 右内边距」，有图标时不再截掉最后一个字；一行放不下时页签自动换行，页签栏随之加高、商品网格下移。
* **修复图标选择器无法换页**：`turnPage()` 会触发 `refreshWidgets()` → `addWidgets()` → 重新过滤时把页码重置回第 1 页（同一条链还会递归）。现在过滤只重算匹配表、不重建控件，页码显示也移到了标题行右侧。
* **修复「配置重载失败」误报**：新建分类后该文件还没有商品，旧逻辑把「解析出 0 件商品」当成配置损坏并拒绝应用。现在只有**所有文件都解析失败（一个分类都没得到）**才保留旧数据，空分类 / 空商店照常应用，重载后目录版本正常递增。
* **新建权限调整**：单人存档（集成服务器）任何人都能使用「新建」；专用服务器仍限 OP
  （权限等级 2）。客户端按钮显隐与服务端校验共用 `ShopItemEditorService.canEdit()`。

### English

* **Buy/sell config split into two folders**: `config/better-minecraft-shop/in/*.json`
  holds **buy** entries (`price` + `stock`), `config/better-minecraft-shop/out/*.json`
  holds **sell** entries (`sell_price`). Separate categories and tabs per direction,
  replacing the flat `shop_items/` folder.
* **Config format 1 → 2**: `sell_price` in `in/` and `price` in `out/` are ignored with a
  log line; the sell price no longer falls back to `price`.
* **No default config is emitted any more**: startup only creates empty `in/` and `out/`
  folders. The eight bundled templates moved to `docs/examples/legacy-shop_items/`
  (documentation only, not shipped in the jar).
* **Legacy data is never auto-migrated**: `shop_items/` and the old single file are not
  read, modified or deleted; a startup warning prints the absolute path and points at
  `tools/migrate_shop_items.py` (preview by default; `--apply` writes, sources stay).
* **Cross-folder anti money-loop**: when `out/`'s `sell_price` is above `in/`'s `price` for
  the same item (+ enchantment signature), it is clamped to `price` while loading.
* **New in-game editor**: a **New** button at the top-right of the Buy and Sell pages
  (6 px left of the search box, permission level 2 only). Pick an inventory stack, set
  price/stock, save into the **selected category's** JSON file (temp file + `.bak` backup +
  atomic replace); the shop reloads and refreshes every online player.
* **New category creation and edit mode**: the top-right corner now has **New category** and
  **New item** side by side (left of the search box, 6 px apart). A category name may be
  Chinese or English and becomes the file name (`_0`, `_1`… when blank; duplicates are
  rejected). The icon picker is searchable, paged and lists every registered item, modded
  ones included. **One category per JSON file**, marked `"editable": true`, with a stable
  `cat_N` id so renaming never resets stock. Hand-written categories stay read-only in game.
  **Edit mode** in the left bar lets you delete items (with a confirm dialog) and rename /
  re-icon a category tab (the file is renamed too; multi-category hand-written files refuse).
  Empty categories are kept and shown so items can be added to them.
* **In-game writes now target category files**: items are no longer forced into
  `custom.json` but appended to the selected category's file (`ShopCategoryWriter` +
  `CategoryFileIO`; `ShopItemWriter` only validates and appends the entry).
* Version bumped 1.0.4 → 1.0.5; `dist/` keeps 1.0.0 / 1.0.2 / 1.0.4 untouched.
* **Fixed the bogus "shop data is not synced yet" message**: opening the editor closed the
  shop screen, which released the client snapshot before the editor read it. The snapshot is
  now kept when switching to the editor, and the folder revision is captured at open time.
* **Create entries moved into edit mode**: the top-right button is now only "New category" (shown in edit mode only); "New item" left the header and became the first, fixed tile of the product grid in edit mode (hidden unless an in-game category is selected).
* **Fixed the edit-mode button label not refreshing**: after leaving edit mode the button still read "Exit edit mode". Toggling edit mode now also refreshes the sidebar button label.
* **Fixed truncated category tab labels**: a tab is now sized as left padding + icon + gap + full text + right padding, so the last character is no longer dropped when an icon is present; when a row is full the tabs wrap onto another row and the grid moves down.
* **Fixed icon picker paging**: turning a page re-ran `refreshWidgets()` → `addWidgets()` → the filter, which reset the page to 1 (the same chain could also recurse). Filtering now only recomputes the match list without rebuilding widgets, and the page counter moved to the title row.
* **Fixed a false "config reload failed"**: a freshly created category has no items yet, and the old guard treated "0 entries" as a broken config. Data is now only kept back when *every* file fails to parse (no category at all); empty categories and empty shops apply normally and the directory revision increments.
* **Editor permission widened for single-player**: everyone can use the editor in an
  integrated (single-player) server; dedicated servers still require OP (level 2). The
  button and the server check share `ShopItemEditorService.canEdit()`.

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
