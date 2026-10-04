# BetterEnd — Bukkit / Paper / Folia plugin port

**BetterEnd** brings the Minecraft mod **Better End** to a vanilla-ish server as a pure
server-side plugin: the End is regenerated with BetterEnd's island terrain, 27 biomes, caves, ores,
nine tree species, flora and a reskin of the vanilla End spikes. No Fabric, no mod loader, no client
mod — the custom blocks, items, models, recipes and language strings are owned by
[CraftEngine](https://github.com/Xiao-MoMi/craft-engine), and this plugin owns the world generation
and the game logic.

| | |
|---|---|
| **What it is** | A Paper plugin (`betterend-bukkit`) implementing the world content of the **Better End** Minecraft mod |
| **Original mod** | [Better End](https://modrinth.com/mod/betterend) by Team BetterX — [source](https://github.com/quiqueck/BetterEnd) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/betterend) |
| **This port** | [IOVEYOUMC0/BetterEnd-Plugin](https://github.com/IOVEYOUMC0/BetterEnd-Plugin) — author **HuiDu_OwO** |
| **Server** | Paper `26.2` (Folia-supported), Java 25 |
| **Hard dependency** | [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 26.8.1+ |
| **Optional** | [UltimateAdvancementAPI](https://github.com/frengor/UltimateAdvancementAPI) for the advancement tab |
| **License** | MIT — see [LICENSE](LICENSE), [LICENSE.ASSETS](LICENSE.ASSETS), [THIRD-PARTY.md](THIRD-PARTY.md) |

Not affiliated with Mojang, Microsoft, the PaperMC project or Team BetterX.

---

## 1. What it is, and what it is not

This is a **re-implementation for servers**, not a wrapper around the mod. The original Better End
is a Fabric mod: it ships Java code that hooks Minecraft's client and server, and assets that the
client loads from the mod jar. None of that works on a Bukkit server, so this project:

* re-implements the world generation in Bukkit's `ChunkGenerator` API and CraftEngine's runtime
  block registry (terrain, biomes, surfaces, carvers, trees, flora, ores, vanilla-spike reskinning);
* turns the mod's blocks, items, models, recipes and translations into a **CraftEngine pack**
  (`plugins/CraftEngine/resources/betterend`) that the plugin installs and keeps up to date;
* ships the 27 End biomes as a **datapack** registered through Paper's plugin-bootstrapper API, so
  the biome registry is populated before worlds load;
* keeps the vanilla End intact around the origin, so the dragon fight, the exit portal and the ten
  obsidian pillars still work.

**What it does not do:** it never edits existing worlds or vanilla blocks on its own, it does not
generate structures (no End Cities yet), it does not add the mod's mobs, and it ships no sounds or
music. See [§5 Porting status](#5-porting-status).

## 2. Requirements

| | |
|---|---|
| Server | Paper `26.2` (`api-version: '26.2'`), or Folia |
| Java | 25 |
| CraftEngine | 26.8.1 or newer, installed as a plugin |
| World | The End environment — the generator is a no-op elsewhere |

### CraftEngine setting that is *not* optional

Set this in `plugins/CraftEngine/config.yml`:

```yaml
misc:
  delay-configuration-load: false
```

CraftEngine ships `true`, which defers block registration to the first server tick — *after* Paper
has generated the spawn chunks. A BetterEnd world created in that window is baked permanently as
plain end stone, and chunks are never regenerated. With the default in place the plugin refuses to
generate and logs the reason instead of silently producing a broken world, and the world falls back
to vanilla.

## 3. Install

1. Put `craft-engine-bukkit-*.jar` (CraftEngine) and `betterend-bukkit-*.jar` in `plugins/`.
   CraftEngine must load **before** BetterEnd — the plugin descriptor already declares that
   dependency.
2. Set `misc.delay-configuration-load: false` in `plugins/CraftEngine/config.yml` and restart.
3. On first start the plugin copies its bundled pack to
   `plugins/CraftEngine/resources/betterend` and stamps it with the plugin version. Customise
   BetterEnd from a **separate** CraftEngine pack: edits made inside
   `plugins/CraftEngine/resources/betterend` are overwritten on upgrade. Set
   `craftengine.install-bundled-pack: false` in `plugins/BetterEnd/config.yml` to take ownership of
   that folder instead.
4. Create an End world with the generator, in `bukkit.yml`:

   ```yaml
   worlds:
     world_the_end:
       generator: BetterEnd:betterend
   ```

   Existing worlds are never touched. Create the world *after* CraftEngine has registered its
   blocks (i.e. a normal start with the setting above; `/mv create` after startup also works).
5. Verify in game: the startup log names any of the four broken-world conditions; `/datapack list`
   shows the `BetterEnd/biomes` pack; F3 reads `betterend:<biome>`.
6. Optional: drop `UltimateAdvancementAPI.jar` in `plugins/` to get the BetterEnd advancement tab.
   Without it the plugin loads exactly the same, minus the tab.

`plugins/BetterEnd/config.yml` is documented inline: island layer geometry, the pinned central
island, ore densities per chunk, and the advancement sampler. **Terrain keys are frozen once a
world exists** — changing them afterwards seams the landscape, and nothing repairs it.

## 4. Building from source

```bash
./gradlew :plugin:jar :common:test
```

* Jar: `plugin/build/libs/betterend-bukkit-0.1.0-SNAPSHOT.jar` (the `common` and `nms` outputs are
  merged into it).
* Tests: JUnit 5, **226 tests** (173 `@Test` methods plus parameterised cases) across 18 classes, run
  with a 2 GB test worker.
* Toolchain: Java 25 (`options.release = 25`), Gradle wrapper 9.7.0.
* `plugin/libs/UltimateAdvancementAPI-Plugin-2.8.0.jar` is vendored so the build works from a fresh
  clone; see [plugin/libs/README.md](plugin/libs/README.md) if you would rather not use it.

## 5. Porting status

### Ported and shipping

| Area | What is implemented |
|---|---|
| Terrain | Three island layers on the mod's own OpenSimplex field, SDF cone shapes, three density octaves, top fade, coherent coverage; 1024-block spawn void and the pinned central island at (0, 64, 0) × 1.3 |
| Biomes | **27 of 27**, with per-biome fog/sky/water/particles/music and per-biome surface blocks. `BiomePlacement` + `HexBiomeMap` reproduce the mod's hex-lattice placement |
| Vanilla End | A 384-block disc around the origin stays vanilla `THE_END`, so vanilla places its own ten pillars with crystals and the dragon still heals. The plugin reskins the spikes (flavolite ribs, four-layer crown, three-layer foot, 1-in-24 crying obsidian) and repairs the core |
| Caves | Both of the mod's carvers run inside `generateNoise` (`CaveCarver` round caverns, `TunnelCarver`, plus the `cave_surface_coat` band) in the six cave biomes; Bukkit's own carvers stay off |
| Ores | flavolite layers (6/chunk), violecite (8), thallasium (24 veins, size 8), ender (12 veins, size 4), amber (60 veins in *amber_land*), dragon bone (24 veins in *dragon_graveyards*) — all configurable |
| Trees | **9 species**, each placed in the biome the mod places it in: lacugrove, dragon_tree, lucernia, tenanea, pythadendron, jellyshroom, umbrella_tree, gigantic_amaranita, mossy_glowshroom |
| Flora | The mod's flora table (51 rows) with its palettes and placement rules |
| Advancements | 29 rows defined; the ones a player can actually finish are registered through UltimateAdvancementAPI, the rest come back as their subsystems land |

### Not ported yet

* **End Cities and all structures** — `shouldGenerateStructures()` is `false`. No elytra, no
  shulkers.
* **Mechanics that gate most of the mod's advancements**: the eternal portal, the end village, the
  infusion ritual, the leveled anvil, template loot, and the End Stone Smelter (alloying). 24 of the
  mod's 29 advancement rows are hidden until these land.
* **Arrival platform, exit portal and pillar placement** — Bukkit owns these; the dragon fight,
  world spawn and the seed are the server's, not the plugin's.
* **The mod's mobs, sounds and music discs' audio** — no entity code and no `.ogg` files ship.
* **Blocks**: a growing subset of the mod's block ids. Slabs, stairs, walls, signs and a few parent
  models still missing from the pack are tracked in [MIGRATION.md](MIGRATION.md).

[MIGRATION.md](MIGRATION.md) is the design journal: why each subsystem is built the way it is, what
was measured, and what is deliberately left out.

## 6. Architecture

```
betterend-bukkit
├── common/   Bukkit-lifecycle-independent: generator, biome provider, terrain, carvers,
│             trees, flora, populators, config, CraftEngine bridge, NMS loader
├── nms/      Version seam, bound by reflection at runtime (v26_2 platform)
└── plugin/   Plugin descriptors, main class, advancements, bundled CraftEngine pack,
              biome datapack
```

* **Lifecycle.** `paper-plugin.yml` is the only descriptor. `onLoad()` reads the config and installs
  the bundled pack; `onEnable()` refuses to start if CraftEngine or the pack is missing, loads the
  NMS platform and registers the vanilla-End guard, and defers advancement registration to
  `ServerLoadEvent`; `getDefaultWorldGenerator` returns the generator only for an `betterend`
  generator id and only when the pack's blocks are registered.
* **Biome datapack.** `BiomeDatapack` implements Paper's `PluginBootstrap` and registers a datapack
  on `LifecycleEvents.DATAPACK_DISCOVERY`. It has to be a bootstrapper because the biome registry is
  frozen during `WorldLoader.load`, before plugins load — which is also why installing or updating
  the biomes needs a full restart, not `/reload`.
* **CraftEngine ownership.** Blocks, items, models, recipes and language live in
  `craftengine/betterend/configuration/*.yml` and `.../resourcepack/` — 37 configuration files and
  1,614 resource-pack files (906 PNG textures, 690 block/item models). Java only touches a few
  CraftEngine entry points (`CraftEngineBlocks`,
  `CraftEngineItems`, `ImmutableBlockState`) and never re-implements a block.
* **NMS.** `NmsLoader` matches `Bukkit.getBukkitVersion()` and loads
  `org.betterx.betterend.bukkit.nms.v26_2.Platform` by reflection; on anything else it falls back to
  a "BetterEnd Lite" implementation. The seam is 16 lines today — no NMS hooks are needed yet.
* **Folia.** `folia-supported: true`. `RegionSchedulerAdapter` wraps the region/entity/global
  schedulers, the advancement watcher runs on each player's own region, and world generation is
  stateless (one `IslandField`/`BiomePlacement` per seed, stateless populators), so concurrent
  generation is safe.

## 7. Credits

**Original mod — "Better End"** (`betterend`), Team BetterX / BetterX Team:

* **Quiqueck** — code, project lead
* **paulevs** (paulevsGitch) — code & art; the MIT copyright holder in the upstream `LICENSE`
* **Bulldog83** — code & art
* **Edos** — building
* **Yuki** — art
* **Seaward** — art
* **Firel** — music

Links: [GitHub](https://github.com/quiqueck/BetterEnd) ·
[Modrinth](https://modrinth.com/mod/betterend) ·
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/betterend) ·
[Issues](https://github.com/quiqueck/BetterEnd/issues)

**Bukkit/Paper/Folia port — BetterEnd-Plugin:** **HuiDu_OwO**.

**Upstream asset licensing.** Upstream's code is MIT (Copyright (c) 2020 paulevsGitch). Exactly three
assets are CC BY-NC-SA 4.0 ("Team BetterX"), and **all three are deliberately excluded from this
repository, the bundled pack and the built jar**: `textures/gui/infusion.png`, `icon_updater.png`,
`lang/de_de.json`. [LICENSE.ASSETS](LICENSE.ASSETS) lists them with their SHA-256 digests so the
exclusion can be re-verified at any time. Everything else taken from upstream keeps its upstream MIT
license and notice.

## 8. License

This port is released under the **MIT License** — see [LICENSE](LICENSE). It carries both the
upstream notice (Copyright (c) 2020 paulevsGitch) and the port's own. Bundled third-party components
that are not under MIT are listed in [THIRD-PARTY.md](THIRD-PARTY.md).
