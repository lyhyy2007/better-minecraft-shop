# SDMShop 3.3.1 缺陷 → 本模组修复对照

分析对象：`sdmshopa-neoforge-3.3.1.jar`（工作区内，未改动）。
方法：Vineflower 1.12.0 反编译全部 103 个 class + `javap -p -c` 校验指令级行为。
产物：`dsh-mc-neoforge-bmcs/_recon/`（`sdmshop_src/` 反编译源码、`javap_all.txt` 全量反汇编）。
下面的 `文件:行` 均指该反编译源码，引用的是**实际代码行为**，不是猜测。

> 结论先行：SDMShop 的架构不适用于本需求 —— 它**没有侧边栏**（入口是快捷键 `O` 与 `/sdmshop`），
> **自己不管钱**（完全委托外部模组 SDM-Economy），**商品目录由客户端上传**。
> 因此本模组是重新设计，只把它当“踩坑清单”。

---

## 一、崩溃类

| # | SDMShop 缺陷（含位置） | 触发条件 | 本模组的处理 |
|---|---|---|---|
| 1 | 买卖包用**列表下标**寻址且无边界检查：`BuyShopTovarC2S.java:31`、`SellShopTovarC2S.java:31` 直接 `TovarList.SERVER.tovarList.get(message.index)` | 客户端与服务端目录不同步时 `index = -1`，或任意越界值 → 服务端 tick 线程 `IndexOutOfBoundsException` | 交易包改为携带**商品 UUID**（配置键派生的稳定 v3 UUID），服务端 `ShopConfig.byId(uuid)` 查不到就回一条“已下架”，永不按下标索引 |
| 2 | 首次开服从不下发目录：`SDMShop.java:74-79`（`SDMTovarList.sdm` 未生成 → 反序列化被跳过 → `ShopNetworkUtils` 解引用 null） | 全新存档第一名玩家进服 | 目录来源于配置文件，服务端启动即完整加载；快照按需组装，不依赖“世界文件是否已存在” |
| 3 | `BuyingWindow.java:221` / `:117` 整除，`stackCount`、`cost` 可能为 0 | 命令类商品、或手改存档把 `cost` 改成 0 → 客户端 `ArithmeticException` | 配置校验阶段就拒绝 `price < 1`；界面不做任何会除零的计算（数量由 `IntTextBox` 输入，价格直接展示） |
| 4 | `TovarXP.java:42/64` `getXpNeededForNextLevel()` 可能为 0 | 等级为 0 的边界 / 负等级 | 本模组不做 XP 商品类型（如需，另开类型并单独做除零保护） |
| 5 | `TovarXP.java:78-90` 无界 `while (true)` 求等级 | 负数 XP 输入 → 服务端死循环 | 无此逻辑；所有循环都有明确上界（商品数、槽位数） |
| 6 | `TovarTab.java:22-26` 未做 `instanceof` 就 `(CompoundTag)` 强转 | 存档里出现非 compound 元素 | 配置是文本 JSON，解析走 `Codec`，类型不符即报错跳过该条，不会 ClassCastException |

## 二、坏档 / 数据损坏类

| # | SDMShop 缺陷（含位置） | 后果 | 本模组的处理 |
|---|---|---|---|
| 7 | 商品目录由客户端上传并原样落盘：`UpdateTovarDataC2S.java:29`、`UpdateTabDataC2S.java:31`（无权限、无结构校验、无大小限制） | 普通玩家即可改写商店并持久化；`CommandType` 商品还能借 `performPrefixedCommand` 变成任意命令执行面 | 客户端**没有任何写入目录的通道**；目录只来自服务端 `config/better-minecraft-shop/shop_items/**.json`（可多文件、可递归子目录，单文件 10MB 上限、单文件损坏只丢该文件） |
| 8 | 每笔交易都全量同步磁盘：`SDMShop.java:87-112` `saveData()`（`mkdir` 非 `mkdirs`、无临时文件+原子改名、无备份、`IOException` 只打日志） | 崩溃/断电时文件半写 → 商品与库存丢失 | 库存用原版 `SavedData`（由存档管线负责原子写入 `world/data/better_minecraft_shop_stock.dat`），并用 `Codec` 编解码 |
| 9 | 解析失败静默丢条目：`TovarList.java:33-38`、`ShopNBTUtils.java:73-82`（`result().orElse(EMPTY)`，无日志无隔离） | 一条坏数据让商品永久消失，且再保存后无法恢复 | 逐条解析：坏条目**精确报错 + 只跳过这一条**；整份文件损坏时**保留上一份可用数据**（`ShopConfig.load` 的 `loaded` 分支） |
| 10 | `ItemStack.EMPTY.setCount(n)` 会改到 JVM 级共享空堆栈：`ShopNBTUtils.java:73-82` | 全局空堆栈被污染 | 全程使用 `ItemStack.copy()` / `copyWithCount()`，从不对 `EMPTY` 赋值 |
| 11 | 交易失败无回滚（买：`TovarItem.java:38-52`；卖：`:54-102`） | 钱货不一致、可被刷 | 每个失败路径都有明确回滚（给货失败取回物品；结算异常取回物品/退回钱），见 `ShopTransaction.java` |
| 12 | `limit` 缺失默认 0，且 0 会被隐藏：`AbstractTovar.java:82` + `EntryPanel.java:50` | 字段缺失 → 商品从商店“消失” | 缺 `stock` 默认 `-1`（不限量）；`0` 明确表示“暂时售罄”，界面照常展示而不是隐藏 |
| 13 | 买、卖都扣同一个计数器：`TovarItem.java:49` 与 `:64` | 越卖越少，且无补货机制 | 买入扣减；卖出是回收，补回库存但**不超过配置上限**（`ShopStockManager#restock`） |
| 14 | 无数据版本号 | 格式一变就整体读不出来 | 配置有 `version` 字段；存档侧 `SavedData` 写 `data_version`；`Codec` 字段缺失有默认值，可平滑升级 |

## 三、物品堆叠 / 元数据类

| # | SDMShop 缺陷（含位置） | 后果 | 本模组的处理 |
|---|---|---|---|
| 15 | 买入发放 `count` 个**单件**堆栈、忽略模板 `getCount()`：`TovarItem.java:44-46`；而显示用 `getCount()`（`:163-164`）、计价/收货再乘一次（`:62`） | 显示数量、计价数量、发放数量、回收数量是四个不同的数 | 交易单位统一为「1 件」：模板恒为 `copyWithCount(1)`，数量只由请求的 `quantity` 决定，一条路径只有一个数量语义 |
| 16 | `(int)(count * getCount())` 把 `long` 窄化成 `int`：`TovarItem.java:62` | 溢出变负 → 回收逻辑反向“加物品” | 全链路 `long` 计算 + `Math.multiplyExact`；数量先夹取再使用 |
| 17 | 回收扫描 `getContainerSize()`（41 槽，含护甲）并 `setItem(a, EMPTY)`：`TovarItem.java:112/130` | **会把玩家身上穿着的装备卖掉** | `ItemStackUtil` 明确只允许主背包(0..35) + 副手(40)，护甲槽永不参与 |
| 18 | 回收用 `isSameItemSameComponents` 是对的，但**买入发放的模板可能已经在存档往返中丢组件**（见 #19） | 附魔 / 改名在重启后消失 | 配置解析在 `RegistryOps` 下做，且用**严格模式**（`result()`）：宁可报错跳过，也不接受“半个 ItemStack” |
| 19 | 存档用裸 NBT + `SINGLE_ITEM_CODEC` + 事后 `setCount`：`ShopNBTUtils.java:56-82` | 组件静默丢失、计数与交付不一致 | 物品数据只走 `DataComponentPatch.CODEC` / `ItemStack.STREAM_CODEC`；**没有任何原始 NBT 读写** |
| 20 | 商品标题每帧 `getDisplayName().getString()` 并做括号剥离：`TovarItem.java:106` | 样式/翻译丢失、每帧分配 | 展示名走**游戏内置本地化**（`custom_name` → `item_name` → 物品翻译键 → 缺翻译回退），配置里不再维护 `display_name`；名字跟随客户端语言 |
| 20b | 目录只有一份（单文件 / 单一来源），条目重复时无从取舍 | 同一物品被写两次时行为不确定 | 多文件 + 全局去重：判重键 = 物品 id + 组件签名（或显式 `variant`），冲突时按分类优先级取舍并在日志里说明；优先级表 `武器工具 > 五金 > 建材 > 石材 > 木材 > 原材料 > 食物 > 种子`，可用 `priority` 覆盖 |
| 21 | 标签类商品用线性扫描注册表重建 `TagKey`，找不到就留 null：`TovarItem.java:250-257` | `byTag=true` + `tag=null` → 回收时 NPE | 本版本不提供“按标签售卖”类型（如需，用 `TagKey.create` + 注册表查询并显式判空） |

## 四、并发 / 状态类

| # | SDMShop 缺陷（含位置） | 后果 | 本模组的处理 |
|---|---|---|---|
| 22 | 大量**跨端共享**的静态可变状态：`TovarList.SERVER/CLIENT`、`TovarTab.SERVER/CLIENT`、`BuyingWindow.tovar`、`Search.searchContent`、`AddProperties.*`、`SendConfigS2C.nbt` 等 18 处 | 单人/局域网（同 JVM）里客户端状态会被服务端逻辑读到；两个包实例互相覆盖 | 服务端状态集中在 `ShopManager` + `SavedData`；客户端快照 `ShopClientData` 只在客户端主线程访问；payload 无静态字段 |
| 23 | 界面里 `Thread.sleep(100)`：`ConfirmButton.java:39-43` 等 3 处 | 卡住客户端渲染线程 | 客户端只发一个包，不做任何阻塞等待 |
| 24 | `SendShopDataS2C` 每次交易全服广播 + 重开界面：`:50-55` | 带宽浪费、界面闪烁、`BuyingWindow.tovar` 为 null 时 NPE | 只向发起者回一份**单品增量补丁**（这件商品的新库存 + 我的余额）；界面已打开时只刷新数据（保留滚动/搜索/选中），不重建界面；全量快照按 500 条一批分批发送 |
| 25 | 交易靠 `context.queue(...)` 保证线程，但**没有任何权限校验** | 未授权写入 | 包处理统一走 `IPayloadContext.enqueueWork`（主线程）；目录写入只允许管理员通过 `/bmcs reload` 触发 |

## 五、依赖与元数据类

| # | SDMShop 缺陷 | 后果 | 本模组的处理 |
|---|---|---|---|
| 26 | `neoforge.mods.toml` 只声明 `neoforge` / `minecraft` / `architectury`，但字节码硬链接 `ftblibrary`(22 类)、`sdmeconomy`、`sdmcore`、`sdmuilibrary` | 缺前置时以 `NoClassDefFoundError` 崩溃而不是给出依赖提示 | 显式声明 `ftblibrary` 与 `architectury` 为 `type="required"`，版本范围明确 |
| 27 | 混入 loom/Architectury 构建产物（`sdmshopa-common-common-refmap.json` 用 intermediary `class_2561`） | 混入映射不一致的 mixin 目标 | 本模组不使用 mixin，也不需要 Architectury 的构建插件 |

---

## 六、从 SDMShop 保留的可取之处

1. **组件精确匹配**（`isSameItemSameComponents`）—— 思路正确，本模组沿用并强化（护甲槽排除、数量显式夹取）。
2. **界面用 FTB Library 组件搭建** —— 本模组沿用同一框架（`BaseScreen` / `Panel` / `TextBox` 等）。
3. **商品条目带稳定 UUID**（`AbstractTovar.uuid`）—— 本模组同样给每条商品稳定 UUID，
   并且**把它用在网络寻址上**（SDMShop 存了却只用下标）。
4. **SNBT/JSON 配置驱动**的思路 —— 本模组改为服务端 JSON + Codec 校验。

---

## 七、本模组自己在实测中踩到并修掉的坑（与 SDMShop 无关）

列在这里是因为它们同样属于「兼容性 / 健壮性」问题，而且都是**先看到运行时现象再修**的，
细节与原始日志见 `docs/验证记录.md` 第 6 节。

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 28 | 旧单文件与新默认目录共存时，同一个 `minecraft:stone` 同时在 `blocks` 与 `stone` 两个页签上架（目录 62 条，离线脚本按全局去重算出来是 44 条） | 第一版判重表是**每个分类一张**，只有分类内重复才会被发现 | 全局 `DedupRegistry`（跨文件、跨分类）；冲突时把条目归属从低优先级分类转移到高优先级分类，日志给出取舍 |
| 29 | 10000 条压力配置**全部**被跳过，报 `MalformedJsonException ... missed input: {"minecraft:custom_name":"压力测试 #1"}` | `custom_name` / `item_name` / `lore` 用 `ComponentSerialization.FLAT_CODEC`：值必须是**字符串**，且这个字符串会被 `JsonParser.parseString`（lenient）**再当 JSON 文档解析一次**；`#` 是 Gson 的非法字面量字符。反直觉的是写成对象 `{"text":...}` 也不行（`Not a string`） | 解析前规范化：能当 JSON 文档解析的字符串原样保留，否则包成 `{"text":"..."}` 的 JSON 文档字符串；对象 / 数组则序列化成 JSON 文档字符串 —— 三种写法都能用 |
| 30 | `bmcs stock set weapons_tools.1 5`（配置上限 4）在下一次 `/bmcs reload` 或重启后被**悄悄改回 4** | `syncWithConfig()` 每次都夹 `min(现有值, 配置初始值)`，无法区分「管理员放量」与「玩家卖回补货」 | SavedData v2 额外记录「上次看到的配置初始值」：上限没变则保留当前值、上限变了才收紧；`restock` 上限取 `max(配置值, 当前值)`，卖回不会冲掉放量 |
| 31 | 一份 10000 条全部写错的配置把 10000 行明细写进日志，单次运行日志涨到 10 MB | 逐条报错没有上限 | 逐条报错 / 告警统一走 `logEntry(...)`：只打印前 40 条，之后折叠成一行提示（总数由「本次加载跳过 N 条」汇总） |
| 32 | 用 PowerShell / 记事本编辑出的配置文件带 UTF-8 BOM（`EF BB BF`） | BOM 会被当成 JSON 的第一个字符 | 解析前剔除行首 BOM（本机实测：带 BOM 的文件能正常解析） |
