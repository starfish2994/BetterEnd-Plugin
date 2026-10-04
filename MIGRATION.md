# BetterEnd Bukkit — status

_Last updated 2026-09-07. The audit that started this port is kept below as the record of why
things are the way they are; this section is the current truth._

> **Editor's note (2026-10-04).** This status section is now stale in several rows. Since 2026-09-07:
> both carvers are wired into `generateNoise` (`CaveCarver`, `TunnelCarver` and `CaveCoatPlanner` in
> `DustWastelandsGenerator.carve`), the tree table grew from 5 to **9 species** (`TreeShape.Species`,
> placed by `TreePopulator`), raw-generation spires and ice stars landed (`SpireShape`,
> `IceStarPlanner`), the test suite is now **226 tests (173 `@Test` methods plus parameterised
> cases) across 18 classes**, and the bundled
> pack ships **37 configuration files and 1,614 resource-pack files**. Where this section disagrees
> with the code, the code wins; [README.md](README.md) is the maintained summary of porting status.

## Build

`./gradlew :plugin:jar :common:test` — green. **80 tests, 0 failures.**
trees 29 · biome 20 · config 12 · terrain 10 · vanilla-check 4 · vanilla-core 5.

Jar 1.02 MB: 35 classes, 37 CraftEngine config yml, 924 pack textures, 27 biome jsons, 2 biome tags,
`config.yml`, `paper-plugin.yml`.

## What generates

| | |
|---|---|
| Terrain | Three island layers on the mod's own OpenSimplex, SDF cones, three density octaves, top fade, coherent coverage. 1024-block spawn void, pinned central island at (0,64,0)×1.3. |
| Biomes | **27 of 27 ship.** Own fog/sky/water/particles/music per biome, and per-biome surface blocks — every id the table names is in the pack. |
| Vanilla End | A 384-block-radius disc around the origin is painted `THE_END` (`VanillaEndCore.CORE_RADIUS`), so vanilla places its own ten obsidian pillars with end crystals. The dragon heals. Exit portal, dragon egg and arrival platform were never broken. |
| Trees | **5 of 11 species**: lacugrove, dragon_tree, lucernia, tenanea, pythadendron — in megalake_grove, shadow_forest, lantern_woods, blossoming_spires, chorus_forest. ~13.5% of chunks outside the vanilla core get at least one; ~1.95 ms/chunk. |
| Ores | flavolite layers 6/chunk, thallasium 24 veins, ender ore 12 — all configurable. |
| Blocks | 473 of 731 (64.7%); 590 defined ids after factory expansion. |
| Config | 13 keys: three island layers × 5, the central island, pack install, three feature densities. |

Generator flags: decorations **true** (this is what restores the pillars), structures **false**,
surface **false**, caves **false**, mobs **true**.

## Two things that silently produce a broken world

1. **`misc.delay-configuration-load: false` in `plugins/CraftEngine/config.yml`.** CraftEngine ships
   it `true`, which registers blocks *after* worlds load. The plugin refuses to generate and logs the
   fix, but the world then falls back to vanilla.
2. **The world must be `Environment.THE_END`.** `DragonBattle` is null otherwise — gated on the
   dimension type, never on the chunk generator. No dragon, no exit portal, no arrival platform, and
   nothing here fixes it.

## Server checklist

1. Set `misc.delay-configuration-load: false`, restart CraftEngine.
2. Create an End-environment world with `generator: BetterEnd:betterend`.
3. Read the startup log — the guard names any of the four broken-world conditions.
4. `/datapack list` shows the betterend pack; F3 reads `betterend:<biome>`.
5. Walk between two biomes and look at the **ground**, not just the fog.
6. Fly to origin: ten pillars with crystals. Kill the dragon and confirm it heals first.
7. Find a `shadow_forest` or `lantern_woods` and confirm trees.

## Remaining, by what unblocks the most

| | |
|---|---|
| **End Cities** | `shouldGenerateStructures()` is false, the fail-safe. Turning it on needs the `end_city` tag (already written, 9 mod-curated biomes, inert today) **and** a 20-minute empirical test: `CustomChunkGenerator.getBaseColumn` always delegates to vanilla noise with no Bukkit hook, so cities may hang in void. No elytra or shulkers until this is settled. |
| **Caves** | Carvers are empty in every biome. Round caves ride the cave-biome decider, which exists — the carver does not. |
| **6 tree species** | mossy_glowshroom / umbrella_tree / jellyshroom / gigantic_amaranita need whole block families; helix_tree needs an 8-value leaf `COLOR` property CraftEngine cannot express; dragon_helix needs `bulb_vine` with a `SHAPE` property. |
| **Saplings** | Deliberately deferred. CE's `sapling_block` needs a feature id, and features land in the terrain pass. |
| **258 blocks** | 93 slabs/stairs/buttons/fences (each costs a whole vanilla block), 53 needing bclib/wover parent models, 52 with no CE template (walls 30, signs 21), 11 furnaces/anvils, 23 that are dead lang keys and not blocks at all. |
| **Pythadendron crown** | Flat. `SplineHelper.powerOffset` lives in bclib, which is not in this checkout; the literal reading sends trees to y=204, so it was left out rather than guessed. |
| **Golden terrain digest** | The one test that would have caught the island-scale seed bug. Must wait until the port stops churning. |

## Landmines

- **Never literal-grep the CraftEngine pack for a block id.** `config_factory` templates generate ids
  from `${stone_type}` / `${family}`; a plain grep misses them and reports blocks as absent. Expand
  the blueprints against every instance variable by name.
- **Never read blocks back during generation.** CraftEngine's `deceive-bukkit-material` makes
  `getType()` lie. All terrain questions go to `IslandField`.
- **Five auto-state pools have ≤2 spare** (generic TRIPWIRE 2, CAVE_VINES 2, KELP / WEEPING_VINES /
  TWISTING_VINES 1 each). The tripwire arithmetic only works because `AutoStateGroup` ordinal order
  serves LOWER/HIGHER before generic. One more generic-tripwire appearance throws
  `StateExhaustedException` and silently drops blocks.
- **CraftEngine features cannot express BetterEnd's.** CE parses with the vanilla
  `ConfiguredFeature.CODEC`; `{"type":"betterend:lacugrove"}` is mod code no datapack can supply.
  Every BetterEnd feature is plugin Java in a `BlockPopulator`.

---

# Appendix: the original audit

## 1. VERDICT

The fundamental design is sound. CraftEngine for blocks + a Bukkit `ChunkGenerator` for terrain is the right split: CE 26.8.1 genuinely supplies block states, item names, tags, sounds, loot, recipes, features and even `configured_feature` trees from YAML, and the port's ~700 lines of Java call exactly three CE API methods, all current and all thread-safe (`AbstractBlockManager.byId` is a `ConcurrentHashMap`, `craft-engine-26.8-src/.../core/block/AbstractBlockManager.java:100`). What is shipped compiles, loads, and is structurally correct.

What is broken is **lifecycle timing and one boolean**. Two independent defects mean the world a player actually sees is not the world the code describes: `shouldGenerateNoise()` returns `true` (`DustWastelandsGenerator.java:154-157`) so vanilla terrain is generated first and BetterEnd islands are stamped on top of it; and CraftEngine's shipped default `misc.delay-configuration-load: true` (`Config.java:377`, shipped `config.yml:749`) means custom blocks are *guaranteed* unregistered while the server generates spawn chunks, so those chunks are baked as plain end stone with zero log output.

**The single biggest risk is that every failure in this port is silent and permanent.** `customBlock()` catches everything and returns end stone (`DustWastelandsGenerator.java:247-254`, `catch (RuntimeException | LinkageError ignored)`); chunk generation is one-shot and persisted. An operator's first signal is flying to spawn after the region files are already wrong. Content-wise the port is at roughly 9 survival-reachable blocks out of a ~731-id mod — but that is a backlog, not a bug. Fix the silence first.

---

## 2. BLOCKERS

### B1 — `shouldGenerateNoise()` returns true: vanilla terrain generates under every chunk
- **File:** `common/src/main/java/org/betterx/betterend/bukkit/DustWastelandsGenerator.java:154-157`
- **Failure:** Paper's default is `false` (`Paper-26.1.2/paper-api/.../generator/ChunkGenerator.java:384-386`; contract at `:69-72`: "The Vanilla noise is generated **before** generateNoise(...)"). `CustomChunkGenerator.java:255-276` gates `this.delegate.fillFromNoise(...)` on the flag and then runs the plugin's `generateNoise` over the *already-filled* chunk. The port never writes AIR and never clears — `fillSegment` (`:124-130`), flavolite (`:84`) and ore (`:111`) are the only `setBlock` calls. So vanilla End (or, in a `NORMAL` world, overworld stone) survives everywhere the islands are not. `getBaseHeight` (`:132-142`) then reports island height only, desyncing the heightmap Paper uses for spawn selection (`CustomChunkGenerator.java:278-292`). Persisted permanently.
- **Fix:** Delete lines 154-157. One-word change; `false` is already the API default.
- *Confirmed independently by 4 of 7 dimensions.*

### B2 — CraftEngine blocks are not registered when the server generates spawn chunks
- **File:** `common/src/main/java/org/betterx/betterend/bukkit/DustWastelandsGenerator.java:247-254` (symptom) / `plugin/src/main/java/org/betterx/betterend/bukkit/BetterEndPlugin.java:55-56` (the gate that should exist)
- **Failure:** CE's `misc.delay-configuration-load` defaults **true** (`craft-engine-26.8-src/.../core/plugin/config/Config.java:377`; shipped `config.yml:749`, whose own comment at `:748` reads "DISABLED (false): Only disable if you require datapack-generated spawn chunks"). With it true, `CraftEngine.java:334/:339` skip `worldManager.delayedInit()` and `packManager.loadPacks()` during enable and defer both to `scheduler.platform().runDelayed(...)` at `:363` → `BukkitExecutor.java:54-57` `Bukkit.getScheduler().runTask(...)` = **first tick**. Paper enables STARTUP plugins in `initServer` before `loadLevel` (`DedicatedServer.java.patch:165-166`), so at spawn-chunk time `AbstractBlockManager.byId` is empty (only put at `:836`), `CraftEngineBlocks.byId` returns null, and `DustWastelandsGenerator.java:250` takes `fallback` — plain `END_STONE`. Simultaneously `BukkitWorldManager.delayedInit()` (`:206-218`) has not installed `InjectedCustomChunkGenerator`, so any custom block that *did* resolve is written raw into the vanilla region file and never recorded in CE's per-chunk store (`WorldStorageInjector.java:52-55`, `InjectedLevelChunkSection.java:72-80`); `sync-custom-blocks-on-chunk-load` is false by default (`config.yml:683`) so it is never adopted later, and `restore-vanilla-blocks-on-chunk-unload` (`config.yml:675-677`) never fires for them.
- **Minimal fix (one call, at world-creation time):** in `getDefaultWorldGenerator` (`BetterEndPlugin.java:55-56`), before returning, call `CraftEngineBlocks.byId(Key.of("betterend:endstone_dust"))`; if null, log SEVERE naming `misc.delay-configuration-load: false` and **return null** so Paper falls back to vanilla instead of baking wrong terrain. Same gate also covers a missing/unparsed pack. Then document the supported alternative: create the world from a `CraftEngineReloadEvent` listener (`bukkit/api/event/CraftEngineReloadEvent.java`, `isFirstReload()` at `:22`), which CE fires only after block registration *and* `delayedInit()` (`CraftEngine.java:381-404`).
- **Do not** parse `plugins/CraftEngine/config.yml` — proposed by one dimension, unnecessary.
- **Contradiction worth naming:** the `terra-reference` dimension asserted the constructor-resolve fix is safe "because CraftEngine loads packs in onEnable (`CraftEngine.java:343`)". That is the `delay-configuration-load: false` branch only. Its *fix* (resolve four ids in the constructor and throw if any is null) is still right — it is B2's gate by another name — but its *justification* is wrong.

### B3 — `MinecraftServer.initWorld` fires `WorldInitEvent` before spawn chunks (settled, informs B2)
Not a defect; recorded so nobody re-derives it. `WorldInitEvent` fires at `MinecraftServer.java.patch:444`, `prepareLevel` generates spawn chunks at `:538-572`. So CE's `onWorldInit` (`BukkitWorldManager.java:365-369`) *does* run in time when `delay-configuration-load: false` — that setting genuinely fixes B2 on the operator side.

---

## 3. MAJORS

### M1 — Silent, undiagnosable fallback on every CE lookup failure
`DustWastelandsGenerator.java:247-254`. `catch (RuntimeException | LinkageError ignored) { return fallback; }` at `:251-252` plus the null branch at `:250`. An id typo, a config that failed to parse, B2's window and M2's window all produce byte-identical invisible plain-end-stone terrain. **Fix:** keep the try/catch, replace `ignored` with a one-shot SEVERE keyed on the id (`Set.newKeySet()` guard). B2's gate turns four per-chunk silent failures into one startup failure.
*(One dimension's supporting NPE story — that `BukkitBlockManager.unload()` nulls what `getBukkitBlockData` dereferences — is wrong: `unload` nulls `DelegatingBlockState`'s back-pointer (`BukkitBlockManager.java:165-171`), not `ImmutableBlockState.customBlockState` (`CraftEngineBlocks.java:163-165`). Diagnostics gap, not a second corruption source.)*

### M2 — `/ce reload` clears the block registry while worldgen worker threads are mid-chunk
`DustWastelandsGenerator.java:249`. Paper runs `generateNoise` off-main (`CustomChunkGenerator.java:255-275`, `FeatureHooks.getWorldgenExecutor()`); CE runs the whole reload off-main too (`CraftEngine.java:226-241` `asyncExecutor.execute`), and teardown is `byId.clear()` (`AbstractBlockManager.java:168-175`) plus `state.setBlockState(null)` for every custom block (`BukkitBlockManager.java:150-171`). Nothing drains the worldgen executor. Two failure modes: a chunk in the window bakes END_STONE; a worker that grabbed the `BlockData` *just before* the clear writes a valid-looking `craftengine:custom_N` that `WorldStorageInjector.compareAndUpdateBlockState` then refuses to record (`:79-83`, `if (newImmutableBlockState == null) return;`) — B2's failure mode reproduced at runtime. **Fix:** one `private volatile BlockData[] palette` field on the generator, filled on the main thread from a `CraftEngineReloadEvent` listener; `generateNoise` reads the snapshot once. ~15 lines, closes M2 and B2's second half together.

### M3 — Six items render as "Nether Brick"
`basic_blocks.yml:45` (ender_ore), `:50` (amber_ore), `:55` (ender_shard), `:57` (raw_amber), `dust_wastelands.yml:2` (thallasium_ore), `:7` (thallasium_raw) — no `data:` block at all. `AbstractItemManager.ItemParser.parseSection` resolves the base material via `Config.defaultMaterial()` (`AbstractItemManager.java:513`), which is `item.default-material` → `nether_brick` (`Config.java:663`, shipped `config.yml:290`). The only name attachment in the entire parser body (`:507-780`) is `ItemProcessors.collectProcessors(section.getSection("data"), ...)` at `:639`. **Fix:** add `data: { item_name: '<!i><white>Ender Ore</white>' }` etc. to each of the six, matching `basic_blocks.yml:4-5`.

### M4 — No item in the pack has item-level settings: the wood set is inert in vanilla crafting
`wood_sets.yml:13-47` and `stone_sets.yml:13-48` define items with only `data`/`model`/`behavior`, never `settings:` — true of all 78 item entries. CE exposes item tags and fuel as `ItemSettingsModifiers` (`core/item/setting/ItemSettingsModifiers.java:106-116`), entirely distinct from block tags, which `BukkitBlockManager.java:362-366` registers against `RegistriesProxy.BLOCK`. CE's own equivalent config sets them on the *item*: `default_assets/configuration/blocks/tree.yml:41-47` gives the log item `settings: {fuel_time: 300, tags: [...logs, logs_that_burn]}`. **Consequence:** betterend planks are not in `#minecraft:planks`, so they craft no crafting table, chest, boat, stick or wooden tool; none of the 30 wood blocks burn as fuel. `wood_sets.yml:121`'s `minecraft:wooden_tool_materials` under *block* settings is the visible symptom of the confusion — it silently creates an empty block TagKey. **Fix:** ~8 lines once, in the wood blueprint's item half.

### M5 — `overrides:` is a shallow replace, so template tags are discarded not extended
`wood_sets.yml:57-58, :72-73, :90-91, :105-106, :120-121`. `TemplateManagerImpl` deep-merges templates (`:137-140`) but applies overrides with a flat `results.putAll(...)` (`:143`) while `merges:` goes through `deepMergeMaps` (`:146`). `default:settings/wood` supplies `[mineable/axe, logs_that_burn, logs, completes_find_tree_tutorial]` (`block_settings.yml:50-54`) and `default:settings/planks` supplies `[mineable/axe]` (`:64`) — all replaced wholesale across 24 log/bark + 6 plank blocks. `minecraft:mineable/axe` *may* survive by accident if vanilla's tag nests `#logs` (`TagUtils.expandBlockTags`, `bukkit/util/TagUtils.java:74-87`, walks nesting from the live datapack; no vanilla tag JSON is in the checkout to settle it), but `completes_find_tree_tutorial` is lost either way. **Fix:** literal one-word swap `overrides:` → `merges:` in five places; `deepMergeMaps` concatenates lists (`TemplateManagerImpl.java:386-388`).

### M6 — Bundled pack is copied only-if-missing: no upgrade ever reaches an existing server
`common/.../CraftEngineBridge.java:56` — `if (Files.exists(target)) return;` is the only write gate, and `:60` is a bare `Files.copy` with no `REPLACE_EXISTING`. Nothing reads `pack.yml`'s `version: '0.1'` (`pack.yml:3`) back; CE reads it only for display (`AbstractPackManager.java:504`). **Fix:** thread a `boolean overwrite` into `copyResource` and drive it from a stamp file under the pack root. Keep never-overwrite as the default for operator-edited files; at minimum, log one WARNING naming installed-vs-bundled versions and telling the operator to delete `plugins/CraftEngine/resources/betterend`.

### M7 — A partial write is permanent (the one real data-loss path in the bridge)
`CraftEngineBridge.java:56` + `:60`. `Files.copy` is not atomic; combined with the exists-guard, a file truncated by disk-full / kill -9 / JVM crash during the **first** `onLoad` is never repaired on any later start, and produces a permanent CraftEngine parse error on a file the operator never touched, with no log line. **Fix (one line, same edit as M6):** copy to `target.resolveSibling(name + ".tmp")` then `Files.move(tmp, target, ATOMIC_MOVE/REPLACE_EXISTING)`.

### M8 — 66 of 75 shipped blocks are unreachable in survival
`DustWastelandsGenerator.java:34, :56-59` place exactly four custom blocks. The only recipes anywhere are `stone_sets.yml:138-174`, `wood_sets.yml:127-134` and `ender_stone_bricks.yml:24-34`; `basic_blocks.yml:232` is an empty `recipes:` key. So: the 6 non-flavolite stone families (30 blocks) have no source at all; all 30 wood blocks have no source (no trees generate, and planks are craftable only from `_log`, not from `_bark`/`_stripped_*` — `wood_sets.yml:130-131`); mossy_obsidian, mossy_dragon_bone, brimstone, missing_tile, dragon_bone_block, amber_ore and raw_amber are creative-only; thallasium_raw and ender_shard have no downstream recipe of any kind. **This reorders the backlog:** worldgen and recipe sources for the existing 75 are cheaper and higher-value than 200 new ids nobody can obtain either.
*Shapeless multi-ingredient works — `AbstractRecipeSerializer.parseIngredient` iterates with `itemsValue.forEach` (`:93`) and `CustomShapelessRecipe.Serializer` passes each list element straight in (`:130-131`), so a nested list accepts log OR bark OR stripped variants.*

### M9 — Display names are raw snake_case; CraftEngine's lang section is unused
`stone_sets.yml:16` renders "flavolite", `:23` "flavolite polished", `:37` "flavolite brick"; `wood_sets.yml:15` "dragon_tree log", `:29` "dragon_tree bark". The mod says "Flavolite", "Polished Flavolite", "Flavolite Bricks", "Dragon Tree Log" (`en_us.json:277, :289, :278, :199`) — word order *and* pluralisation differ, so no format string can generate them. 65 of 78 items affected. `ConfigFactoryParser` substitutes bare `${x}` with no prettifying (`AbstractPackManager.java:3648-3652`). CE's own convention is `<lang:...>` (`default_assets/configuration/items/cap.yml:9`, `topaz_armor.yml:9`) against a `lang#items:` section. Also `basic_blocks.yml:33` says "Endstone Dust" where the mod says "End Stone Dust". **Fix:** migrate to a `configuration/langs/en_us.yml` section; cheapest interim is a second `display:` key per factory instance. This is cross-cutting — it gets more expensive per file added.

### M10 — Terrain fidelity: the islands are wrong shape, and nothing decorates them
Five distinct confirmed gaps, all in `DustWastelandsGenerator.java`:
- **No caves.** `:159-162` returns false; the original carves round caverns p=0.4 r=10..30 y=8..56 squash 1.6 (`configured_carver/round_cave.json`, `EndCaveBiome.java:77-78`) through these islands. Islands are solid lumps.
- **Flat islands.** `:240` is the only perturbation: `noise(x*0.035, z*0.035, seed+91) * 0.045`, a *vertical prism* with no y term. The original adds three density octaves whenever `dist > -0.5F` (`TerrainGenerator.java:195-199`) plus an `SDFRadialNoiseMap` at intensity 0.75 (`IslandLayer.java:46-49, :142-145`; terrainHeight 1.5 from `dust_wastelands.json`). Amplitude gap 0.045 vs 0.2–0.75.
- **Flavolite is a surface blotch, not a deep sheet.** `:68-88` places 1-2 discs of radius 2-5 anchored to `surfaceY`, with y jitter drawn *per (dx,dz)* at `:82` so a "disc" is a one-block dithered speckle. Original is `betterend:ore_layer` radius 12, y uniform 16..128, warp `offset*8`, count 6 (`placed_feature/flavolite_layer.json`, `OreLayerFeature.java:32-46`).
- **Ore veins are clipped and fake.** `:81`/`:108` discard everything outside 0..15 (and `CraftChunkData.java:164-167` makes out-of-chunk `setBlock` a silent no-op anyway), so patches are half-circles cut at chunk edges. `:104-107` re-derives every step from the *original* localX/localZ/y, so a "vein" is ≤`length` blocks scattered in one 3×3×3 box with duplicates. ~18 thallasium + ~9 ender per chunk against the original's 24 blobs of size 8 and 12 of size 4 (`thallasium_ore.json`, `ender_ore.json`).
- **No decorations, no structures, no populator.** `:164-167` and `:174-177` both false, and the class never overrides `getDefaultPopulators` (grepped: no `BlockPopulator` anywhere in the port). The original biome declares END_VILLAGE, ETERNAL_PORTAL, FLAVOLITE_LAYER (`DustWastelandsBiome.java:37-39`) and inherits THALLASIUM_ORE / ENDER_ORE / CRASHED_SHIP (`EndFeatures.java:125-127`); vanilla chorus plants, END_SPIKE pillars, gateways and End Cities are all off too.

**Fix (ordered by value/effort):** move flavolite + ore into a `BlockPopulator` (`getDefaultPopulators`) — cross-chunk writes come free and it deletes the clipping and the `surfaceY` rescans; then re-add the three density octaves to `isSolid`; caves last (one ellipsoid per chunk, p=0.4, cy 8..56, r 10..30, `(y-cy)*1.6`, capped 5 below segment top per `EndCaveCarver` SURFACE_ROOF, is a faithful 1:1 with no biome machinery).

### M11 — Content coverage (backlog, not defects)
| Category | Missing | Evidence |
|---|---|---|
| Flora (plants, mosses, vines, crystals, mushrooms) | 199 ids, zero coverage | `basic_blocks.yml:59-231` defines 8 blocks, none flora; CE behaviors all exist (`BukkitBlockBehaviors.java:9, 20, 23, 25, 36, 72, 75, 81, 82, 87, 90`) |
| Wood | 6 of 12 families absent; the 6 present ship 5 of ~29 slots → 274 missing ids | `wood_sets.yml:5-10, :13-126`; `EndWoodBlocks.java:121,139,156,171,185,227,257,307,340,362,370`; 304 lang keys counted |
| Metals | 78 blocks, ~110 items, all unported | `dust_wastelands.yml` is 36 lines, 1 block + 1 item; `EndMetalBlocks.java:72,77,85,97,107,120,126,136` |
| Biomes | 27 declared, port paints END_BARRENS everywhere | `EndBiomes.java:15-43`; `DustWastelandsGenerator.java:296-306` |
| Sounds | 28 sound events + 6 jukebox songs | `EndSounds.java:15-49, :51-56`; parser exists (`AbstractSoundManager.java:97, :133`); 43 .ogg + sounds.json already in Reference |
| Structures | 128 .nbt + 23 structure types | `EndStructures.java`; no CE structure section exists |

---

## 4. MINORS

**Config schema**
- `ender_stone_bricks.yml:12` — bare `settings:` with no `template:`, so no sound modifier runs (`BlockSettings.java:41` defaults `BlockSounds.EMPTY` = `minecraft:intentionally_empty`, `SoundData.java:14`). *Same block* also has no `correct_tools`/`required_break_power`, so it breaks by hand and drops (`BlockSettings.java:218`), unlike every sibling. **Both fixed by deleting the file — see below.**
- `ender_stone_bricks.yml:2` is an id the mod never had (zero hits in `en_us.json`; the mod ships 8 `end_stone_brick_{cracked,weathered}_*` ids at `:251-258`), is pixel-identical to vanilla (`:5`, `:22`), duplicates vanilla's 2×2 recipe (`:26-33`), and `:22` makes CE write a model into `assets/minecraft/models/block/end_stone_bricks.json` (`AbstractBlockManager.java:701-707` sets `modelPath = textureKey` when `model:` is absent; written by `AbstractPackManager.java:3076-3084`), overriding vanilla's own model. **Fix: delete `ender_stone_bricks.yml`.** Closes 4 findings.
- `basic_blocks.yml:113-128` — `betterend:brimstone` loses its `active` boolean (mod's `BrimstoneBlock.java:30, :38-40` + `blockstates/brimstone.json`). Port ships `brimstone.png` but only one appearance. Right behavior is `CHANGE_OVER_TIME_BLOCK` (`BukkitBlockBehaviors.java:37`), **not** `NEAR_LIQUID_BLOCK` (`:16`), which breaks the block rather than toggling a property.
- `basic_blocks.yml:71-72` — mossy_obsidian gets `default:loot_table/self`, but the mod drops **obsidian** unless silk-touched (`EndStoneBlocks.java:73` `.dropWithSilktouch(Blocks.OBSIDIAN)`). Free renewable obsidian.
- **45 stone-family blocks play HARP under a note block.** No `instrument` set anywhere; `BlockSettings.java:38` defaults `"harp"`, pushed into NMS at `BukkitBlockManager.java:288`. `default:sound/stone` sets only sounds (`block_settings.yml:189-191`). Wood is fine (`default:settings/wood` sets `bass`, `:49`). Add `instrument: basedrum`, or use `default:settings/ore` for the three ores (`:66-77`).
- `stone_sets.yml:138` — `recipes:` inside a `config_factory` blueprint shares one mutable `HashMap` between the BLOCK and RECIPE parsers (`AbstractPackManager.java:3646-3658` → `:674-683`; mutated per-id by `IdSectionConfigParser.java:57-60`). Only reachable via `/ce reload recipe` or `/ce reload all` (`ReloadCommand.java:41, :85`); plain `/ce reload` is safe (`CraftEngine.java:302`). Losing interleaving makes a block drop another block's item via `default:loot_table/self`. **Fix:** move `recipes:` into a sibling `config_factory#stone_recipes:` — ~9 duplicated lines, fresh map per factory (`:3647`).
- `wood_sets.yml:82-99, :100-114` — the 12 bark blocks use `default:block_state/pillar` (3 appearances) but pass the *same* texture to `pillar_texture_top` and `pillar_texture_side` (`:98-99`, `:113-114`), so all three orientations render identically. Burns 24 SOLID visual states and 24 internal ids for nothing (`AbstractBlockManager.java:634-637`, `:424`). Replace with `auto_state: solid`.
- `basic_blocks.yml:95/100/103` names pillar appearances `axis_x/y/z` while the shared template uses `axisX/Y/Z` (`block_states.yml:8, 17, 28`). Appearance names are baked into the persisted allocation cache key (`AbstractBlockManager.java:628-632`, saved at `:410-415`) — rename now, while nothing is placed.
- No `categories:` section anywhere: all 78 items ungrouped in CE's browser. CE's own factory sets one per family (`tree.yml:33-37, :40`). Cosmetic at 75 ids; a cross-cutting rewrite at 731 — same shape of problem as M9, do it in the same pass.

**Generator**
- `DustWastelandsGenerator.java:204/:210` — **dimensional bug.** `centerDistance = Math.max(1, 1000 / layer.distance)` is a *cell count*, compared at `:210` against a *squared* cell distance. The spawn void radius comes out as √(1000·distance) blocks: 548 for BIG, 387 for MEDIUM, 245 for SMALL, not the intended 1000. Save-compat break — fix before shipping.
- `:214` + `:222` — `coverage` and `heightFactor` derive from the **same** `islandSeed` differing only by a constant XOR, and `unit()` (`:278-280`) keys on bit 63. Acceptance at `:215` admits exactly the islands whose `heightFactor` draw has bit63==0, so `heightFactor` is provably never ≥ 1.0 (brute-forced: 12743 accepted islands, range [0.65002, 0.99995]). Every non-central island is horizontally shrunk 0-35%, never widened. `heightFactor` is invented — the mod's only per-island randomness is one uniform scale on all three axes (`IslandLayer.java:112-113, :121-126`). **Fix: delete line 222** and the record field at `:293`; use `limit = island.scale * (radius + edge)` at `:241`. Fixes both bugs at once.
- `:241-242` — **sign bug.** `limit` goes negative where `radius*heightFactor < -edge`, and the squared comparison resurrects it as a solid disc of radius `|limit|`. Confined to cone tips (ny ∈ [-0.30, -0.272], ~8 blocks on a scale-300 island): stubs up to 13 blocks wide instead of a taper. **Fix:** `if (limit <= 0) continue;`
- `:126-128` — a segment of height 1-2 becomes *entirely* falling dust over air (`basic_blocks.yml:145-147` is `behavior: type: falling_block`). **Fix:** `int d = Math.min(dustDepth, top - bottom);`
- `:117-122` — `surfaceY` scans for `Material.END_STONE`, but CE custom blocks report as the deceived material (`deceive-bukkit-material.default: bricks`, `config.yml:364-365`; written into `CraftMagicNumbersProxy.BLOCK_MATERIAL` at `BukkitBlockManager.java:608`). So when CE works, `surfaceY` returns *below* the 1-3 dust cap and flavolite is never surface-visible; when CE is broken, `dust == endStone` and it returns `top`. Two different worlds from the same seed. **Fix:** record the per-column top into an `int[256]` in `generateNoise` and pass it to `decorate`/`placeOreVeins`. Also deletes ~10 full-column rescans per chunk.
- `:219-221` — central island at y=70; the mod hardcodes `new BlockPos(0, 64, 0)` (`IslandLayer.java:101`). One-word fix.
- `:213-215` — coverage cull is per-cell white noise; the original samples a coherent density field at the island's world position (`IslandLayer.java:80`, `LayerOptions.java:31, :64-66`). Destroys small-island archipelagos. Note the coverage test at `:215` runs *before* centerX/centerZ are drawn at `:217-218`, so a fix must reorder.
- `:228-243` — no top fade-out. The original forces `dist = -1` above maxHeight and lerps across the last 27 blocks (`TerrainGenerator.java:165-166, :201-204`). Affected islands top out ~y=104.
- `:126` — dust thickness is per-column white noise (sampled 64×64: 1323/1464/1309 for depths 1/2/3, no run structure); the original drives it from vanilla's coherent surface-depth noise (`add_surface_depth: true`).

**Plugin / build**
- `BetterEndPlugin.java:56` — `generator: BetterEnd` (id==null) and `generator: BetterEnd:` (id=="") both return null (`CraftServer.java:1754-1755`). Not fully silent — `CraftServer.java:1765-1767` logs a named SEVERE — but the world loads vanilla and is only recoverable by deleting it. **Fix:** `return id == null || id.isEmpty() || id.equalsIgnoreCase("betterend") ? new DustWastelandsGenerator() : null;` plus `@Nullable` on the parameter (`Plugin.java:183-184`).
- `BetterEndPlugin.java:33-35` — `disablePlugin(this)` inside `onEnable` closes the plugin's own classloader mid-callback (`JavaPlugin.java:268-287` sets `isEnabled` *before* `onEnable`, so the re-entrant call passes `PaperPluginInstanceManager.java:229-232` and runs the full disable path incl. `configuredPluginClassLoader.close()` at `:250-260`), then `PluginEnableEvent` fires unconditionally at `:218` for a disabled plugin. Same line also collapses three distinct causes into one silent branch. **Fix (one edit, both findings):** `throw new IllegalStateException(packInstalled ? "CraftEngine did not enable" : "BetterEnd pack was not installed")` — Paper's supported bail-out (`:207-214`).
- `CraftEngineBridge.java:20-27, :51` — `BUNDLED_FILES` is a hand-maintained 6-entry literal, while `:52` already walks `resourcepack` recursively via `copyDirectory` (`:64-103`), which handles 143 files including the jar case (`:88-100`). **Contradiction named:** one dimension rated this a *blocker* ("the whole migration backlog is blocked"); another verified the list matches disk and jar exactly today (6 ymls). Both are right — it is not a defect now, and it silently swallows the *next* config file added. **Fix:** `copyDirectory("craftengine/" + PACK, root)` replacing lines 20-27 and 51. (Do **not** write `copyDirectory(RESOURCE_ROOT, root)` — `RESOURCE_ROOT` at `:19` ends in `/` and the file branch at `:77` would build a double slash; it happens to resolve, by luck.) Note `Pack.expandFolders` also recognises `blueprint/` and `script/` (`Pack.java:22-23`).
- `gradle.properties:2` — `paper_version=26.2.build.+` is a dynamic version, so "`:plugin:jar` SUCCEEDS" is time-dependent. The local Gradle cache already holds **five** distinct resolutions of that selector (builds 87, 105, 112, 119, 121). **Fix:** pin `26.2.build.121-stable`.
- `plugin/build.gradle.kts:18` — `expand("version" to project.version)` inside the `filesMatching` action. Two effects, reproduced on this project: `--configuration-cache` hard-fails ("invocation of 'Task.project' at execution time is unsupported"); and version is not a declared input, so bumping `version` and rebuilding leaves `plugin.yml` reading the **old** string (`> Task :processResources UP-TO-DATE`, verified in an isolated repro). **Fix:** hoist `val pluginVersion = project.version.toString()` outside the task *and* add `inputs.property("version", pluginVersion)` — the hoist alone does not fix staleness.
  - ⚠️ **Tripwire:** `expand()` is Groovy `SimpleTemplateEngine`. `stone_sets.yml:14` (`betterend:${stone_type}:`) and `wood_sets.yml:13` are CE `config_factory` variables. Widening that `filesMatching("plugin.yml")` to `craftengine/**` or `**/*.yml` dies with `MissingPropertyException: stone_type`.
- `CraftEngineBridge.java:65-66` — the jar branch resolves a *directory* via `getResource` and throws if null; works today only because Gradle's default `includeEmptyDirs=true` puts 19 zero-length directory entries in the jar. Latent; matters if shading is ever adopted.
- Supply chain: `gradle-wrapper.properties` has `validateDistributionUrl=true` but **no** `distributionSha256Sum`, and an unverified `gradle-wrapper.jar` (48462 bytes) sits in the tree. `gradlew wrapper --gradle-version 9.7.0 --gradle-distribution-sha256-sum <sum>`.
- Perf, in one bundle (`DustWastelandsGenerator.java`): `:240` recomputes an (x,z)-only noise inside the per-island, per-y loop — hoist to the column body, biggest single win; `:194`/`:216` allocate an `ArrayList` + up to ~13 `new Random` per column ≈ 3300/chunk; `:117-122` `surfaceY` rescans full columns per feature, allocating a `BlockPos` per `getType` (`CraftChunkData.java:150-157`). Roughly 10⁵ `noise()` calls per chunk, not the 10⁶-10⁷ two dimensions claimed. Real cost: hundreds of µs to low-single-digit ms per chunk. Fix the hoist and the `int[256]`; skip the rest until profiled.
- Dead code: `RegionSchedulerAdapter.java` (zero callers, grepped), `NmsPlatform.ownsTerrain()`, `BetterEndPlugin.isLite()`. Note `Platform` never overrides `ownsTerrain()`, so `isLite()` returns **true even on a fully supported 26.2 server** — the one accessor distinguishing lite from full can never report full.
- Dead/cosmetic: `basic_blocks.yml:232` bare `recipes:` (safely skipped by `AbstractPackManager.java:674-675`, but it is the only record that dragon_bone/mossy_obsidian/endstone_dust recipes are owed — fill it, don't delete it); `default:sound/wood` redundant beside `default:settings/wood` in 5 places (`block_settings.yml:43-45, :56-58`); 12 duplicate `stripped_X_log*.png` textures, byte-identical to the canonical `X_stripped_log*.png` (mod's own lang key is `pythadendron_stripped_log`, `en_us.json:579`) — delete them; the entire `file:` protocol branch of `copyDirectory` (`:67-87`) plus `ResourceCopyException` (`:105-111`) is unreachable on a real server, 21 lines.

---

## 5. REFUTED / UNCERTAIN — do not chase these

| Claim | Status |
|---|---|
| "Auto-allocated internal ids are persisted in a cache file, so a re-allocation silently swaps every placed block" | **REFUTED.** CE's per-chunk store serialises by namespaced *string* (`DefaultSectionSerializer.java:47-51, :66-98`), and on unload CE rewrites vanilla region files to `restoreBlockState()` (`BukkitWorldManager.java:600-620`). The numeric id reaches disk only under B2. Do **not** add `id:` pins. |
| "CraftEngine has no isolated shape states, so slabs/stairs/walls are blocked" | **REFUTED as stated, but the port's warning comment is substantively right.** Doors/trapdoors/fence gates bind only the redundant `powered=true` (`block_states.yml:432, 113, 703`) and pressure plates use an auto pool — those are **free now**, and the 24 door/trapdoor textures are already staged. Slabs, stairs, buttons and fences each consume **one whole obtainable vanilla block** (CE's own comment at `tree.yml:605`; it spends `petrified_oak_slab` at `:644` and `cut_copper_stairs` at `:676`). **There is no `default:block_state/wall` template at all** — the "30 wall ids" in one report are fabricated. |
| "The SOLID auto-state pool is a hard ceiling on a 731-block port; write a state-budget tally file" | **REFUTED.** `resources/internal/configuration/mappings.yml` yields 1489 SOLID candidates (1300 note_block + 63×3 mushroom). ~2× headroom. Skip the tally. The pools that *do* bind: `SAPLING` has 8 candidates vs the mod's 12 saplings, and `CHORUS` has **zero** (`auto_state: chorus` throws on first use, `VisualBlockStateAllocator.java:150`). Both fixable with a `block_state_mappings:` section in the betterend pack — the auto-state pools are pack-editable config, not plugin constants. |
| "mossy_dragon_bone_hor.json uses the wrong texture" | **REFUTED.** Byte-for-byte identical to the mod's own model; `mossy_dragon_bone_side_hor` is unreferenced upstream too. Applying the "fix" makes the port diverge from what it is porting. |
| "NmsLoader version mismatch → chunks generate through a half-loaded path → corruption" | **REFUTED.** The whole nms module is 16 lines whose `enable()` logs one line. Nothing to lose. Also: **do not** apply the proposed `startsWith("26.2")` — it false-positives on a future 26.20. Leave the gate. |
| "END_BARRENS breaks the ender dragon fight" | **UNCERTAIN.** The gating mechanisms are confirmed (`CustomWorldChunkManager.java:14-48`, `CustomChunkGenerator.java:300-306`), but no decompiled `net.minecraft` exists in any of the three Paper checkouts to say whether spikes/crystals come from biome features or `EndDragonFight`. Don't build a per-region biome split for this. |
| `ender_stone_bricks` 2×2 recipe collides with vanilla's | **UNRESOLVED** — no code found that arbitrates a shape collision between a CE recipe and a vanilla one (`RecipeEventListener.java:859-889` rewrites the result from whichever recipe the server picked). Moot once the file is deleted. |
| Isolated Projects failures (`build.gradle.kts:5-6` `allprojects`; `plugin/build.gradle.kts:11` cross-project `sourceSets`) | **Real and reproduced, but info.** Incubating opt-in flag nobody is using. Don't refactor for a hypothetical. |
| Adopt shadow / add a resolvedConfiguration guard | **Skip.** The jar has 184 entries and zero third-party bytes; the proposed guard uses deprecated API that itself breaks the configuration cache. Add shadow the day a real `implementation` dependency appears. |
| `disablePlugin(this)` "causes NoClassDefFoundError in NmsLoader / onDisable" | **Overstated.** `onEnable` returns before `NmsLoader.load` is reached, and `onDisable` runs before the classloader closes. Only real consequence: a `PluginEnableEvent` for a disabled plugin. |
| Sapling growth needs structure NBT + a datapack | **INVERTED.** `configured_feature` **is** a CE config section, and CE's `tree.yml:707-760` defines a full `minecraft:tree` in YAML wired to a sapling at `:179-181`. Use `feature:`, not `structure:`. Only helix_tree and umbrella_tree (SDF silhouettes) justify the NBT detour. |
| "Only 19-21 particle types are addressable" | **Corrected.** `ParticleConfig.fromConfig` resolves any vanilla particle key via `platform().getParticleType(key)`; `ParticleTypes.java` is only the set accepting structured extra data. The approximation palette is the whole vanilla registry. |
| Several Terra line citations (NMSInitializer.java:203, BukkitBiomeProvider.java:158, NMSBiomeInjector.java:366) | **Fabricated.** Those files are 55, 37 and 103 lines. Don't go looking. |

---

## 6. ROADMAP

### What CraftEngine 26.8.1 **cannot** do as a plugin

| Original mod category | CE support | Closest approximation |
|---|---|---|
| **Biomes** (27) | **None.** No biome section in any parser; `core/` has no biome package. The only `{"biome","biomes"}` hits are a placed-feature filter (`BukkitWorldManager.java:997`) and a loot condition (`BiomeCondition.java:32`). | Ship biome JSONs in a **datapack** inside the plugin and resolve them by `NamespacedKey` through `RegistryAccess.getRegistry(RegistryKey.BIOME)` — datapack biomes are real Bukkit biomes (`paper-api/.../block/Biome.java:22-24`). Zero NMS. This is the one thing that forces a datapack to exist. |
| **Entities / mobs** (6) | **None.** No entity registration section. | Vanilla mob + persistent metadata + a Java listener, or skip. |
| **Structures** (128 .nbt, 23 types) | **None.** `SaplingBlockBehavior.java:141-175` resolves against the live registry; nothing registers one. | Datapack structures, once the datapack exists for biomes. |
| **Particles** (14 custom) | Partial | Any vanilla particle key is addressable; use `dust` with a per-block colour. Low priority. |
| **Functional blocks** | Split. `SIMPLE_STORAGE_BLOCK` (`:40`), `DISPLAY_ITEM_BLOCK` (`:84`), `DRAWER_BLOCK` (`:85`), `SEAT_BLOCK` (`:71`), `SOFA_BLOCK` (`:46`) exist. **No furnace, anvil, smelting or smithing behavior anywhere** in the 94-line registry. | Chests/barrels/pedestals/furniture = config. Furnace/anvil/smelter = plugin Java, inventory-driven. |
| **Slabs / stairs / buttons / fences** | Templates exist but each consumes one whole vanilla block | Defer. Doors/trapdoors/fence gates/pressure plates are free — do those. |
| **Walls** | **No template exists.** | Not achievable without writing a CE block behavior. |

### Phase 0 — Stop baking wrong worlds *(half a day; nothing else matters until this ships)*
- Delete `DustWastelandsGenerator.java:154-157` (**B1**).
- Add the resolve-check gate in `BetterEndPlugin.getDefaultWorldGenerator` (**B2**) + accept null/empty id + `@Nullable`.
- Replace `catch (... ignored)` at `:251` with a one-shot SEVERE keyed on the id (**M1**).
- Replace the `onEnable` `disablePlugin` with a `throw` carrying the actual cause (**M-plugin**).
- Document `misc.delay-configuration-load: false` in the README as a hard requirement.
- **Done when:** a fresh world created at boot with a *broken* pack produces a plain vanilla world plus one SEVERE naming the missing id — never silent end stone; and with a working pack, spawn chunks contain `betterend:endstone_dust` verified with `/ce` or a debug stick.

### Phase 1 — Make the pack installable and updatable *(half a day; blocks every later phase)*
- `CraftEngineBridge`: `copyDirectory("craftengine/" + PACK, root)`, delete `BUNDLED_FILES` and lines 20-27/51 (**minor**); tmp-file + atomic move (**M7**); stamp file for upgrades (**M6**); delete the unreachable `file:` branch `:67-87` + `ResourceCopyException` (21 lines).
- Pin `paper_version` (**minor**); hoist `pluginVersion` + `inputs.property` in `processResources` (**minor**).
- **Done when:** adding a new `.yml` under `configuration/blocks/` and rebuilding installs it on a server that already has an older pack, verified by its ids resolving after `/ce reload`.

### Phase 2 — Make the 75 shipped blocks correct and reachable *(2-3 days; highest value per line)*
- Delete `ender_stone_bricks.yml` (closes 4 findings).
- Add `data.item_name` to the six unnamed items (**M3**).
- `overrides:` → `merges:` in five places (**M5**); delete `minecraft:wooden_tool_materials`.
- Add `settings: {tags: [...], fuel_time: 300}` to the wood item entries (**M4**).
- Fill `basic_blocks.yml:232` and widen the plank recipe to a nested-list shapeless ingredient over log/bark/stripped (**M8**).
- `instrument: basedrum` on the 45 stone blocks; mossy_obsidian silk-touch loot; brimstone `active` property with `CHANGE_OVER_TIME_BLOCK`.
- Migrate names to `configuration/langs/en_us.yml` + `<lang:...>` (**M9**), and add `categories:` in the same pass. Source: the mod's `en_us.json`, which has all 197 correct strings.
- Move `recipes:` to a sibling `config_factory#stone_recipes:` (**minor**).
- **Done when:** `/ce reload all` is clean, every one of the 75 blocks has a correct name, an axe/pickaxe bonus, a sound, and either a recipe or a worldgen source.

### Phase 3 — Sounds *(half a day; cheapest fidelity win in the whole report)*
28 events + 6 jukebox songs. Assets (43 `.ogg` + `sounds.json`) already exist under `Reference/BetterEnd/src/main/resources/assets/betterend/`; the parser already exists (`AbstractSoundManager.java:97, :133`). Pure copy + one config file.
**Done when:** breaking a betterend block plays its own sound and a betterend disc plays in a jukebox.

### Phase 4 — Terrain correctness *(2-4 days)*
Order: delete `heightFactor` (`:222`, fixes the RNG-correlation bug for free) → `if (limit <= 0) continue;` (`:241`) → `centerDistance` squared comparison (`:210`) → dust clamp (`:126-128`) → central island y=64 (`:219`) → `int[256]` top-height array replacing `surfaceY` → hoist `edge` out of the island loop → move flavolite + ore into a `BlockPopulator` → three density octaves in `isSolid` → top fade → coherent coverage → caves.
⚠️ Every item before the populator changes generated terrain. **Do all of Phase 4 in one release**, before anyone builds a world worth keeping.
**Done when:** islands have visible vertical relief, flavolite is a deep sheet exposed at cliff faces, ore veins cross chunk borders unbroken, and no column is pure falling dust over air.

### Phase 5 — Decide the datapack question *(1 day of decision, then it unblocks two categories)*
Biomes are the one worldgen layer CE cannot supply. Either ship the 27 biome JSONs as a plugin datapack and look them up via `RegistryKey.BIOME`, or accept a single stand-in — in which case use `END_HIGHLANDS`, not `END_BARRENS`, since barrens carries **no mob spawn entries** so `shouldGenerateMobs()` at `:169-172` currently buys nothing. **Features do not need the datapack**: `configured_feature:` and `placed_features:` are CE config sections.
**Done when:** the decision is written down, because Phases 6-8 are sized differently on each side of it.

### Phase 6 — Flora *(the largest content block, 199 ids)*
CE behaviors all exist. Place with CE `placed_features:` filtered on `dimension_type: minecraft:the_end` (pattern: `default_feature_populator/subpacks/26_1/configuration/features.yml:1-30`). Add a `block_state_mappings:` section to extend the SAPLING pool past its 8 candidates and create a CHORUS pool from zero.

### Phase 7 — Wood *(274 ids)* and Phase 8 — Metals *(78 blocks, ~110 items)*
Wood: widen the blueprint to doors/trapdoors/gates/pressure plates only (skip slabs/stairs/buttons/fences — each costs a vanilla block); add the 6 missing families; `configured_feature:` trees per species wired to saplings via `feature:` (`tree.yml:707-760, :179-181`). Metals: tools/armor from `default_assets/configuration/items/topaz_tool_weapon.yml` and `topaz_armor.yml` as the template.

### Phase 9 — Functional blocks, structures, mobs
Only what the table above says is achievable. Furnaces/anvils/smelter are plugin Java; mobs are out of scope; structures follow the Phase 5 decision.

---

## 7. NEXT ACTION

Open `c:/Users/HuiDu_OwO/Downloads/GitHub/BetterEnd/BetterEnd/common/src/main/java/org/betterx/betterend/bukkit/DustWastelandsGenerator.java` and delete lines 154-157:

```java
    @Override
    public boolean shouldGenerateNoise() {
        return true;
    }
```

Nothing replaces it — `false` is Paper's default (`paper-api/.../generator/ChunkGenerator.java:384-386`) and matches the `false` already returned by `shouldGenerateSurface` (`:150-152`), `shouldGenerateCaves` (`:159-162`), `shouldGenerateDecorations` (`:164-167`) and `shouldGenerateStructures` (`:174-177`). Four lines deleted, one blocker closed, no other file touched.

Then, in the same commit, `BetterEndPlugin.java:56`:

```java
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        if (id != null && !id.isEmpty() && !id.equalsIgnoreCase("betterend")) return null;
        if (CraftEngineBlocks.byId(Key.of("betterend:endstone_dust")) == null) {
            getLogger().severe("CraftEngine blocks are not registered yet; refusing to generate '" + worldName
                + "'. Set misc.delay-configuration-load: false in plugins/CraftEngine/config.yml.");
            return null;
        }
        return new DustWastelandsGenerator();
    }
```

skipped: the `volatile BlockData[]` reload snapshot (M2) and the one-shot SEVERE in `customBlock` (M1) — add both in Phase 0's second commit, once the gate proves the id resolves at world-creation time.