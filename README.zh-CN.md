# BetterEnd —— Bukkit / Paper / Folia 服务端插件版

**BetterEnd** 把 Minecraft 模组 **Better End** 带到了原版服务端：末地被重新生成为 BetterEnd 的
岛屿地形，包含 **27 个群系**、洞穴、矿物、九种树木、地表植被，以及原版末地黑曜石柱的材质重绘。
不需要 Fabric、不需要模组加载器、客户端也不需要装模组 —— 自定义方块、物品、模型、配方与语言文本
由 [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 提供，本插件负责世界生成与游戏逻辑。

| | |
|---|---|
| **本项目是什么** | 用 Paper 插件（`betterend-bukkit`）实现 **Better End** 模组的世界内容 |
| **原模组** | [Better End](https://modrinth.com/mod/betterend)（Team BetterX）— [源码](https://github.com/quiqueck/BetterEnd) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/betterend) |
| **本插件仓库** | [IOVEYOUMC0/BetterEnd-Plugin](https://github.com/IOVEYOUMC0/BetterEnd-Plugin) —— 移植作者 **HuiDu_OwO** |
| **服务端** | Paper `26.2`（支持 Folia），Java 25 |
| **必需前置** | [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 26.8.1 及以上 |
| **可选前置** | [UltimateAdvancementAPI](https://github.com/frengor/UltimateAdvancementAPI)（进度标签页） |
| **开源协议** | MIT —— 见 [LICENSE](LICENSE)、[LICENSE.ASSETS](LICENSE.ASSETS)、[THIRD-PARTY.md](THIRD-PARTY.md) |

本项目与 Mojang、Microsoft、PaperMC 项目组、Team BetterX 均无隶属关系。

---

## 1. 这是什么，不是什么

这是**为服务端重新实现**的版本，不是给模组套一层壳。原版 Better End 是 Fabric 模组：Java 代码挂在
客户端/服务端上，材质由客户端从模组 jar 里读取。这套东西在 Bukkit 服务端一行都跑不起来，所以本项目：

* 用 Bukkit 的 `ChunkGenerator` API 与 CraftEngine 的运行时方块注册表**重新实现世界生成**
  （地形、群系、地表、洞穴雕刻、树木、植被、矿物、末地黑曜石柱重绘）；
* 把模组的方块、物品、模型、配方与翻译整理成一个 **CraftEngine 资源包**
  （安装到 `plugins/CraftEngine/resources/betterend`），由插件负责安装与升级；
* 通过 Paper 的插件 bootstrapper API 把 27 个末地群系注册成**数据包**，保证群系注册表在世界加载前
  就已填充；
* 保留原点附近的原版末地，因此末影龙战斗、返回传送门与十根黑曜石柱依然正常工作。

**本项目不会做的事：** 不会自动改动已有世界或原版方块；目前不生成结构（还没有末地城）；不添加模组的
生物；不包含音效与音乐。详见 [§5 移植进度](#5-移植进度)。

## 2. 环境要求

| | |
|---|---|
| 服务端 | Paper `26.2`（`api-version: '26.2'`）或 Folia |
| Java | 25 |
| CraftEngine | 26.8.1 以上，作为插件安装 |
| 世界 | 必须是末地（`Environment.THE_END`），其它维度不会生效 |

### 一个**必须**修改的 CraftEngine 设置

在 `plugins/CraftEngine/config.yml` 中设置：

```yaml
misc:
  delay-configuration-load: false
```

CraftEngine 默认是 `true`，会把方块注册推迟到服务器第一个 tick —— 那时 Paper **已经**生成完出生点
区块。在这个时间窗口内创建的 BetterEnd 世界会被永久固化成普通末地石，而区块不会重新生成。保持默认值时，
插件会**拒绝生成并打印原因**，让世界回退到原版，而不是静默产出一个坏档。

## 3. 安装

1. 把 `craft-engine-bukkit-*.jar`（CraftEngine）和 `betterend-bukkit-*.jar` 放进 `plugins/`。
   CraftEngine 必须在 BetterEnd **之前**加载 —— 插件描述文件里已经声明了这个依赖。
2. 设置 `misc.delay-configuration-load: false`，重启服务器。
3. 首次启动时插件会把自带资源包复制到 `plugins/CraftEngine/resources/betterend`，并写入版本戳。
   要自定义内容请另建一个 CraftEngine 资源包：直接改
   `plugins/CraftEngine/resources/betterend` 里的文件会在升级时被覆盖；把
   `plugins/BetterEnd/config.yml` 里的 `craftengine.install-bundled-pack` 设为 `false`
   可以接管该目录，此后由你自己维护。
4. 在 `bukkit.yml` 里用生成器创建末地世界：

   ```yaml
   worlds:
     world_the_end:
       generator: BetterEnd:betterend
   ```

   已有世界不会被改动。请务必在 CraftEngine 注册完方块之后再创建世界（即按上面的设置正常启动即可；
   启动后用 `/mv create` 也可以）。
5. 进游戏确认：启动日志会点名四种“坏档条件”；`/datapack list` 能看到 `BetterEnd/biomes` 数据包；
   F3 显示 `betterend:<群系>`。
6. 可选：把 `UltimateAdvancementAPI.jar` 放进 `plugins/` 即可获得 BetterEnd 进度标签页；不装的话
   插件表现完全一致，只是没有该标签页。

`plugins/BetterEnd/config.yml` 内有完整注释：岛屿层几何参数、原点中央岛、各矿物每区块数量、进度采样
间隔。注意**世界一旦生成，地形参数就会被冻结** —— 之后再改会让新旧区块之间出现断崖，且没有任何机制能
修复，只能删档。

## 4. 从源码构建

```bash
./gradlew :plugin:jar :common:test
```

* 产物：`plugin/build/libs/betterend-bukkit-0.1.0-SNAPSHOT.jar`（`common`、`nms` 的编译输出会合并进去）。
* 测试：JUnit 5，18 个测试类共 **226 个测试**（173 个 `@Test` 方法加参数化用例），测试 worker 堆上限 2 GB。
* 工具链：Java 25（`options.release = 25`），Gradle Wrapper 9.7.0。
* `plugin/libs/UltimateAdvancementAPI-Plugin-2.8.0.jar` 已随仓库提供，以便全新克隆即可编译；不想用这个
  分支的话见 [plugin/libs/README.md](plugin/libs/README.md)。

## 5. 移植进度

### 已完成并可用

| 模块 | 已实现内容 |
|---|---|
| 地形 | 基于模组自带 OpenSimplex 噪声场的三层岛屿、SDF 圆锥体、三层密度八度、顶部渐隐、连片覆盖率；原点 1024 格空域与 (0, 64, 0) 处 ×1.3 的中央岛 |
| 群系 | **27 / 27 全部实装**，每个群系有独立的雾、天空、水色、粒子与音乐，以及独立地表方块；`BiomePlacement` + `HexBiomeMap` 复刻了模组的六边形格点布局 |
| 原版末地 | 原点周围 384 格保持原版 `THE_END`，因此原版自行放置十根带水晶的黑曜石柱，末影龙仍能回血；插件重绘这些柱子（flavolite 柱身、四层柱顶、三层柱底、1/24 概率哭泣的黑曜石）并修复柱芯 |
| 洞穴 | 模组的两种雕刻器都在 `generateNoise` 内运行（`CaveCarver` 圆洞、`TunnelCarver` 隧道，以及 `cave_surface_coat` 覆盖层），只在六个洞穴群系生效；Bukkit 自带雕刻器保持关闭 |
| 矿物 | flavolite 层（6/区块）、violecite（8）、thallasium（24 矿脉，宽 8）、末影矿（12 矿脉，宽 4）、琥珀（*amber_land* 60 矿脉）、龙骨（*dragon_graveyards* 24 矿脉），全部可配置 |
| 树木 | **9 种**，且各自出现在模组规定的群系中：lacugrove、dragon_tree、lucernia、tenanea、pythadendron、jellyshroom、umbrella_tree、gigantic_amaranita、mossy_glowshroom |
| 植被 | 模组的植被表（51 行）及其调色板与放置规则 |
| 进度 | 定义了 29 条进度；玩家当前真的能完成的那部分已通过 UltimateAdvancementAPI 注册，其余随对应系统移植后自动回归 |

### 尚未移植

* **末地城与所有结构** —— `shouldGenerateStructures()` 返回 `false`，因此没有鞘翅、没有潜影贝。
* **决定大部分进度的机制**：永恒传送门、末地村庄、注魔仪式、等级铁砧、模板战利品、末地石熔炉
  （合金冶炼）。29 条进度中有 24 条因此暂不显示。
* **到达平台、返回传送门、黑曜石柱放置** —— 这些归 Bukkit 管；末影龙战斗、世界出生点与种子属于服务端，
  不是插件。
* **模组的生物、音效与音乐唱片音频** —— 没有任何实体代码，也没有 `.ogg` 文件。
* **方块**：目前是模组方块 id 的一个持续增长的子集。缺少的台阶、楼梯、墙、告示牌等父母模型见
  [MIGRATION.md](MIGRATION.md)。

[MIGRATION.md](MIGRATION.md) 是本项目的设计日志：每个子系统为什么这样做、做过哪些测量、哪些是刻意不做的。

## 6. 架构

```
betterend-bukkit
├── common/   与 Bukkit 生命周期无关：生成器、群系提供器、地形、雕刻器、树木、植被、
│             填充器、配置、CraftEngine 桥接、NMS 加载器
├── nms/      版本隔离层，运行时通过反射绑定（v26_2 平台）
└── plugin/   插件描述文件、主类、进度系统、自带 CraftEngine 资源包、群系数据包
```

* **生命周期。** `paper-plugin.yml` 是唯一的描述文件。`onLoad()` 读取配置并安装资源包；
  `onEnable()` 在缺少 CraftEngine 或资源包时拒绝启动，加载 NMS 平台、注册原版末地守卫，并把进度注册
  推迟到 `ServerLoadEvent`；`getDefaultWorldGenerator` 只在生成器 id 为 `betterend` 且资源包方块已注册
  时才返回生成器。
* **群系数据包。** `BiomeDatapack` 实现 Paper 的 `PluginBootstrap`，在
  `LifecycleEvents.DATAPACK_DISCOVERY` 上注册数据包。必须是 bootstrapper：群系注册表在
  `WorldLoader.load` 期间就已冻结，那时插件还没加载 —— 这也是为什么安装/更新群系需要完全重启，`/reload`
  无效。
* **CraftEngine 负责内容。** 方块、物品、模型、配方、语言都在
  `craftengine/betterend/configuration/*.yml` 与 `.../resourcepack/` 里 —— 37 个配置文件加 1614 个
  资源包文件（906 张 PNG、690 个方块/物品模型）。Java 只调用少数 CraftEngine 入口
  （`CraftEngineBlocks`、`CraftEngineItems`、`ImmutableBlockState`），从不重复实现一个方块。
* **NMS。** `NmsLoader` 匹配 `Bukkit.getBukkitVersion()`，再用反射加载
  `org.betterx.betterend.bukkit.nms.v26_2.Platform`；其它版本会退化为 “BetterEnd Lite”。目前这个隔离层
  只有 16 行 —— 暂时不需要任何 NMS 钩子。
* **Folia。** `folia-supported: true`。`RegionSchedulerAdapter` 封装了 region / entity / global 三种调度器，
  进度监听跑在玩家自己的 region 上；世界生成是无状态的（每个种子一个 `IslandField`/`BiomePlacement`，
  填充器无状态），因此并发生成是安全的。

## 7. 致谢

**原模组 —— “Better End”**（`betterend`），Team BetterX / BetterX Team：

* **Quiqueck** —— 代码、项目负责人
* **paulevs**（paulevsGitch）—— 代码与美术；上游 `LICENSE` 中的 MIT 版权持有人
* **Bulldog83** —— 代码与美术
* **Edos** —— 建筑
* **Yuki** —— 美术
* **Seaward** —— 美术
* **Firel** —— 音乐

链接：[GitHub](https://github.com/quiqueck/BetterEnd) ·
[Modrinth](https://modrinth.com/mod/betterend) ·
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/betterend) ·
[问题反馈](https://github.com/quiqueck/BetterEnd/issues)

**Bukkit/Paper/Folia 移植 —— BetterEnd-Plugin：** **HuiDu_OwO**。

**上游材质协议说明。** 上游代码为 MIT（Copyright (c) 2020 paulevsGitch），另有**恰好三个**资源是
CC BY-NC-SA 4.0（署名 “Team BetterX”）。这三个文件已被**刻意排除**在本仓库、自带资源包与构建产物之外：
`textures/gui/infusion.png`、`icon_updater.png`、`lang/de_de.json`。[LICENSE.ASSETS](LICENSE.ASSETS)
中列出了它们及各自的 SHA-256，随时可以复核这一排除。其余来自上游的内容继续沿用上游 MIT 协议与版权声明。

## 8. 许可

本项目以 **MIT 协议**发布 —— 见 [LICENSE](LICENSE)。其中同时保留上游版权声明
（Copyright (c) 2020 paulevsGitch）与移植作者自己的声明。不属于 MIT 的第三方组件列在
[THIRD-PARTY.md](THIRD-PARTY.md)。
