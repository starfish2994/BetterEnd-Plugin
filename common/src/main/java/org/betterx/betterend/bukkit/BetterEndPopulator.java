package org.betterx.betterend.bukkit;

import org.betterx.betterend.bukkit.biome.BiomePlacement;
import org.betterx.betterend.bukkit.biome.BiomeSurface;
import org.betterx.betterend.bukkit.terrain.IslandField;
import org.betterx.betterend.bukkit.terrain.OpenSimplexNoise;
import org.betterx.betterend.bukkit.vanilla.EndSpikes;
import org.betterx.betterend.bukkit.vanilla.VanillaEndCore;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dust wastelands underground-ore step -- flavolite layers, thallasium and ender ore -- plus
 * {@link #repairCore}, which puts back the end stone vanilla's obsidian pillars carve out of the
 * island under them.
 * <p>
 * These live in a populator rather than in {@code generateNoise} because a flavolite layer is a
 * flood fill of radius 6 that has to cross chunk borders -- {@code ChunkData.setBlock} outside
 * 0..15 is a silent no-op, which is what turned them into half discs. {@code LimitedRegion} allows
 * writes one chunk out in every direction, and CraftEngine records them there just as it does in
 * the centre chunk (its FEATURE-stage hook injects the sections before the populators run).
 * <p>
 * Placement never reads a block back. A neighbour's populator may or may not have run yet, so a
 * block read is visit-order dependent, and CraftEngine's deceive-material would make it lie in any
 * case; every decision comes from {@link IslandField} and {@link BiomePlacement}, i.e. purely from
 * the seed and the position. The one exception is {@link #repairCore}'s {@code == AIR} test, which
 * is a read of a VANILLA block and is provably honest; see its javadoc.
 * Holds no mutable state: Paper runs this concurrently for many chunks.
 */
public final class BetterEndPopulator extends BlockPopulator {
    /** flavolite_layer.json radius 12.0; violecite_layer.json radius 15.0. Halved, as OreLayerFeature:32. */
    private static final float FLAVOLITE_RADIUS = 12.0f * 0.5f;
    private static final float VIOLECITE_RADIUS = 15.0f * 0.5f;
    /** Both layer jsons give the same y range, so it is shared rather than per-ore. */
    private static final int LAYER_MIN_Y = 16;
    private static final int LAYER_MAX_Y = 128;
    /**
     * OreLayerFeature.java:55 - SDFScale3D(1, 0.2, 1) of the sphere. A static field of the feature
     * class, shared by every ore_layer config, so it is deliberately NOT per-ore.
     */
    private static final float LAYER_SQUASH = 0.2f;

    /**
     * The {@code minecraft:biome} filter each ore feature carries, as the set of BetterEnd biomes
     * whose json lists it. {@code null} means every one of the 27 -- which is true of thallasium and
     * ender, and was wrongly assumed of flavolite until now.
     */
    static final Set<BiomeSurface> FLAVOLITE_BIOMES =
            EnumSet.of(BiomeSurface.DUST_WASTELANDS, BiomeSurface.NEON_OASIS);
    static final Set<BiomeSurface> VIOLECITE_BIOMES =
            EnumSet.of(BiomeSurface.CHORUS_FOREST, BiomeSurface.SHADOW_FOREST);
    static final Set<BiomeSurface> AMBER_BIOMES = EnumSet.of(BiomeSurface.AMBER_LAND);
    static final Set<BiomeSurface> DRAGON_BONE_BIOMES =
            EnumSet.of(BiomeSurface.DRAGON_GRAVEYARDS);

    private final DustWastelandsGenerator generator;
    /** One warp-noise field per world seed; OreLayerFeatureConfig.java:33-38 seeds it from the world seed. */
    private final Map<Long, OpenSimplexNoise> warp = new ConcurrentHashMap<>();

    BetterEndPopulator(DustWastelandsGenerator generator) {
        this.generator = generator;
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                         @NotNull LimitedRegion region) {
        long seed = worldInfo.getSeed();
        IslandField field = generator.field(seed);
        // Hoisted: every ore write is additionally gated on the biome filling with plain end stone
        // (DustWastelandsGenerator.fillsWithEndStone), because that is the only block the mod's ore
        // features replace.
        BiomePlacement placement = generator.placement(seed);
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;
        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight();

        // First, so this chunk's own ore writes below can never be clobbered by it.
        repairCore(region, field, generator.endStone, chunkX, chunkZ, maxY - 1);
        // Immediately after, in the SAME pass. A second BlockPopulator would get its own
        // CraftLimitedRegion and no ordering guarantee against this one; here the order is explicit
        // and the two are disjoint by construction (see reskinSpikes).
        reskinSpikes(region, field, generator.flavolite, generator.spikeAccent,
                generator.cryingObsidian, seed, chunkX, chunkZ);

        // OreLayerFeatureConfig.java:33-38 seeds the warp field from the world seed ALONE, so both
        // layer ores distort through the identical noise. Deliberately not salted per ore.
        OpenSimplexNoise warpNoise = warp.computeIfAbsent(seed, OpenSimplexNoise::new);
        oreLayers(region, field, generator, placement, random, warpNoise, originX, originZ,
                generator.config.flavoliteLayersPerChunk(), FLAVOLITE_RADIUS, generator.flavolite,
                FLAVOLITE_BIOMES, true);
        oreLayers(region, field, generator, placement, random, warpNoise, originX, originZ,
                generator.config.violeciteLayersPerChunk(), VIOLECITE_RADIUS, generator.violecite,
                VIOLECITE_BIOMES, false);

        // thallasium_ore.json: size 8; ender_ore.json: size 4. Both are uniform(above_bottom 8,
        // below_top 8) with in_square. The counts (24 and 12 in the mod) come from config.yml.
        oreBlobs(region, field, generator, placement, random, originX, originZ, minY, maxY,
                generator.config.thallasiumVeinsPerChunk(), 8, generator.thallasiumOre, null, true);
        oreBlobs(region, field, generator, placement, random, originX, originZ, minY, maxY,
                generator.config.enderVeinsPerChunk(), 4, generator.enderOre, null, true);
        // amber_ore.json: size 6, count 60, amber_land only. dragon_bone_ore.json: size 8 and
        // count 24 -- the same as thallasium -- in dragon_graveyards only.
        oreBlobs(region, field, generator, placement, random, originX, originZ, minY, maxY,
                generator.config.amberVeinsPerChunk(), 6, generator.amberOre, AMBER_BIOMES, false);
        oreBlobs(region, field, generator, placement, random, originX, originZ, minY, maxY,
                generator.config.dragonBoneVeinsPerChunk(), 8, generator.dragonBoneOre,
                DRAGON_BONE_BIOMES, false);
    }

    /**
     * Vanilla {@code EndSpikeFeature} clears the non-obsidian remainder of its square footprint to
     * air above y=65 - free in vanilla, where the island tops out at y~63, but our island is at
     * y=66..90 on the spike ring, so it deletes real end stone and leaves the reported gap where the
     * pillar meets the ground. The mod does not have this problem because SpikeFeatureMixin.java:188
     * cancels {@code placeSpike} outright and rebuilds the pillar, base structure included
     * (pillar_base_N.nbt ships {@code minecraft:end_stone}, planted 3 blocks under the surface,
     * :90-91). The port has no mixin, so it repairs afterwards instead.
     * <p>
     * Floor at 60 rather than vanilla's 65: {@code EndSpikeFeature.placeSpike} (:91-92, CFR decompile
     * of the running paper-26.2.jar) clears to air only where {@code pos.getY() > 65}, so 60 carries
     * five blocks of deliberate slack under the real rule. Raise it to 65 if the scan ever shows up
     * in a profile.
     */
    private static final int REPAIR_FLOOR = 60;

    /**
     * Re-asserts the terrain {@code generateNoise} wrote, wherever a vanilla decoration carved it
     * away inside the vanilla core. Runs over this chunk plus the +-1 ring: that is exactly the
     * radius a feature may write (CraftLimitedRegion buffer = 16), so every chunk repairs everything
     * any neighbour's spike could have carved and the pass needs no cross-chunk state and never has
     * to locate a pillar. Monotone AIR -> END_STONE only where {@link IslandField} says solid, so
     * the nine-fold overlap between neighbours costs nothing and a rerun is a no-op.
     * <p>
     * Order-independent even though it reads blocks: a neighbour's spike either has not decorated
     * yet, in which case its columns still hold intact terrain and fail the {@code == AIR} test, or
     * it has, in which case the carve and the obsidian are both visible. Every interleaving reaches
     * the same final state, and the spike's own chunk repairs it again regardless.
     * <p>
     * Places END STONE, never obsidian: the pillar itself is where vanilla put it, and end stone is
     * byte-identical to what the generator wrote here - inside the core
     * {@code DustWastelandsGenerator.surfaceAt} returns the vanilla surface, plain end stone with no
     * dust cap and no speckle, which is the second reason the {@code inCoreQuart} guard is in the
     * loop and not just an optimisation.
     * <p>
     * ponytail ceiling: outside a pillar's own footprint ({@link #isPillarColumn}) this restores ANY
     * decoration-carved air inside the core, not just the spike's. Correct while
     * {@code minecraft:the_end}'s feature list is {@code end_spike} and nothing else, which is NOT
     * DETERMINABLE from this checkout. If a the_end feature that legitimately hollows terrain ever
     * appears, narrow this further to columns within {@link VanillaEndCore#SPIKE_MAX_RADIUS} of a
     * column that reads obsidian.
     * <p>
     * ponytail ceiling: the AIR read is honest only while craft-engine's
     * {@code block.deceive-bukkit-material.default} (config.yml:364-370, default "bricks") does not
     * read as air. CraftMagicNumbers.java:165-172 prepopulates BLOCK_MATERIAL for every vanilla
     * block and BukkitBlockManager.java:516-543 only overwrites its own DelegatingBlock keys, so
     * vanilla obsidian, bedrock and air read back truthfully. This is the ONLY block read in the
     * plugin, and it never asks about a CraftEngine block.
     *
     * @return blocks filled, for the test; a second run over the same region must return 0.
     */
    static int repairCore(LimitedRegion region, IslandField field, BlockData endStone,
                          int chunkX, int chunkZ, int maxY) {
        if (chunkX + 1 < VanillaEndCore.coreChunkMin() || chunkX - 1 > VanillaEndCore.coreChunkMax()
                || chunkZ + 1 < VanillaEndCore.coreChunkMin() || chunkZ - 1 > VanillaEndCore.coreChunkMax()) {
            return 0;
        }
        // Nothing above IslandField.TOP_SOLID_Y can ever be solid, so scanning to the world's build
        // height is ~130 rows per column of guaranteed air.
        maxY = Math.min(maxY, IslandField.TOP_SOLID_Y);
        int x0 = (chunkX - 1) << 4;
        int z0 = (chunkZ - 1) << 4;
        int x1 = ((chunkX + 2) << 4) - 1;
        int z1 = ((chunkZ + 2) << 4) - 1;
        // The core is a 384-block disc - 2401 chunks, of which the pinned island puts terrain in
        // 124. Without this gate the other ~2280 each pay a 2304-column scan to discover they are
        // void: measured over the whole core, +730% on generateNoise's own cost, against +37% with
        // it. The gate is exact, not a sample - see IslandField.anySolid.
        if (!field.anySolid(x0, z0, x1, z1, REPAIR_FLOOR, maxY)) return 0;
        int filled = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!VanillaEndCore.inCoreQuart(x, z)) continue;
                double[] column = field.densityColumn(x, z, REPAIR_FLOOR, maxY);
                if (isPillarColumn(region, column, x, z)) continue;
                for (int i = 0; i < column.length; i++) {
                    if (column[i] <= 0) continue;
                    int y = REPAIR_FLOOR + i;
                    if (!region.isInRegion(x, y, z)) continue;
                    if (region.getType(x, y, z) != Material.AIR) continue;
                    region.setBlockData(x, y, z, endStone);
                    filled++;
                }
            }
        }
        return filled;
    }

    /**
     * The pillar's own footprint circle, left strictly alone. Vanilla fills that circle with
     * obsidian, caps it with bedrock and clears everything above it -- the crystal's seat and, on a
     * guarded spike, the cage -- while the air this pass exists to undo is the square footprint
     * MINUS that circle. The two sets are disjoint by construction, so "this column holds obsidian
     * or bedrock" is an exact test for "vanilla built here, do not touch it", and it is what keeps
     * the repair from entombing an end crystal on a seed whose terrain outgrows a short spike.
     * <p>
     * Without it the safety margin is empirical and thin: after the bulge fix the island tops out at
     * Y 69..73 on the spike ring across eight seeds and vanilla's shortest spike caps at Y 76, but
     * that height is NOT DETERMINABLE from this checkout and {@code generator.layers.medium.scale}
     * can raise the island. The dragon heals from crystals it can still reach; this makes that
     * structural instead of a three-block coincidence.
     * <p>
     * Reads only vanilla blocks, and only in columns the terrain says are solid, so it asks
     * CraftEngine nothing -- see this class's javadoc for why that read is honest.
     */
    private static boolean isPillarColumn(LimitedRegion region, double[] column, int x, int z) {
        for (int i = 0; i < column.length; i++) {
            if (column[i] <= 0) continue;
            int y = REPAIR_FLOOR + i;
            if (!region.isInRegion(x, y, z)) continue;
            Material at = region.getType(x, y, z);
            if (at == Material.OBSIDIAN || at == Material.BEDROCK) return true;
        }
        return false;
    }

    /**
     * How deep the crown and the foot band run. {@code pillar_top_*.nbt} carry 4 layers of flavolite
     * crown trim, {@code pillar_base_*.nbt} flare over 3.
     */
    private static final int CROWN_DEPTH = 4;
    private static final int FOOT_DEPTH = 3;
    /** {@code SpikeFeatureMixin.java:113-115} - 1 in 24 EDGE blocks becomes crying obsidian. */
    private static final int SPECKLE_ONE_IN = 24;

    /**
     * Reskins vanilla's ten obsidian spikes into the mod's flavolite-ribbed, flavolite-crowned,
     * crying-obsidian-speckled ones ({@code SpikeFeatureMixin.java:76-120}).
     * <p>
     * Approximated procedurally rather than replayed from the mod's 12 {@code pillars/*.nbt}, and
     * that is a correctness decision. The mod plants those structures at ITS OWN Y --
     * {@code SpikeFeatureMixin.java:78} is {@code minY + spike.getHeight() - 64}, where minY is the
     * surface under the centre -- and gets away with it only because {@code :181} cancels
     * {@code placeSpike} outright and spawns the crystal from the NBT's own {@code entities} list.
     * A populator can cancel nothing: by the time it runs, vanilla has already placed the bedrock
     * cap, the fire and a {@code generatedByDragonFight} crystal
     * ({@code EndSpikeFeature.java:115-124}). Replaying {@code pillar_top_N} would stack a second
     * bedrock cap at a different height, strand vanilla's crystal mid-shaft, and add a second
     * crystal of its own. A reskin that loses a crystal is worse than no reskin.
     * <p>
     * For the same reason the mod's {@code radius--} ({@code SpikeFeatureMixin.java:77}) is NOT
     * reproduced. It exists only to fit NBTs sized to {@code r-1}; here it would mean carving
     * vanilla's outermost obsidian ring to air, which both breaks {@link #isPillarColumn} and
     * removes the ground the crystal is centred over. It costs one block of pillar width.
     * <p>
     * <b>Disjoint from {@link #repairCore} by construction.</b> Both {@code repairCore} and
     * {@link #isPillarColumn} look only at cells where {@link IslandField} says solid; this pass
     * writes only where it says void. So the repair sees a byte-identical world, its
     * obsidian-or-bedrock test still fires on every pillar column, and no crystal can be entombed.
     * That is a proof, not a margin: it is the same predicate on both sides, not two guesses.
     * <p>
     * <b>Reads nothing.</b> The spike list is {@link EndSpikes#forSeed}, a pure function of the
     * seed, and every cell written is one {@code EndSpikeFeature.placeSpike} (:87-88) filled with
     * obsidian by construction. No block read, so contract 1 never comes up.
     * <p>
     * <b>Idempotent.</b> The pass runs up to nine times over the same spike, once from each chunk in
     * the +-1 ring, and every value it writes is a pure function of (seed, x, y, z) -- notably the
     * speckle, which uses {@link #speckled} and NOT the populator's per-chunk {@link Random}, whose
     * draws depend on chunk visit order. Re-running writes the same block data to the same cells,
     * so restarts and the ninefold overlap are both free.
     *
     * @return blocks written, for the test
     */
    static int reskinSpikes(LimitedRegion region, IslandField field, BlockData flavolite,
                            BlockData accent, BlockData crying, long seed, int chunkX, int chunkZ) {
        // Every spike centre is inside the ring, so every spike chunk is inside
        // [-(42+5)>>4, (42+5)>>4] = [-3, 2], and a region reaches +-2 chunks past that. Checked
        // before EndSpikes.forSeed so the ~99.99% of chunks nowhere near the ring pay one compare
        // instead of a shuffle and ten allocations.
        int reach = ((VanillaEndCore.SPIKE_RING_RADIUS + VanillaEndCore.SPIKE_MAX_RADIUS) >> 4) + 3;
        if (Math.abs(chunkX) > reach || Math.abs(chunkZ) > reach) return 0;
        int written = 0;
        for (EndSpikes.Spike spike : EndSpikes.forSeed(seed)) {
            // A spike writes at most +-5 blocks from its centre, so its footprint never leaves its
            // centre chunk +-1, and this region reaches this chunk +-1. Nothing else is reachable.
            if (Math.abs((spike.centerX() >> 4) - chunkX) > 2
                    || Math.abs((spike.centerZ() >> 4) - chunkZ) > 2) {
                continue;
            }
            int r = spike.radius();
            // Vanilla's bedrock cap is AT height, the fire and the crystal above it: never reach it.
            int top = spike.height() - 1;
            int surface = field.topY(spike.centerX(), spike.centerZ(), REPAIR_FLOOR, top);
            int foot = surface == Integer.MIN_VALUE ? REPAIR_FLOOR : surface;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // The rim of the obsidian circle only. The shaft's interior stays obsidian, as
                    // it is in every pillar_top_*.nbt, and the centre column is never touched.
                    if (dx * dx + dz * dz > r * r + 1) continue;
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    boolean rib = (dx == 0) != (dz == 0); // the four cardinal edge columns
                    int x = spike.centerX() + dx;
                    int z = spike.centerZ() + dz;
                    for (int y = foot + 1; y <= top; y++) {
                        boolean inCrown = y > top - CROWN_DEPTH;
                        boolean inFoot = y <= foot + FOOT_DEPTH;
                        if (!rib && !inCrown && !inFoot) continue;
                        if (!region.isInRegion(x, y, z)) continue;
                        // The whole disjointness guarantee, in one line.
                        if (field.isSolid(x, y, z)) continue;
                        region.setBlockData(x, y, z, speckled(seed, x, y, z) ? crying
                                : (rib && !inCrown ? accent : flavolite));
                        written++;
                    }
                }
            }
        }
        return written;
    }

    /**
     * The 1-in-24 crying-obsidian draw, as a hash of position and seed rather than a draw from the
     * populator's {@link Random}. That random is seeded per chunk
     * ({@code ChunkGenerator.java.patch}: {@code setDecorationSeed(seed, x, z)}), so the same cell
     * reached from nine different chunks would get nine different answers and the pattern would
     * depend on visit order. SplitMix64's finalizer over an LCG mix of the four inputs; the test
     * asserts the resulting rate, so a degenerate hash goes red instead of silently speckling
     * nothing or everything.
     */
    private static boolean speckled(long seed, int x, int y, int z) {
        long h = seed;
        h = h * 6364136223846793005L + 1442695040888963407L + x;
        h = h * 6364136223846793005L + 1442695040888963407L + y;
        h = h * 6364136223846793005L + 1442695040888963407L + z;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return Long.remainderUnsigned(h, SPECKLE_ONE_IN) == 0;
    }

    /**
     * OreLayerFeature: a sphere of radius 6 squashed to 0.2 in Y, its centre plane warped up to
     * +-8 blocks by 2D noise, flood-filled from the seed point through end stone only -- so a layer
     * stops at air and never jumps a gap, but it does cross chunk borders.
     */
    /**
     * One ore_layer feature's worth of layers for this chunk; {@code count} comes from config.yml.
     * <p>
     * OreLayerFeature.java:33-36 computes {@code r = floor(radius + 1)} and offsets by
     * {@code randRange(max(r-16, 0), min(31-r, 15))}; for both radii used here that range works out
     * to 0..15, so the offset needs no parameter.
     */
    private static void oreLayers(LimitedRegion region, IslandField field,
                                  DustWastelandsGenerator generator, BiomePlacement placement,
                                  Random random, OpenSimplexNoise warpNoise, int originX, int originZ,
                                  int count, float radius, BlockData block,
                                  Set<BiomeSurface> only, boolean vanillaBarrens) {
        if (block == null) return;
        for (int i = 0; i < count; i++) {
            int x = originX + randRange(random, 0, 15);
            int z = originZ + randRange(random, 0, 15);
            int y = randRange(random, LAYER_MIN_Y, LAYER_MAX_Y);
            oreLayer(region, field, generator, placement, warpNoise, radius, originX, originZ,
                    x, y, z, block, only, vanillaBarrens);
        }
    }

    private static void oreLayer(LimitedRegion region, IslandField field,
                                       DustWastelandsGenerator generator, BiomePlacement placement,
                                       OpenSimplexNoise warpNoise, float radius,
                                       int originX, int originZ, int centerX, int centerY, int centerZ,
                                       BlockData block, Set<BiomeSurface> only,
                                       boolean vanillaBarrens) {
        if (!inBiome(placement, only, vanillaBarrens, centerX, centerY, centerZ)) return;
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        long start = key(centerX, centerY, centerZ);
        queue.add(new long[]{centerX, centerY, centerZ});
        seen.add(start);
        while (!queue.isEmpty()) {
            long[] at = queue.poll();
            int x = (int) at[0];
            int y = (int) at[1];
            int z = (int) at[2];
            if (!region.isInRegion(x, y, z)) continue;
            if (!inLayer(warpNoise, radius, originX, originZ, centerX, centerY, centerZ, x, y, z)) continue;
            if (!field.isEndStone(x, y, z)) continue;
            // Gates the WRITE, not the spread: the layer keeps the extent it has today and only
            // loses the blocks it would have put in umbralith. Whether bclib's fillRecursive
            // spreads through a block it cannot replace is NOT DETERMINABLE (SDF.java is not in
            // this checkout), so the shape is left alone rather than changed on a guess.
            if (generator.fillsWithEndStone(placement, x, y, z)) {
                region.setBlockData(x, y, z, block);
            }
            for (int[] step : NEIGHBOURS) {
                int nx = x + step[0];
                int ny = y + step[1];
                int nz = z + step[2];
                if (seen.add(key(nx, ny, nz))) queue.add(new long[]{nx, ny, nz});
            }
        }
    }

    /**
     * The mod's {@code minecraft:biome} placement filter, which all five ore features carry.
     * <p>
     * Two halves, both load-bearing.
     * <p>
     * <b>The rings.</b> {@link BiomePlacement#quartAt} answers as if the vanilla rings did not
     * exist, so a ring column has to be decided here or not at all. {@code minecraft:the_end}
     * carries only {@code wover:is_end/center} and lists no BetterEnd ore, so it gets nothing.
     * {@code minecraft:end_barrens} carries {@code wover:is_end/barrens}, and the mod's
     * {@code wover/worldgen/biome_modifications/default_ores.json} PREPENDS flavolite, thallasium
     * and ender into every non-betterend biome with that tag -- so those three do generate in the
     * barrens and the per-biome three do not. Without this the port wrote ore into the dragon
     * fight's own island, because {@code vanillaSurface}'s filler is end stone and
     * {@code fillsWithEndStone} therefore says yes there.
     * <p>
     * <b>The biome set.</b> Quart-resolved, because that is the biome the provider painted and the
     * player reads in F3 -- {@code DustWastelandsGenerator.surfaceAt} asks the identical question
     * the identical way.
     * <p>
     * Tested at the DRAWN ORIGIN only, never per block. {@code minecraft:biome} is a placement
     * filter on the position the earlier modifiers produced; the feature then spreads on its own
     * rules. Gating every block instead would shear blobs and layers flat against a biome border.
     *
     * @param only           the biomes whose json lists this ore, or null for all 27.
     * @param vanillaBarrens whether {@code default_ores.json} injects it into {@code end_barrens}.
     */
    static boolean inBiome(BiomePlacement placement, Set<BiomeSurface> only,
                                   boolean vanillaBarrens, int x, int y, int z) {
        BiomePlacement.Ring ring = placement.ring(x, z);
        if (ring != null) {
            return vanillaBarrens && ring == BiomePlacement.Ring.BARRENS;
        }
        return only == null || only.contains(placement.quartAt(x, y, z));
    }

    private static boolean inLayer(OpenSimplexNoise warpNoise, float radius,
                                   int originX, int originZ,
                                   int centerX, int centerY, int centerZ, int x, int y, int z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        // OreLayerFeature.java:40-45 - the noise is sampled at the local offset plus the CHUNK
        // ORIGIN, not plus the layer centre.
        double offset = warpNoise.eval((dx + originX) * 0.1, (dz + originZ) * 0.1) * 8;
        double dy = ((y - centerY) + offset) / LAYER_SQUASH;
        return dx * dx + dy * dy + dz * dz < radius * radius;
    }

    /**
     * ponytail: vanilla's {@code minecraft:ore} blob algorithm is NOT FOUND in the reference tree,
     * so {@code size} is spent as a short random walk through end stone. Swap in the real ellipsoid
     * sweep if a vein's shape ever matters.
     */
    private static void oreBlobs(LimitedRegion region, IslandField field,
                                 DustWastelandsGenerator generator, BiomePlacement placement,
                                 Random random, int originX, int originZ, int minY, int maxY,
                                 int count, int size, BlockData block,
                                 Set<BiomeSurface> only, boolean vanillaBarrens) {
        if (block == null) return;
        for (int i = 0; i < count; i++) {
            int x = originX + random.nextInt(16);
            int z = originZ + random.nextInt(16);
            int y = randRange(random, minY + 8, maxY - 9);
            // Drawn before the filter so a rejected blob still consumes its three draws, which
            // keeps each ore's stream independent of which biome the chunk happens to be.
            if (!inBiome(placement, only, vanillaBarrens, x, y, z)) continue;
            for (int step = 0; step < size; step++) {
                if (region.isInRegion(x, y, z) && field.isEndStone(x, y, z)
                        && generator.fillsWithEndStone(placement, x, y, z)) {
                    region.setBlockData(x, y, z, block);
                }
                int[] move = NEIGHBOURS[random.nextInt(NEIGHBOURS.length)];
                x += move[0];
                y += move[1];
                z += move[2];
            }
        }
    }

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /** MHelper.randRange(int, int, RandomSource) [bclib@1.21] - inclusive on both ends. */
    private static int randRange(Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFFL);
    }
}
