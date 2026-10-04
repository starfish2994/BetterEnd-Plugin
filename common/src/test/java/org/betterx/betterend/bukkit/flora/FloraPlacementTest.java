package org.betterx.betterend.bukkit.flora;

import org.betterx.betterend.bukkit.biome.BiomePlacement;
import org.betterx.betterend.bukkit.biome.BiomeSurface;
import org.betterx.betterend.bukkit.config.BetterEndConfig;
import org.betterx.betterend.bukkit.flora.Flora.Engine;
import org.betterx.betterend.bukkit.flora.Flora.Row;
import org.betterx.betterend.bukkit.flora.FloraPlanner.ColumnScan;
import org.betterx.betterend.bukkit.terrain.IslandField;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flora layer, with no server: {@link FloraPlanner} names nothing outside {@code java.*} and
 * the two pure worldgen classes, so every assertion below runs on the real {@link IslandField} and
 * the real {@link BiomePlacement} without Bukkit or CraftEngine.
 * <p>
 * The sample is built once (a few seconds of real terrain) and shared, because six of the seven
 * tests below are statements about the same set of planned blocks.
 */
class FloraPlacementTest {
    private static final long SEED = 42L;

    private record Placed(int x, int y, int z, Row row, int idIndex, String value) {
    }

    /** One planned chunk: its blocks, its dominant biome, and how often each row fired. */
    private record Chunk(int chunkX, int chunkZ, BiomeSurface biome, List<Placed> blocks,
                         Map<String, Integer> placements) {
    }

    // --- the sample ---------------------------------------------------------------------------

    private static final class Sample {
        final BiomePlacement placement = BiomePlacement.bind(SEED, BetterEndConfig.DEFAULTS);
        final IslandField field = placement.field();
        final FloraPlanner.Surface surface = surfaceOracle(placement);
        final List<Chunk> chunks = new ArrayList<>();

        Sample(int wanted) {
            // A coarse grid, keeping only the chunks the void gate lets through - the same gate
            // FloraPopulator applies before allocating anything. The 8-chunk stride is half a
            // 256-block biome cell, so consecutive samples land in different biomes and the whole
            // land pool shows up; the bounded box keeps IslandField's plane memo from exploding.
            outer:
            for (int cx = -160; cx <= 160; cx += 8) {
                for (int cz = -160; cz <= 160; cz += 8) {
                    Chunk chunk = plan(this, cx, cz);
                    if (chunk != null) {
                        chunks.add(chunk);
                        if (chunks.size() >= wanted) {
                            break outer;
                        }
                    }
                }
            }
        }
    }

    private static final Sample SAMPLE = new Sample(400);
    private static final BiomeSurface.Resolved[] RESOLVED = resolved();

    private static BiomeSurface.Resolved[] resolved() {
        BiomeSurface.Resolved[] table = new BiomeSurface.Resolved[BiomeSurface.values().length];
        for (BiomeSurface biome : BiomePlacement.selectable()) {
            table[biome.ordinal()] = biome.resolve(id -> true, broken -> {
            });
        }
        return table;
    }

    /** The pack defines everything, so nothing degrades and every row is exercised. */
    private static FloraPlanner.Surface surfaceOracle(BiomePlacement placement) {
        BiomeSurface.Resolved[] resolved = new BiomeSurface.Resolved[BiomeSurface.values().length];
        for (BiomeSurface biome : BiomePlacement.selectable()) {
            resolved[biome.ordinal()] = biome.resolve(id -> true, broken -> {
            });
        }
        return new FloraPlanner.Surface(placement, resolved);
    }

    /** @return null when the void gate rejects the chunk, exactly as {@code populate} would. */
    private static Chunk plan(Sample sample, int chunkX, int chunkZ) {
        int x0 = (chunkX << 4) - 16;
        int z0 = (chunkZ << 4) - 16;
        if (!sample.field.anySolid(x0, z0, x0 + 47, z0 + 47, ColumnScan.LO, ColumnScan.HI)) {
            return null;
        }
        List<Placed> blocks = new ArrayList<>();
        Map<String, Integer> placements = new HashMap<>();
        ColumnScan scan = new ColumnScan(sample.field, chunkX, chunkZ);
        Random rng = new Random(FloraPlanner.mix(SEED, chunkX, chunkZ));
        FloraPlanner.plan(Flora.ROWS, scan, sample.surface, sample.placement, rng, chunkX, chunkZ,
                (x, y, z, row, idIndex, value) -> blocks.add(new Placed(x, y, z, row, idIndex, value)));
        for (Placed p : blocks) {
            placements.merge(p.row().name(), 1, Integer::sum);
        }
        int centreX = (chunkX << 4) + 8;
        int centreZ = (chunkZ << 4) + 8;
        int top = sample.field.topY(centreX, centreZ, ColumnScan.LO, ColumnScan.HI);
        BiomeSurface biome = top == FloraPlanner.NONE || sample.placement.ring(centreX, centreZ) != null
                ? null : sample.placement.quartAt(centreX, top, centreZ);
        return new Chunk(chunkX, chunkZ, biome, blocks, placements);
    }

    private static ColumnScan scanFor(Chunk chunk) {
        return new ColumnScan(SAMPLE.field, chunk.chunkX(), chunk.chunkZ());
    }

    // --- T1 density ---------------------------------------------------------------------------

    /**
     * {@code count_on_every_layer} re-samples its count every layer round and stops on the first
     * round that finds no surface, so a single-surface End column yields E[placements] = N/2 with
     * two rounds. The two ways to get this wrong are both catastrophic and both caught here: a
     * count drawn ONCE outside the round loop halves the density, and a loop that runs until
     * {@code count == 0} instead of until a round lands nothing gives {@code N(N+1)/2} - for
     * crystal_grass (N=20) that is 210 placements a chunk instead of 10, a 21x carpet.
     * <p>
     * The bands are wide because a real chunk is not a flat plane: void columns inside the write
     * zone, biome edges and the soil test all cut below the analytic figure. They are narrow
     * enough that either failure mode above lands far outside.
     */
    @Test
    void densityMatchesCountOnEveryLayer() {
        assertDensity("crystal_grass", BiomeSurface.CRYSTAL_MOUNTAINS);
        assertDensity("inflexia", BiomeSurface.UMBRA_VALLEY);
        assertDensity("twisted_moss", BiomeSurface.BLOSSOMING_SPIRES);
    }

    /**
     * {@code plan} returns the number of feature placements it dispatched, so passing a single-row
     * list measures that row alone - no instrumentation, no counter interface.
     */
    private void assertDensity(String rowName, BiomeSurface host) {
        Row row = Flora.ROWS.stream().filter(r -> r.name().equals(rowName)).findFirst().orElseThrow();
        List<Row> only = List.of(row);
        int chunks = 0;
        int placements = 0;
        for (Chunk chunk : SAMPLE.chunks) {
            if (chunk.biome() != host) {
                continue;
            }
            chunks++;
            ColumnScan scan = scanFor(chunk);
            Random rng = new Random(FloraPlanner.mix(SEED, chunk.chunkX(), chunk.chunkZ()));
            placements += FloraPlanner.plan(only, scan, SAMPLE.surface, SAMPLE.placement, rng,
                    chunk.chunkX(), chunk.chunkZ(), (x, y, z, r, i, v) -> {
                    });
        }
        assertTrue(chunks >= 4, rowName + ": only " + chunks + " sampled chunks host " + host);
        double mean = (double) placements / chunks;
        double analytic = row.maxPerLayer() / 2.0;
        // The band is generous upward because a stacked End column really does carry layer-1 and
        // layer-2 surfaces, which is the whole point of count_on_every_layer, and downward because
        // part of a sampled chunk's 48x48 zone is void or another biome.
        assertTrue(mean > analytic * 0.6 && mean < analytic * 3.0,
                rowName + " placed " + mean + " features/chunk in " + chunks + " " + host
                        + " chunks; count_on_every_layer(uniform(0," + row.maxPerLayer()
                        + ")) predicts " + analytic + ". Half of it means the count is drawn once"
                        + " outside the layer loop; " + (row.maxPerLayer() + 1) / 2.0 * analytic
                        + " means the loop runs until count==0 instead of until a round lands"
                        + " nothing.");
    }

    // --- T2 soil ------------------------------------------------------------------------------

    /**
     * Every ground plant stands on a block its {@code survives_on} tag accepts. The
     * {@code TreePopulator.biomeSoil} shortcut passes every other test here and fails this one in
     * umbra_valley (inflexia on umbralith, whose {@code alt} is the pallidium it actually needs),
     * megalake (umbrella_moss on the endstone_dust speckle) and neon_oasis (the inverse).
     */
    @Test
    void everyGroundPlantStandsOnItsOwnSoil() {
        int checked = 0;
        for (Chunk chunk : SAMPLE.chunks) {
            ColumnScan scan = scanFor(chunk);
            for (Placed p : chunk.blocks()) {
                boolean tall = p.row().engine() == Engine.DOUBLE && "lower".equals(p.value());
                boolean shortDouble = p.row().engine() == Engine.DOUBLE && p.value() == null;
                if (p.row().engine() != Engine.GROUND && !tall && !shortDouble) {
                    continue;
                }
                Flora.SoilSet soil = tall ? p.row().soilTall() : p.row().soil();
                if (soil == Flora.SoilSet.ANY) {
                    continue;
                }
                int ground = p.y() - 1;
                int bottom = scan.segmentBottom(p.x(), p.z(), ground);
                String id = SAMPLE.surface.at(p.x(), p.z(), ground, ground - bottom + 1);
                assertTrue(soil.accepts(id), p.row().name() + " grew on " + id
                        + " at " + p.x() + "," + p.y() + "," + p.z() + "; " + soil + " forbids it");
                checked++;
            }
        }
        assertTrue(checked > 200, "only " + checked + " ground plants to check the soil rule on");
    }

    // --- T3 support ---------------------------------------------------------------------------

    /**
     * Nothing floats and nothing is buried. A port that keeps {@code ScatterFeature}'s raw
     * {@code downRay} semantics writes plants inside hillsides; one that uses the column's global
     * top instead of the banded search floats them above overhangs; one that inverts the ceiling
     * test silently drops every vine.
     */
    @Test
    void nothingFloatsAndNothingIsBuried() {
        int walls = 0;
        int vines = 0;
        int ceilings = 0;
        int columns = 0;
        for (Chunk chunk : SAMPLE.chunks) {
            ColumnScan scan = scanFor(chunk);
            Set<Long> planned = new HashSet<>();
            for (Placed p : chunk.blocks()) {
                planned.add(cell(p.x(), p.y(), p.z()));
            }
            for (Placed p : chunk.blocks()) {
                // A COLUMN stamp's body and its cap must rest on terrain or on the block below it
                // in its own stamp; only the fur/leaf ring (idIndex 2) hangs off the side.
                if (p.row().engine() == Engine.COLUMN && p.idIndex() == 2) {
                    // A stamp's fur/leaf ring takes the four horizontals and UP, never DOWN: that
                    // cell always holds the stalk this stamp just wrote, which the mod skips
                    // because isEmptyBlock sees it. ColumnScan holds only terrain, so an air()
                    // test alone put a DOWN leaf through glow_pillar's top roots block - and half
                    // of all glow pillars are one block tall, so that was the whole plant.
                    assertFalse("down".equals(p.value()), p.row().name()
                            + " planned a DOWN ring block at " + p.x() + "," + p.y() + "," + p.z()
                            + "; that cell is its own stalk");
                }
                if (p.row().engine() == Engine.COLUMN && p.idIndex() <= 1) {
                    columns++;
                    assertTrue(scan.solid(p.x(), p.y() - 1, p.z())
                                    || planned.contains(cell(p.x(), p.y() - 1, p.z())),
                            p.row().name() + " floats: nothing under " + p.x() + ","
                                    + p.y() + "," + p.z());
                }
                // The one cell the mod itself does not guarantee is a double plant's upper half.
                if (!"upper".equals(p.value())) {
                    assertFalse(scan.solid(p.x(), p.y(), p.z()),
                            p.row().name() + " was written inside terrain at "
                                    + p.x() + "," + p.y() + "," + p.z());
                }
                switch (p.row().engine()) {
                    case GROUND -> assertTrue(scan.solid(p.x(), p.y() - 1, p.z()),
                            p.row().name() + " floats at " + p.x() + "," + p.y() + "," + p.z());
                    case DOUBLE -> {
                        if (!"upper".equals(p.value())) {
                            assertTrue(scan.solid(p.x(), p.y() - 1, p.z()),
                                    p.row().name() + " floats at " + p.y());
                        }
                    }
                    case WALL -> {
                        walls++;
                        int dx = "east".equals(p.value()) ? 1 : "west".equals(p.value()) ? -1 : 0;
                        int dz = "south".equals(p.value()) ? 1 : "north".equals(p.value()) ? -1 : 0;
                        assertTrue(scan.solid(p.x() - dx, p.y(), p.z() - dz),
                                p.row().name() + " clings to air, facing=" + p.value());
                    }
                    case CEILING -> {
                        ceilings++;
                        assertTrue(scan.solid(p.x(), p.y() + 1, p.z()),
                                p.row().name() + " hangs from air at " + p.y());
                    }
                    case VINE -> {
                        vines++;
                        if ("top".equals(p.value())) {
                            assertTrue(scan.solid(p.x(), p.y() + 1, p.z()),
                                    p.row().name() + " strand hangs from air at " + p.y());
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        assertTrue(walls > 0, "no wall plant was ever placed - the box scan is dead");
        assertTrue(vines + ceilings > 0, "no vine or ceiling plant - the ceiling scan is dead");
        assertTrue(columns > 0, "no column stamp was ever placed");
    }

    private static long cell(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) z << 12) ^ y;
    }

    // --- T4 surface agreement -----------------------------------------------------------------

    /**
     * The cap/alt arithmetic, every branch. A version that drops the {@code cap > 0} guard returns
     * the top block for a one-block dusty segment where {@code fillSegment:203-211} wrote the
     * filler; one that applies the alt to the whole cap band instead of the top block speckles
     * five blocks deep.
     */
    @Test
    void surfaceArithmeticMatchesTheGenerator() {
        BiomeSurface.Resolved dusty = new BiomeSurface.Resolved(
                "betterend:endstone_dust", "minecraft:end_stone", "betterend:end_moss", true);
        BiomeSurface.Resolved plain = new BiomeSurface.Resolved(
                "betterend:umbralith", "betterend:umbralith", "betterend:pallidium_full", false);
        BiomeSurface.Resolved noAlt = new BiomeSurface.Resolved(
                "betterend:sangnum", "minecraft:end_stone", null, false);

        assertEquals("minecraft:end_stone", FloraPlanner.Surface.block(dusty, 0, false),
                "a one-block dusty segment has no cap, so its floor is the filler");
        assertEquals("minecraft:end_stone", FloraPlanner.Surface.block(dusty, 0, true),
                "no cap means no speckle either");
        assertEquals("betterend:endstone_dust", FloraPlanner.Surface.block(dusty, 5, false));
        assertEquals("betterend:end_moss", FloraPlanner.Surface.block(dusty, 5, true));
        assertEquals("betterend:umbralith", FloraPlanner.Surface.block(plain, 0, false),
                "a non-dusty biome caps one block whatever dustDepth says");
        assertEquals("betterend:pallidium_full", FloraPlanner.Surface.block(plain, 0, true));
        assertEquals("betterend:sangnum", FloraPlanner.Surface.block(noAlt, 3, true),
                "a null alt must never be returned");
    }

    /**
     * The oracle must return what {@code DustWastelandsGenerator} actually wrote - not just be
     * self-consistent. The loop below is transcribed from {@code generateNoise:161-179} +
     * {@code fillSegment:196-208} + {@code surfaceAt:219-226}, NOT from {@link FloraPlanner}:
     * cut the density column into segments the generator's way, apply its cap and speckle rules,
     * and compare block ids. A {@code Surface} that sampled the biome at the plant instead of the
     * segment top, passed the wrong segment height to {@code dustDepth}, dropped the ring arm or
     * applied the alt below the top block fails here and passes every other test in this file,
     * because every other test asks the same oracle the planner asked.
     */
    @Test
    void theSurfaceOracleIsWhatTheGeneratorWrote() {
        Random rng = new Random(11);
        int compared = 0;
        int rings = 0;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            int x = rng.nextInt(4000) - 2000;
            int z = rng.nextInt(4000) - 2000;
            int maxY = 256;
            double[] column = SAMPLE.field.densityColumn(x, z, 0, maxY - 1);
            int bottom = Integer.MIN_VALUE;
            for (int y = 0; y <= maxY; y++) {
                boolean solid = y < maxY && column[y] > 0;
                if (solid) {
                    if (bottom == Integer.MIN_VALUE) {
                        bottom = y;
                    }
                    continue;
                }
                if (bottom == Integer.MIN_VALUE) {
                    continue;
                }
                int top = y - 1;
                String expected;
                if (SAMPLE.placement.ring(x, z) != null) {
                    expected = "minecraft:end_stone";
                    rings++;
                } else {
                    BiomeSurface.Resolved r = RESOLVED[
                            SAMPLE.placement.quartAt(x, top, z).ordinal()];
                    int cap = r.dusty() ? SAMPLE.field.dustDepth(x, z, top - bottom + 1) : 1;
                    expected = top >= top - cap + 1 ? r.top() : r.under();
                    if (r.alt() != null && cap > 0 && SAMPLE.field.altSurface(x, z)) {
                        expected = r.alt();
                    }
                }
                assertEquals(expected, SAMPLE.surface.at(x, z, top, top - bottom + 1),
                        "the flora oracle disagrees with fillSegment at " + x + "," + top + "," + z);
                seen.add(expected);
                compared++;
                bottom = Integer.MIN_VALUE;
            }
        }
        assertTrue(compared > 2000, "only " + compared + " segments compared");
        assertTrue(rings > 0, "no ring column sampled - the vanilla-ring arm is untested");
        assertTrue(seen.size() >= 8, "only " + seen.size()
                + " distinct surface blocks exercised; the sample is degenerate");
    }

    /**
     * Every COLUMN row must have a stamp in {@code FloraPlanner.column}'s switch, whose default
     * arm throws - on the worldgen thread, for a row someone adds to the table later.
     */
    @Test
    void everyColumnRowHasAStamp() {
        assertEquals(Set.of("blue_vine", "glow_pillar", "lanceleaf", "lumecorn", "large_amaranita"),
                Flora.ROWS.stream().filter(r -> r.engine() == Engine.COLUMN)
                        .map(Row::name).collect(java.util.stream.Collectors.toSet()),
                "a COLUMN row without a stamp throws IllegalStateException from generation");
    }

    /**
     * The bitmap must agree with {@link IslandField#topY} exactly. An off-by-one in the bit walk,
     * or a band clamped differently from the field's, buries or floats the whole world.
     */
    @Test
    void theBitmapAgreesWithTheField() {
        Random rng = new Random(7);
        int checked = 0;
        for (int i = 0; i < 60; i++) {
            int chunkX = rng.nextInt(400) - 200;
            int chunkZ = rng.nextInt(400) - 200;
            ColumnScan scan = new ColumnScan(SAMPLE.field, chunkX, chunkZ);
            for (int j = 0; j < 170; j++) {
                int x = scan.minX() + rng.nextInt(48);
                int z = scan.minZ() + rng.nextInt(48);
                assertEquals(SAMPLE.field.topY(x, z, ColumnScan.LO, ColumnScan.HI),
                        scan.top(x, z), "bitmap top disagrees at " + x + "," + z);
                if (checked % 20 == 0) {
                    // Every twentieth column block for block, plus the segment walk the soil test
                    // depends on: top() alone can agree while every bit under it is shifted.
                    double[] column = SAMPLE.field.densityColumn(x, z, ColumnScan.LO, ColumnScan.HI);
                    for (int y = ColumnScan.LO; y <= ColumnScan.HI; y++) {
                        assertEquals(column[y - ColumnScan.LO] > 0, scan.solid(x, y, z),
                                "bitmap disagrees with the field at " + x + "," + y + "," + z);
                    }
                    int top = scan.top(x, z);
                    if (top != FloraPlanner.NONE) {
                        int expected = top;
                        while (expected > ColumnScan.LO && column[expected - 1 - ColumnScan.LO] > 0) {
                            expected--;
                        }
                        assertEquals(expected, scan.segmentBottom(x, z, top),
                                "segment bottom disagrees at " + x + "," + z);
                    }
                }
                checked++;
            }
        }
        assertTrue(checked >= 10000, "only " + checked + " columns compared");
    }

    // --- T5 determinism -----------------------------------------------------------------------

    /**
     * Replanning a chunk, and planning eight chunks on four threads in a shuffled order, must both
     * reproduce the single-threaded result block for block. The mod's shared static
     * {@code Direction[] DIR} shuffle ({@code WallScatterFeature.java:18,95-102}) is exactly the
     * trap this catches; so is any lazy init or unsalted use of the populator's {@code Random}.
     */
    @Test
    void planningIsDeterministicAndThreadSafe() throws Exception {
        List<Chunk> targets = SAMPLE.chunks.subList(0, Math.min(8, SAMPLE.chunks.size()));
        List<String> expected = new ArrayList<>();
        for (Chunk chunk : targets) {
            expected.add(digest(plan(SAMPLE, chunk.chunkX(), chunk.chunkZ())));
        }

        List<Chunk> shuffled = new ArrayList<>(targets);
        Collections.shuffle(shuffled, new Random(3));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Map<String, Future<String>> results = new HashMap<>();
            for (Chunk chunk : shuffled) {
                results.put(chunk.chunkX() + ":" + chunk.chunkZ(),
                        pool.submit(() -> digest(plan(SAMPLE, chunk.chunkX(), chunk.chunkZ()))));
            }
            for (int i = 0; i < targets.size(); i++) {
                Chunk chunk = targets.get(i);
                assertEquals(expected.get(i),
                        results.get(chunk.chunkX() + ":" + chunk.chunkZ()).get(),
                        "chunk " + chunk.chunkX() + "," + chunk.chunkZ() + " differs across threads");
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        }
    }

    private static String digest(Chunk chunk) {
        StringBuilder out = new StringBuilder();
        for (Placed p : chunk.blocks()) {
            out.append(p.x()).append(',').append(p.y()).append(',').append(p.z()).append(' ')
                    .append(p.row().name()).append('/').append(p.idIndex()).append('=')
                    .append(p.value()).append('\n');
        }
        return out.toString();
    }

    // --- T6 write zone ------------------------------------------------------------------------

    /**
     * Every planned block sits inside the 48x48 a populator may write
     * ({@code CraftLimitedRegion.java:49,66-73}). Without the clip, a radius-12 disc or a
     * radius-6 box makes {@code setBlockData} THROW - it does not no-op - and kills the worldgen
     * worker. Y is not clipped here; {@code FloraPopulator} guards every write with
     * {@code isInRegion}, which is what covers it.
     */
    @Test
    void nothingIsPlannedOutsideTheWriteZone() {
        for (Chunk chunk : SAMPLE.chunks) {
            int x0 = (chunk.chunkX() << 4) - 16;
            int z0 = (chunk.chunkZ() << 4) - 16;
            for (Placed p : chunk.blocks()) {
                assertTrue(p.x() >= x0 && p.x() <= x0 + 47 && p.z() >= z0 && p.z() <= z0 + 47,
                        p.row().name() + " planned at " + p.x() + "," + p.z()
                                + ", outside chunk " + chunk.chunkX() + "," + chunk.chunkZ());
            }
        }
    }

    // --- T7 non-degenerate --------------------------------------------------------------------

    /**
     * The sample that passes every test above and is still worthless: one engine silently never
     * fires, or every write is the same block. Six distinct host biomes and a spread of ids is
     * what "a player walking the End sees each biome's signature flora" reduces to.
     */
    @Test
    void theWorldIsNotOneBlockRepeated() {
        Map<String, Integer> perId = new HashMap<>();
        Set<BiomeSurface> hosts = new HashSet<>();
        Set<Engine> engines = new HashSet<>();
        int total = 0;
        for (Chunk chunk : SAMPLE.chunks) {
            for (Placed p : chunk.blocks()) {
                perId.merge(p.row().ids().get(p.idIndex()), 1, Integer::sum);
                engines.add(p.row().engine());
                total++;
            }
            if (!chunk.blocks().isEmpty() && chunk.biome() != null) {
                // The biome that actually produced flora, not the six a fired row declares.
                hosts.add(chunk.biome());
            }
        }
        assertTrue(total > 1000, "only " + total + " blocks planned over " + SAMPLE.chunks.size()
                + " land chunks - the flora layer is barely alive");
        assertTrue(perId.size() >= 12, "only " + perId.size() + " distinct block ids written");
        for (Map.Entry<String, Integer> e : perId.entrySet()) {
            assertTrue(e.getValue() <= total * 0.6,
                    e.getKey() + " is " + (100 * e.getValue() / total) + "% of all writes");
        }
        assertTrue(hosts.size() >= 6, "only " + hosts.size() + " host biomes produced flora");
        assertTrue(engines.contains(Engine.GROUND), "no GROUND plant");
        assertTrue(engines.contains(Engine.WALL), "no WALL plant");
        assertTrue(engines.contains(Engine.VINE) || engines.contains(Engine.CEILING),
                "neither vines nor ceiling plants fired");
    }

    /** Every emitted property value must be one the pack declares, or the palette cannot map it. */
    @Test
    void everyEmittedStateIsDeclared() {
        for (Chunk chunk : SAMPLE.chunks) {
            for (Placed p : chunk.blocks()) {
                Flora.Prop prop = FloraPlanner.propOf(p.row(), p.idIndex());
                if (prop == Flora.Prop.NONE) {
                    assertEquals(null, p.value(),
                            p.row().name() + " emitted a value for a property-less block");
                } else {
                    assertTrue(prop.stateValues().contains(p.value()),
                            p.row().name() + " emitted " + prop.property() + "=" + p.value()
                                    + ", which " + prop + " does not declare");
                }
            }
        }
    }

    // --- T8 void and core ---------------------------------------------------------------------

    /**
     * A void chunk and a vanilla-core chunk both plan nothing: the first because the terrain has
     * no surface at all, the second because {@code BiomePlacement.ring} is non-null there and
     * BetterEnd paints no biome inside radius 1024. A missing void gate builds gardens in the
     * emptiness; a missing ring gate carpets the dragon's island in moss.
     */
    @Test
    void theVoidAndTheVanillaCoreStayEmpty() {
        assertEquals(FloraPlanner.NONE, SAMPLE.field.topY(100, 0, ColumnScan.LO, ColumnScan.HI),
                "(100,0) is not void any more; pick another column");
        Chunk core = plan(SAMPLE, 0, 0);
        assertTrue(core == null || core.blocks().isEmpty(),
                "flora was planned inside the vanilla end core");

        int voidChunks = 0;
        for (int cx = -400; cx <= 400 && voidChunks < 8; cx += 37) {
            int x0 = (cx << 4) - 16;
            if (SAMPLE.field.anySolid(x0, -16, x0 + 47, 31, ColumnScan.LO, ColumnScan.HI)) {
                continue;
            }
            voidChunks++;
            Chunk chunk = plan(SAMPLE, cx, 0);
            assertTrue(chunk == null, "the void gate let chunk " + cx + ",0 through");
        }
        assertTrue(voidChunks > 0, "no void chunk found to test the gate on");
    }

    /**
     * The flora layer's terrain cost, bounded. Every question all six engines ask is a boolean
     * about one block, so {@link ColumnScan} builds each column's solidity bitmap once from
     * {@code densityColumn} and answers the rest with bit tests; a port that called
     * {@code densityColumn} per question would build thousands of columns a chunk instead.
     * {@code generateNoise} itself builds 256 (one per block column), which is the yardstick.
     */
    @Test
    void theTerrainCostStaysNearTheGenerator() {
        long columns = 0;
        int chunks = 0;
        for (Chunk chunk : SAMPLE.chunks) {
            ColumnScan scan = scanFor(chunk);
            Random rng = new Random(FloraPlanner.mix(SEED, chunk.chunkX(), chunk.chunkZ()));
            FloraPlanner.plan(Flora.ROWS, scan, SAMPLE.surface, SAMPLE.placement, rng,
                    chunk.chunkX(), chunk.chunkZ(), (x, y, z, r, i, v) -> {
                    });
            columns += scan.columnsBuilt();
            chunks++;
        }
        double mean = (double) columns / chunks;
        System.out.printf("flora builds %.1f density columns per land chunk"
                + " (generateNoise builds 256, the scan's own zone holds 2304)%n", mean);
        assertTrue(mean < 512, "flora builds " + mean + " density columns a chunk, more than twice"
                + " generateNoise's 256 - the per-column bitmap memo is not doing its job");
    }

    /** A diagnostic, not an assertion: the per-chunk cost the report quotes. */
    @Test
    void reportTheMeasuredCost() {
        Map<BiomeSurface, int[]> perBiome = new HashMap<>();
        for (Chunk chunk : SAMPLE.chunks) {
            if (chunk.biome() == null) {
                continue;
            }
            int[] acc = perBiome.computeIfAbsent(chunk.biome(), b -> new int[2]);
            acc[0]++;
            acc[1] += chunk.blocks().size();
        }
        StringBuilder out = new StringBuilder("flora writes per land chunk, seed " + SEED + ":\n");
        perBiome.entrySet().stream()
                .sorted((a, b) -> Double.compare((double) b.getValue()[1] / b.getValue()[0],
                        (double) a.getValue()[1] / a.getValue()[0]))
                .forEach(e -> out.append(String.format("  %-22s %5.1f  (%d chunks)%n",
                        e.getKey().id(), (double) e.getValue()[1] / e.getValue()[0],
                        e.getValue()[0])));
        out.append("rows that fired, blocks over ").append(SAMPLE.chunks.size())
                .append(" land chunks:\n");
        Map<String, Integer> perRow = new HashMap<>();
        for (Chunk chunk : SAMPLE.chunks) {
            chunk.placements().forEach((name, n) -> perRow.merge(name, n, Integer::sum));
        }
        perRow.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> out.append(String.format("  %-26s %6d%n", e.getKey(), e.getValue())));
        System.out.println(out);
        assertFalse(perBiome.isEmpty(), "the sample holds no biome-attributable chunk");
    }
}
