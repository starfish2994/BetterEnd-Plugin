# Third-party components

Everything below is either a compile-time-only dependency (nothing of it is packaged into
`betterend-bukkit-*.jar`) or a separately licensed binary that is deliberately kept outside the
plugin jar. The MIT License in [LICENSE](LICENSE) covers this port's own code and the bundled
BetterEnd content; it does not relicense any of these.

## Compile-only dependencies — not redistributed

| Component | Version | License | Notes |
|---|---|---|---|
| [Paper API](https://github.com/PaperMC/Paper) `io.papermc.paper:paper-api` | `26.2.build.121-stable` | GPL-3.0, with MIT-licensed contributions | Provided by the server at runtime |
| [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) `net.momirealms:craft-engine-core` and `craft-engine-bukkit` | `26.8.1` | GPL-3.0 | Installed separately on the server as a plugin |

Both are `compileOnly` in `common/build.gradle.kts`, `nms/build.gradle.kts` and
`plugin/build.gradle.kts`: they are never bundled, and the plugin talks to CraftEngine through its
public API (`CraftEngineBlocks`, `CraftEngineItems`) plus one reflective NMS seam.

## Vendored binary — redistributed in this repository

| File | License | Copyright |
|---|---|---|
| [`plugin/libs/UltimateAdvancementAPI-Plugin-2.8.0.jar`](plugin/libs/UltimateAdvancementAPI-Plugin-2.8.0.jar) | LGPL-3.0-or-later | Copyright (C) 2021 fren_gor, EscanorTargaryen |

This jar is the owner's fork of [UltimateAdvancementAPI](https://github.com/frengor/UltimateAdvancementAPI)
(MC 26.2 NMS support, Folia-aware packet dispatch, the auto-layout `registerAdvancements`
overload). It is committed so that `./gradlew :plugin:jar` works from a fresh clone, and it is used
as `compileOnly` only: it is **not** shaded into the plugin jar and is **not** needed at runtime.
The jar's own `LICENSE` (GPL-3.0) and `NOTICE` (LGPL-3.0-or-later) travel inside it, and its shaded
libraries keep their own notices (`META-INF/.libs/`: Apache-2.0, bStats, Libby).

See [plugin/libs/README.md](plugin/libs/README.md) for how to replace it and when it is required.

## Test-only dependencies — not redistributed

| Component | Version | License |
|---|---|---|
| [JUnit 5](https://github.com/junit-team/junit5) (`junit-jupiter`) | `5.11.4` | EPL-2.0 |

## Upstream game content

The bundled CraftEngine resource pack and the biome datapack are derived from the Better End mod.
See [LICENSE.ASSETS](LICENSE.ASSETS) for the upstream MIT assets that are included, the three
CC BY-NC-SA 4.0 files that are deliberately excluded, and the hashes that make that verifiable.

## Trademarks

"Minecraft" is a trademark of Mojang Synergies AB. This project is not affiliated with, endorsed by,
or associated with Mojang Studios, Microsoft, the PaperMC project, Team BetterX, or the authors of
the original Better End mod.
