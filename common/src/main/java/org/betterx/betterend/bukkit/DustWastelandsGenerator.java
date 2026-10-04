package org.betterx.betterend.bukkit;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.betterx.betterend.bukkit.flora.FloraPalette;
import org.betterx.betterend.bukkit.flora.FloraPopulator;
import org.betterx.betterend.bukkit.biome.BetterEndBiomeProvider;
import org.betterx.betterend.bukkit.biome.BiomePlacement;
import org.betterx.betterend.bukkit.biome.BiomeSurface;
import org.betterx.betterend.bukkit.biome.CaveCoat;
import org.betterx.betterend.bukkit.config.BetterEndConfig;
import org.betterx.betterend.bukkit.terrain.CaveCarver;
import org.betterx.betterend.bukkit.terrain.CaveCoatPlanner;
import org.betterx.betterend.bukkit.terrain.IceStarPlanner;
import org.betterx.betterend.bukkit.terrain.IslandField;
import org.betterx.betterend.bukkit.terrain.TunnelCarver;
import org.betterx.betterend.bukkit.trees.TreePopulator;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** 26.2 BetterEnd terrain generator for the first migrated land biome. */
public final class DustWastelandsGenerator extends ChunkGenerator {
    final BlockData endStone = Material.END_STONE.createBlockData();
    /** The three per-biome ores; null when the pack has not shipped one. */
    final BlockData violecite;
    final BlockData amberOre;
    final BlockData dragonBoneOre;
    /** Read once at plugin load and never re-read; see {@link BetterEndConfig}. */
    final BetterEndConfig config;
    final BlockData flavolite;
    final BlockData thallasiumOre;
    final BlockData enderOre;
    /** {@code SpikeFeatureMixin.java:113-115} speckles the pillar edge 1-in-24 with this. Vanilla, and already in {@code #minecraft:dragon_immune}. */
    final BlockData cryingObsidian = Material.CRYING_OBSIDIAN.createBlockData();
    /**
     * THE WALL SUBSTITUTE, and the one place to change it. The mod's pillar NBTs use
     * {@code betterend:flavolite_wall} for every vertical accent, and CraftEngine has no wall
     * template and no wall behavior ({@code BukkitBlockBehaviors.java:10-64} registers
     * {@code slab_block} and {@code stairs_block} and no {@code wall_block}; the shipped
     * {@code block_states.yml} has no {@code wall}). {@code flavolite_pillar} is the free stand-in:
     * a solid 1x1x1 column in the right material, already defined and already dragon-immune.
     * <p>
     * ponytail: the near-perfect substitute is a new {@code betterend:flavolite_wall} on
     * {@code default:block_state/fence} + {@code fence_block} -- our own post/arm models draw a
     * wall, only the (non-overridable) collision box stays a fence's, and it costs 32 states off an
     * unclaimed vanilla fence family. Swap this one id for it and nothing else changes.
     * <p>
     * Falls back to plain flavolite rather than refusing the world: this is decoration.
     */
    final BlockData spikeAccent;

    /**
     * One bound (island field, placement) pair per world seed, shared by the provider, the tree
     * populator and {@link #generateNoise}, so all of them ask one land memo, one plane cache and
     * one biome map. The generator instance is shared by the whole world and by every worldgen
     * worker, but the seed only arrives with the first call, so neither can be built in the
     * constructor. Both are immutable after construction and this map holds at most one entry per
     * world, so the lookup is safe from any thread, including a worldgen worker: it is pure Java,
     * no Bukkit and no registry.
     * <p>
     * {@code computeIfAbsent} is safe HERE and only here: {@link BiomePlacement#bind} generates
     * nothing, so it cannot re-enter this map. The two caches underneath it are re-entered and use
     * get/put instead - see {@code IslandField.plane} and {@code BiomePlacement.landCache}.
     */
    private final Map<Long, BiomePlacement> placements = new ConcurrentHashMap<>();
    /**
     * One provider per world seed, for the same reason: it resolves every biome eagerly in its
     * constructor and logs the missing ones once, so it must not be rebuilt per call. Built only
     * from {@link #getDefaultBiomeProvider} / {@link #getDefaultPopulators}, never from
     * {@link #generateNoise} -- its constructor reads CraftRegistry's unsynchronized map.
     */
    private final Map<Long, BetterEndBiomeProvider> providers = new ConcurrentHashMap<>();
    /**
     * The cave coat's palette, resolved up front like {@link #surfaces}. An id the pack lacks is
     * simply absent, and {@link #coatBlock} then answers null.
     */
    private final Map<String, BlockData> coatBlocks = new HashMap<>();
    /** The ice star's four blocks, resolved up front for the same reason. */
    private final Map<String, BlockData> iceBlocks = new HashMap<>();

    /** One biome's resolved surface. Immutable; filled in the constructor; published by a final field. */
    private record Surface(BlockData top, BlockData under, BlockData alt, boolean dusty) {
    }

    /**
     * Indexed by {@link BiomeSurface#ordinal()}; only the shipped members are filled, the rest stay
     * null and {@link #surfaceAt} treats them as vanilla. Resolved ONCE, here: the hot loop indexes
     * an array and never touches CraftEngine.
     */
    private final Surface[] surfaces = new Surface[BiomeSurface.values().length];
    /** The vanilla core and anything the pools cannot name: plain end stone, no cap, no speckle. */
    private final Surface vanillaSurface;

    /**
     * Resolves every custom block up front. CraftEngine registers its blocks late --
     * {@code misc.delay-configuration-load} defaults to true, which defers registration
     * past the spawn chunks -- and a chunk generated before that point is permanently
     * baked as plain end stone. Failing here lets the caller refuse the world instead.
     *
     * @throws IllegalStateException if CraftEngine has not registered a required block
     */
    public DustWastelandsGenerator(BetterEndConfig config) {
        this.config = config;
        flavolite = require("betterend:flavolite");
        thallasiumOre = require("betterend:thallasium_ore");
        enderOre = require("betterend:ender_ore");
        // The three per-biome ores. optional(), not require(): each costs one biome its ore rather
        // than the world, the way trees and surface blocks already degrade. dragon_bone_block is
        // default:block_state/pillar, whose axis already defaults to y -- the state the mod asks
        // for -- so no state is set here (TreeShape.java records the same fact for logs).
        violecite = optional("betterend:violecite");
        amberOre = optional("betterend:amber_ore");
        dragonBoneOre = optional("betterend:dragon_bone_block");
        BlockData accent = optional("betterend:flavolite_pillar");
        spikeAccent = accent == null ? flavolite : accent;

        // Every distinct id over the 12 shipped biomes: ~7 lookups for the life of the world.
        // A null VALUE means "the pack does not define it" and is what `defined` below tests.
        Map<String, BlockData> blocks = new HashMap<>();
        blocks.put("minecraft:end_stone", endStone);
        // The one CUSTOM surface block the world cannot open without: dust_wastelands' top and the
        // cap on every dusty biome. require(), so a pack that has not registered it refuses the
        // world instead of baking plain end stone into the region files forever.
        blocks.put("betterend:endstone_dust", require("betterend:endstone_dust"));
        for (BiomeSurface biome : BiomePlacement.selectable()) {
            for (String id : List.of(biome.top(), biome.under(), biome.alt())) {
                if (!blocks.containsKey(id)) blocks.put(id, optional(id));
            }
        }
        List<String> broken = new ArrayList<>();
        for (BiomeSurface biome : BiomePlacement.selectable()) {
            BiomeSurface.Resolved resolved = biome.resolve(id -> blocks.get(id) != null, broken::add);
            surfaces[biome.ordinal()] = new Surface(blocks.get(resolved.top()),
                    blocks.get(resolved.under()),
                    resolved.alt() == null ? null : blocks.get(resolved.alt()),
                    resolved.dusty());
        }
        // The cave coat's four blocks -- three jadestones and cave moss -- which no biome lists as
        // its top or filler, so `blocks` above has never seen them.
        for (String id : CaveCoat.PALETTE) {
            BlockData data = optional(id);
            if (data == null) {
                broken.add(id);
            } else {
                coatBlocks.put(id, data);
            }
        }
        // The ice star's palette: three emerald ices and dense snow, none of which any biome
        // lists as its top or filler, so `blocks` above has never seen them either.
        for (IceStarPlanner.Ice ice : IceStarPlanner.Ice.values()) {
            BlockData data = optional(ice.id());
            if (data == null) {
                broken.add(ice.id());
            } else {
                iceBlocks.put(ice.id(), data);
            }
        }
        vanillaSurface = new Surface(endStone, endStone, null, false);
        if (!broken.isEmpty()) {
            // Never fatal: a missing moss costs one biome its looks, not the world. The four blocks
            // the generator cannot do without still throw, above.
            Logger.getLogger("BetterEnd").warning("BetterEnd: the CraftEngine pack does not define "
                    + broken.size() + " surface block(s); those biomes generate their fallback"
                    + " instead: " + String.join(", ", broken) + ". They are named in F3 but will"
                    + " look unfinished until the pack ships them.");
        }
    }

    /** The one placement for this seed; safe from any thread, including a worldgen worker. */
    BiomePlacement placement(long seed) {
        return placements.computeIfAbsent(seed, s -> BiomePlacement.bind(s, config));
    }

    /** The island field the placement above is bound to. Never a second field: the two have to
     *  agree about where the land is, and the placement is what tells the field its biome heights. */
    IslandField field(long seed) {
        return placement(seed).field();
    }

    /** Main / world-load thread only -- see {@link #providers}. */
    private BetterEndBiomeProvider provider(long seed) {
        return providers.computeIfAbsent(seed, s -> new BetterEndBiomeProvider(placement(s)));
    }

    @Override
    public void generateNoise(@NotNull WorldInfo world, @NotNull Random random, int chunkX, int chunkZ,
                              @NotNull ChunkData data) {
        int minY = data.getMinHeight();
        int maxY = data.getMaxHeight();
        IslandField field = field(world.getSeed());
        BiomePlacement placement = placement(world.getSeed());
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = (chunkX << 4) + localX;
                int z = (chunkZ << 4) + localZ;
                double[] column = field.densityColumn(x, z, minY, maxY - 1);
                int bottom = Integer.MIN_VALUE;
                for (int y = minY; y < maxY; y++) {
                    if (column[y - minY] > 0) {
                        if (bottom == Integer.MIN_VALUE) bottom = y;
                    } else if (bottom != Integer.MIN_VALUE) {
                        fillSegment(data, field, placement, localX, localZ, x, z, bottom, y - 1);
                        bottom = Integer.MIN_VALUE;
                    }
                }
                if (bottom != Integer.MIN_VALUE) {
                    fillSegment(data, field, placement, localX, localZ, x, z, bottom, maxY - 1);
                }
            }
        }
        carve(data, field, placement, world.getSeed(), chunkX, chunkZ);
    }

    /**
     * Both of the mod's carvers plus its {@code cave_surface_coat}, run after the fill exactly as
     * {@code WorldCarver} and then {@code UNDERGROUND_DECORATION} run after {@code fillFromNoise}.
     * <p>
     * Carving and coating are one pass because they are one question. Deciding whether a block was
     * carved costs a 3D simplex per candidate cavern plus three more for the tunnel field, and that
     * -- not the density lattice -- is the expensive half of a cave chunk: measured over 1600
     * chunks it was 3.3 s against 0.9 s for every density column and {@code topY} put together.
     * Asking it once and using the answer for both halves is worth more than any other optimisation
     * available here.
     * <p>
     * Writes only inside this chunk's own columns. {@link CaveCoatPlanner} reads one block beyond
     * them, but purely as terrain queries -- no chunk data is touched, so nothing depends on a
     * neighbour having generated yet.
     */
    private void carve(ChunkData data, IslandField field, BiomePlacement placement,
                       long seed, int chunkX, int chunkZ) {
        CaveCoatPlanner.Sweep sweep = CaveCoatPlanner.sweep(chunkX, chunkZ, placement, field,
                CaveCarver.planFor(seed, placement, chunkX, chunkZ),
                TunnelCarver.planFor(seed, placement, chunkX, chunkZ),
                (x, y, z) -> fillsWithEndStone(placement, x, y, z));
        if (sweep == null) {
            return;
        }
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int y = 0; y <= CaveCoatPlanner.BAND_TOP; y++) {
                    if (sweep.carved(localX, y, localZ)) {
                        data.setBlock(localX, y, localZ, Material.CAVE_AIR);
                    }
                }
            }
        }
        // The coat goes on after the carve, and before the ore populator, which is how the mod's
        // `protected: #c:ores` is reproduced without ever reading a block back: the ore blobs are
        // written later and win wherever the two overlap.
        for (CaveCoatPlanner.Paint paint : sweep.paints()) {
            BlockData block = coatBlocks.get(paint.id());
            // A null block is an id the pack has not shipped; that face just stays end stone.
            if (block != null) {
                data.setBlock(paint.x() & 15, paint.y(), paint.z() & 15, block);
            }
        }
    }

    /**
     * The biome's filler with its surface on top, matching the mod's rules
     * ({@code EndBiome.java:108-119}): {@code under} is the whole solid run, {@code top} is the one
     * floor block, and the three biomes whose top is {@code endstone_dust} additionally get the
     * {@code add_surface_depth} band {@link IslandField#dustDepth} models. The segment's underside
     * always stays filler -- dust is a falling block, so a segment capped all the way to its floor
     * would be a column of falling sand over air.
     * <p>
     * The biome is sampled ONCE per solid run, at its top block: that is the only place where both
     * the floor block the mod's rule keys on and the segment height the dust clamp needs are known.
     * A segment tall enough to cross the cave ceiling therefore keeps its top biome's filler all
     * the way down -- invisible while umbra_valley is the only biome whose filler is not end stone.
     */
    private void fillSegment(ChunkData data, IslandField field, BiomePlacement placement,
                             int localX, int localZ, int x, int z, int bottom, int top) {
        Surface surface = surfaceAt(placement, x, top, z);
        int cap = surface.dusty() ? field.dustDepth(x, z, top - bottom + 1) : 1;
        int capBottom = top - cap + 1;
        for (int y = bottom; y <= top; y++) {
            data.setBlock(localX, y, localZ, y >= capBottom ? surface.top() : surface.under());
        }
        if (surface.alt() != null && cap > 0 && field.altSurface(x, z)) {
            data.setBlock(localX, top, localZ, surface.alt());
        }
    }

    /**
     * The two vanilla rings are not BetterEnd's to paint, and they are the only regions that are
     * not: BetterEnd surfaces run everywhere outside {@code BiomePlacement.ring}. That is the mod's
     * own boundary, {@code innerVoidRadiusSquared} (WoverEndBiomeSource.java:327-341), not the
     * chunk-quantized {@code inCentralRing} whose rim used to cut across real islands and flip top
     * block, dust cap, speckle and filler along a straight line. Almost all of it sits in void: the
     * inner-void cull drops every island centred within 1024 blocks, so the only ground inside it
     * belongs to the pinned island and to the rim of a big island just outside.
     * <p>
     * Quart-resolved, not block-resolved: this is called per block column while the biome provider
     * is called per quart, so a raw block test would write 1-3 columns of BetterEnd surface inside
     * a quart the provider named {@code the_end}.
     * <p>
     * The null arm is insurance against a future picker change; {@code quartAt} can only return
     * picker members today.
     */
    private Surface surfaceAt(BiomePlacement placement, int x, int y, int z) {
        if (placement.ring(x, z) != null) return vanillaSurface;
        Surface surface = surfaces[placement.quartAt(x, y, z).ordinal()];
        return surface == null ? vanillaSurface : surface;
    }

    /**
     * Does the terrain at this position fill with plain {@code minecraft:end_stone}? The ore
     * features are the callers: every one of them replaces end stone by EXACT block match and
     * nothing else (placed_feature/thallasium_ore.json and ender_ore.json both carry
     * {@code "target": {"block": "minecraft:end_stone", "predicate_type": "minecraft:block_match"}},
     * and OreLayerFeature.java:58 is {@code state.is(Blocks.END_STONE)} - not the end_stones tag),
     * so a biome whose filler is not end stone carries no ores in the mod either. umbra_valley
     * fills its whole solid run with umbralith, and this is what keeps flavolite discs out of it.
     * <p>
     * Goes through {@link #surfaceAt}, so it is the identical question {@link #fillSegment} asked -
     * including in the degraded case where the pack has no umbralith and the fill really did write
     * end stone.
     * <p>
     * ponytail: {@link #fillSegment} picks one biome per solid run, at the run's top block, while
     * this asks at the block. A run tall enough to cross the cave ceiling therefore still takes
     * ores into its umbralith below y~48 - the same deviation {@link #fillSegment} documents, and
     * it closes when the fill samples per y.
     */
    boolean fillsWithEndStone(BiomePlacement placement, int x, int y, int z) {
        return surfaceAt(placement, x, y, z).under().equals(endStone);
    }

    /**
     * Delegates to the same island field {@link #generateNoise} fills from, so the height reported
     * to spawn and structure placement cannot drift from the terrain that actually generates.
     */
    @Override
    public int getBaseHeight(@NotNull WorldInfo world, @NotNull Random random, int x, int z,
                             @NotNull HeightMap heightMap) {
        int minY = world.getMinHeight();
        int top = field(world.getSeed()).topY(x, z, minY, world.getMaxHeight() - 1);
        return top == Integer.MIN_VALUE ? minY : top + 1;
    }

    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        // Paper builds a fresh, identically seeded Random for EVERY populator
        // (ChunkGenerator.java.patch:113-124), so the `random` argument replays the same stream for
        // both of these. BetterEndPopulator uses it; TreePopulator deliberately derives its own from
        // (seed, chunkX, chunkZ) instead (TreePopulator.java:121-125), so the order here is inert.
        long seed = world.getSeed();
        // raw_generation first: the mod runs spires and ice stars before ores and vegetation.
        return List.of(new RawGenerationPopulator(this),
                new BetterEndPopulator(this),
                new TreePopulator(TreePopulator.biomeSoil(placement(seed)), field(seed)),
                // The placement carries its own island field, so passing it passes the bound pair --
                // one plane cache and one agreement about where the land is (FloraPopulator.java:25-26).
                new FloraPopulator(placement(seed), new FloraPalette()));
    }

    @Override
    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return provider(worldInfo.getSeed());
    }

    /**
     * False: it gates {@code delegate.buildSurface} (CustomChunkGenerator.java:142-145), which would
     * run the vanilla End surface rules over the dust cap {@link #fillSegment} already wrote.
     */
    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    /**
     * False: it gates {@code delegate.applyCarvers} (CustomChunkGenerator.java:240-243) and every
     * shipped biome carries {@code "carvers": []}, so true is a guaranteed no-op with a cost.
     */
    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    /**
     * TRUE, and it is the only thing that runs {@code EndSpikeFeature}: the flag gates exactly one
     * call, {@code addVanillaDecorations} (ChunkGenerator.java.patch:107-109 via
     * CustomChunkGenerator.java:300-307), and the obsidian pillars are a placed feature of
     * {@code minecraft:the_end}, which {@link BetterEndBiomeProvider} paints over
     * {@link org.betterx.betterend.bukkit.vanilla.VanillaEndCore#inCoreQuart}. Without it the dragon
     * never heals.
     * <p>
     * It costs the populators nothing: BlockPopulators run outside the gate
     * (ChunkGenerator.java.patch:111-124) and CraftEngine's own feature pass runs outside it too
     * (InjectedCustomChunkGenerator.java:177-216).
     * <p>
     * The BetterEnd datapack biomes all ship {@code "features": [[],...]}, so the exposure is only
     * through the vanilla biomes the provider still returns - and both of those (THE_END,
     * END_BARRENS) are wanted or featureless.
     */
    @Override
    public boolean shouldGenerateDecorations() {
        return true;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    /**
     * False, as the fail-safe. {@code getBaseColumn} is left unhooked by CraftBukkit
     * (CustomChunkGenerator.java:336-339) and still answers with vanilla End density, so it
     * disagrees with our hooked {@link #getBaseHeight} everywhere outside the vanilla island - the
     * mod had to patch both (NoiseBasedChunkGeneratorHeightMixin.java:25-36) and a Bukkit generator
     * can only patch one. Which of the two End City placement reads is not determinable from this
     * checkout, and a city frozen at y=0 in the void is permanent; a missing city is not.
     */
    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    /**
     * {@link #require} without the exception, for the per-biome surface blocks: an id the pack has
     * not shipped must cost one biome its looks, never the world (TreePopulator.java:258-266 makes
     * the same trade for trees). Handles vanilla ids too -- 9 of the 12 shipped rows carry
     * {@code minecraft:end_stone}, which {@code CraftEngineBlocks.byId} answers null for.
     */
    private static BlockData optional(String id) {
        if (id.startsWith("minecraft:")) {
            Material material = Material.matchMaterial(id);
            return material == null ? null : material.createBlockData();
        }
        try {
            BlockDefinition definition = CraftEngineBlocks.byId(Key.of(id));
            return definition == null ? null : CraftEngineBlocks.getBukkitBlockData(definition.defaultState());
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    /**
     * The cave coat's block for an id, or null when the pack has not shipped it (in which case that
     * face simply stays end stone). Resolved once in the constructor, so the populator never touches
     * CraftEngine from a worldgen thread.
     */
    BlockData coatBlock(String id) {
        return coatBlocks.get(id);
    }

    /**
     * The block a spire's upward-facing faces are capped with: blossoming_spires' own top material,
     * which the mod samples once per spire from the ground it grows out of
     * ({@code SpireFeature.java:74}, {@code EndBiome.sampleTopMaterial}) rather than per position.
     * Falls back to the spire body if a pack has dropped pink moss, exactly as every other biome
     * surface degrades.
     */
    BlockData spireCap() {
        Surface surface = surfaces[BiomeSurface.BLOSSOMING_SPIRES.ordinal()];
        return surface == null || surface.top() == null ? endStone : surface.top();
    }

    /**
     * One of the ice star's four blocks, resolved once at startup like every other palette. Null
     * when the pack has not shipped it, which costs that shell its colour and nothing else.
     */
    BlockData iceBlock(IceStarPlanner.Ice ice) {
        return iceBlocks.get(ice.id());
    }

    private static BlockData require(String id) {
        BlockDefinition definition = CraftEngineBlocks.byId(Key.of(id));
        if (definition == null) {
            throw new IllegalStateException("CraftEngine has not registered the block " + id);
        }
        return CraftEngineBlocks.getBukkitBlockData(definition.defaultState());
    }
}
