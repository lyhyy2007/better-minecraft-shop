# libs/ —— 前置模组编译依赖（可选）

## 中文

本目录用于放**编译期**需要的前置模组 jar。本仓库**不包含**这些 jar（FTB Library 为
All Rights Reserved，不随本仓库分发），`.gitignore` 也忽略 `libs/*.jar`。

两种构建方式任选其一：

**方式 A（推荐，离线 / 内网可用）** —— 手工下载两个 jar 放进本目录，文件名必须完全一致：

| 文件 | 下载地址 |
|---|---|
| `ftb-library-neoforge-2101.1.37.jar` | https://maven.ftb.dev/releases/dev/ftb/mods/ftb-library-neoforge/2101.1.37/ |
| `architectury-13.0.6-neoforge.jar` | https://maven.architectury.dev/dev/architectury/architectury-neoforge/13.0.6/ |

> Architectury 官方文件名形如 `architectury-13.0.6-neoforge.jar`；
> 若下载到的是 `architectury-neoforge-13.0.6.jar`，请重命名成上表中的名字。

**方式 B（需要网络）** —— 不放任何 jar，直接 `./gradlew build`：
`build.gradle` 检测到本目录为空时会自动改用官方 maven 坐标
（仓库已配在 `settings.gradle` 的 `FTB Maven` / `Architectury Maven`）：
`dev.ftb.mods:ftb-library-neoforge:2101.1.37`、`dev.architectury:architectury-neoforge:13.0.6`。

游戏内运行模组时，玩家同样需要自行安装这两个前置模组（见根目录 `README.md`）。

## English

This folder holds the **compile-time** prerequisite jars. This repository does **not**
ship them (FTB Library is All Rights Reserved), and `.gitignore` excludes `libs/*.jar`.

Pick either way to build:

**Option A (offline friendly)** — download the two jars into this folder with *exactly*
these file names:

| File | Where |
|---|---|
| `ftb-library-neoforge-2101.1.37.jar` | https://maven.ftb.dev/releases/dev/ftb/mods/ftb-library-neoforge/2101.1.37/ |
| `architectury-13.0.6-neoforge.jar` | https://maven.architectury.dev/dev/architectury/architectury-neoforge/13.0.6/ |

**Option B (needs network)** — put nothing here and just run `./gradlew build`.
When this folder is empty, `build.gradle` falls back to the official Maven coordinates
(repositories configured in `settings.gradle`).

Players also need both prerequisites installed to run the mod in game — see `README.md`.
