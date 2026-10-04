package org.betterx.betterend.bukkit;

import org.betterx.betterend.bukkit.terrain.IslandField;
import org.betterx.betterend.bukkit.vanilla.EndSpikes;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.LimitedRegion;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BetterEndPopulator#reskinSpikes} -- the flavolite reskin of vanilla's ten obsidian spikes.
 * <p>
 * The tests that matter are not "does it look right", they are the two ways this can end the dragon
 * fight: writing at or above the spike height (bedrock cap, fire, crystal, cage) and writing into a
 * cell {@link IslandField} calls solid, which would flip {@code isPillarColumn} and let
 * {@link BetterEndPopulator#repairCore} entomb a crystal. Both are asserted directly, and the
 * repair's own output is compared with and without the reskin -- with the fake region modelling
 * CraftEngine's {@code deceive-bukkit-material}, so a reskinned cell reads back as something that
 * is NOT obsidian, exactly as it would in game.
 * <p>
 * Blocks are {@link Proxy} stubs: {@code createBlockData()} needs a server and there is no
 * {@code Material.FLAVOLITE}, but the pass only passes the object through, so a distinguishable
 * identity is all it needs.
 */
class SpikeReskinTest {
    private static final long SEED = 42L;
    private static final int MAX_Y = 127;
    private static final int FLOOR = 60; // BetterEndPopulator.REPAIR_FLOOR
    private static final BlockData FLAVOLITE = stub("flavolite");
    private static final BlockData ACCENT = stub("accent");
    private static final BlockData CRYING = stub("crying");

    /**
     * Every write lands on the rim of some spike's obsidian circle, above the ground and under the
     * cap; the four cardinal ribs run unbroken; the crown wraps the whole rim. An implementation
     * that filled the circle, or the square, fails the rim test; one that only did the crown fails
     * rib continuity; one that only did the ribs fails the crown count.
     */
    @Test
    void reskinBuildsRibsAndACrownOnTheRimOnly() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = tallestSpike();
        int cx = spike.centerX() >> 4;
        int cz = spike.centerZ() >> 4;
        FakeRegion region = new FakeRegion(field, cx, cz);

        int written = BetterEndPopulator.reskinSpikes(region.region, field,
                FLAVOLITE, ACCENT, CRYING, SEED, cx, cz);

        assertTrue(written > 100, "only " + written + " blocks written - the sample is degenerate");
        assertEquals(written, region.blocks.size(), "a cell was written twice in one pass");
        for (long k : region.blocks.keySet()) {
            assertTrue(onSomeSpikeRim(field, k),
                    "wrote at " + x(k) + "," + y(k) + "," + z(k) + ", which is on no spike's rim");
        }

        int r = spike.radius();
        int top = spike.height() - 1;
        int foot = field.topY(spike.centerX(), spike.centerZ(), FLOOR, top);
        assertTrue(foot > FLOOR, "the spike stands in void (topY " + foot + ") - nothing to build on");

        // The four cardinal ribs, unbroken from just above their own ground to just under the cap.
        int accentCells = 0;
        for (int[] rib : new int[][]{{r, 0}, {-r, 0}, {0, r}, {0, -r}}) {
            int x = spike.centerX() + rib[0];
            int z = spike.centerZ() + rib[1];
            int base = Math.max(foot, Math.max(field.topY(x, z, FLOOR, top), FLOOR));
            assertTrue(top - base > 10, "rib " + rib[0] + "," + rib[1] + " is only " + (top - base) + " tall");
            for (int y = base + 1; y <= top; y++) {
                BlockData at = region.blocks.get(key(x, y, z));
                assertTrue(at == ACCENT || at == FLAVOLITE || at == CRYING,
                        "rib " + rib[0] + "," + rib[1] + " has a hole at y" + y + " (" + at + ")");
                if (at == ACCENT) accentCells++;
            }
        }
        // The wall substitute goes on the four cardinal rib columns and NOWHERE else -- the region
        // also holds a neighbouring spike's writes, so this is checked against each cell's own
        // owner. Swapping the substitute for a real flavolite_wall must move exactly these cells.
        assertTrue(accentCells > 20, "only " + accentCells + " rib cells use the wall substitute");
        for (Map.Entry<Long, BlockData> e : region.blocks.entrySet()) {
            if (e.getValue() != ACCENT) continue;
            long k = e.getKey();
            EndSpikes.Spike owner = ownerOf(k);
            int odx = x(k) - owner.centerX();
            int odz = z(k) - owner.centerZ();
            String where = x(k) + "," + y(k) + "," + z(k);
            assertTrue((odx == 0) != (odz == 0), "the wall substitute leaked off a rib at " + where);
            assertEquals(owner.radius(), Math.max(Math.abs(odx), Math.abs(odz)),
                    "the wall substitute leaked off the rim at " + where);
            assertTrue(y(k) <= owner.height() - 1 - 4,
                    "the wall substitute leaked into the crown at " + where);
        }

        // The crown reaches every rim cell of the top layer, ribs and corners alike.
        int crownCells = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) continue;
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                assertTrue(region.blocks.containsKey(key(spike.centerX() + dx, top, spike.centerZ() + dz)),
                        "the crown misses the rim cell " + dx + "," + dz);
                crownCells++;
            }
        }
        assertTrue(crownCells >= 8, "the rim collapsed to " + crownCells + " cells");
        // ... and the shaft's interior was left obsidian.
        assertTrue(!region.blocks.containsKey(key(spike.centerX(), top, spike.centerZ())),
                "the reskin filled the shaft interior");
    }

    /**
     * Contract 4, the hard one. Vanilla's bedrock cap sits at {@code height}, the fire and the
     * crystal above it, and a guarded spike's iron-bar cage runs {@code height..height+3}. One write
     * in that band ends the fight. Asserted over all ten spikes, guarded ones included.
     */
    @Test
    void reskinNeverReachesTheCapTheCrystalOrTheCage() {
        IslandField field = new IslandField(SEED);
        List<EndSpikes.Spike> guarded = EndSpikes.forSeed(SEED).stream()
                .filter(EndSpikes.Spike::guarded).toList();
        assertEquals(2, guarded.size(), "expected exactly two guarded spikes");

        int total = 0;
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            int cx = spike.centerX() >> 4;
            int cz = spike.centerZ() >> 4;
            FakeRegion region = new FakeRegion(field, cx, cz);
            total += BetterEndPopulator.reskinSpikes(region.region, field,
                    FLAVOLITE, ACCENT, CRYING, SEED, cx, cz);
            for (long k : region.blocks.keySet()) {
                EndSpikes.Spike owner = ownerOf(k);
                assertTrue(y(k) < owner.height(),
                        "wrote at y" + y(k) + ", at or above spike height " + owner.height()
                                + " - that is the bedrock cap, the crystal or the cage");
                assertTrue(!(x(k) == owner.centerX() && z(k) == owner.centerZ()),
                        "wrote in the centre column, which carries the cap and the crystal");
            }
        }
        assertTrue(total > 1000, "only " + total + " blocks over all ten spikes");
    }

    /**
     * The ninefold chunk overlap and every server restart. Every value written is a pure function of
     * (seed, x, y, z), so a second pass must produce a byte-identical region -- compared as the
     * whole map, not just the count, because a per-chunk {@link java.util.Random} would keep the
     * count and move the speckle.
     */
    @Test
    void reskinIsIdempotentAndOrderIndependent() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = tallestSpike();
        int cx = spike.centerX() >> 4;
        int cz = spike.centerZ() >> 4;

        FakeRegion once = new FakeRegion(field, cx, cz);
        int first = BetterEndPopulator.reskinSpikes(once.region, field, FLAVOLITE, ACCENT, CRYING, SEED, cx, cz);
        Map<Long, BlockData> afterFirst = new LinkedHashMap<>(once.blocks);
        int second = BetterEndPopulator.reskinSpikes(once.region, field, FLAVOLITE, ACCENT, CRYING, SEED, cx, cz);
        assertEquals(first, second, "the second pass wrote a different number of blocks");
        assertEquals(afterFirst, once.blocks, "the second pass changed the region");

        // The same cells reached from a NEIGHBOURING chunk's pass must get the same blocks.
        FakeRegion neighbour = new FakeRegion(field, cx + 1, cz);
        BetterEndPopulator.reskinSpikes(neighbour.region, field, FLAVOLITE, ACCENT, CRYING, SEED, cx + 1, cz);
        int shared = 0;
        for (Map.Entry<Long, BlockData> e : neighbour.blocks.entrySet()) {
            if (!afterFirst.containsKey(e.getKey())) continue;
            shared++;
            assertEquals(afterFirst.get(e.getKey()), e.getValue(),
                    "the neighbouring chunk wrote a different block at " + x(e.getKey()) + ","
                            + y(e.getKey()) + "," + z(e.getKey()));
        }
        assertTrue(shared > 20, "the two chunks overlap on only " + shared + " cells");
    }

    /**
     * The speckle must happen and must not take over -- 1 in 24, so a few percent. A hash that
     * always returned false, or whose low bits are degenerate, goes red here.
     */
    @Test
    void theSpeckleRateIsAboutOneInTwentyFour() {
        IslandField field = new IslandField(SEED);
        int crying = 0;
        int total = 0;
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            int cx = spike.centerX() >> 4;
            int cz = spike.centerZ() >> 4;
            FakeRegion region = new FakeRegion(field, cx, cz);
            BetterEndPopulator.reskinSpikes(region.region, field, FLAVOLITE, ACCENT, CRYING, SEED, cx, cz);
            for (BlockData at : region.blocks.values()) {
                total++;
                if (at == CRYING) crying++;
            }
        }
        assertTrue(total > 1000, "only " + total + " blocks over all ten spikes");
        double rate = (double) crying / total;
        assertTrue(rate > 0.015 && rate < 0.075, "speckle rate " + rate + " is not near 1/24");
    }

    /**
     * The regression that would cost the fight. A reskinned cell no longer reads as OBSIDIAN
     * (CraftEngine's deceive-bukkit-material, modelled here as BRICKS), so a reskin that touched a
     * pillar column inside the terrain would flip {@code isPillarColumn} and
     * {@link BetterEndPopulator#repairCore} would fill that pillar's air with end stone -- on a
     * short spike, around the crystal. Guarded by never writing where {@link IslandField} says
     * solid; checked end to end by running the repair with and without the reskin and demanding an
     * identical result.
     */
    @Test
    void theRepairSeesAnIdenticalWorldWithOrWithoutTheReskin() {
        IslandField field = new IslandField(SEED);
        EndSpikes.Spike spike = tallestSpike();
        int cx = spike.centerX() >> 4;
        int cz = spike.centerZ() >> 4;

        FakeRegion plain = new FakeRegion(field, cx, cz);
        carveEverySpike(plain, field);
        int plainFilled = BetterEndPopulator.repairCore(plain.region, field, null, cx, cz, MAX_Y);
        assertTrue(plainFilled > 0, "the synthetic carve gave the repair nothing to do");
        Set<Long> plainRepaired = new HashSet<>(plain.repaired);

        FakeRegion reskinned = new FakeRegion(field, cx, cz);
        carveEverySpike(reskinned, field);
        int written = BetterEndPopulator.reskinSpikes(reskinned.region, field,
                FLAVOLITE, ACCENT, CRYING, SEED, cx, cz);
        assertTrue(written > 100, "the reskin wrote " + written + " blocks, so this proves little");
        int reskinnedFilled = BetterEndPopulator.repairCore(reskinned.region, field, null, cx, cz, MAX_Y);

        assertEquals(plainFilled, reskinnedFilled, "the reskin changed how much the repair fills");
        assertEquals(plainRepaired, new HashSet<>(reskinned.repaired),
                "the reskin changed WHERE the repair fills - isPillarColumn no longer sees the pillar");
        for (long k : reskinned.blocks.keySet()) {
            assertTrue(!reskinned.repaired.contains(k), "the repair overwrote a reskinned block");
            assertTrue(!field.isSolid(x(k), y(k), z(k)),
                    "the reskin wrote into terrain the island calls solid, at "
                            + x(k) + "," + y(k) + "," + z(k) + " - that is what flips isPillarColumn");
        }
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** The tallest spike on this seed -- the largest radius and the most room above the island. */
    private static EndSpikes.Spike tallestSpike() {
        return EndSpikes.forSeed(SEED).stream()
                .max((a, b) -> Integer.compare(a.height(), b.height())).orElseThrow();
    }

    private static EndSpikes.Spike ownerOf(long k) {
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            if (Math.abs(x(k) - spike.centerX()) <= spike.radius()
                    && Math.abs(z(k) - spike.centerZ()) <= spike.radius()) {
                return spike;
            }
        }
        throw new AssertionError("no spike owns " + x(k) + "," + y(k) + "," + z(k));
    }

    private static boolean onSomeSpikeRim(IslandField field, long k) {
        EndSpikes.Spike spike = ownerOf(k);
        int dx = x(k) - spike.centerX();
        int dz = z(k) - spike.centerZ();
        int foot = field.topY(spike.centerX(), spike.centerZ(), FLOOR, spike.height() - 1);
        return dx * dx + dz * dz <= spike.radius() * spike.radius() + 1
                && Math.max(Math.abs(dx), Math.abs(dz)) == spike.radius()
                && y(k) > (foot == Integer.MIN_VALUE ? FLOOR : foot)
                && y(k) < spike.height();
    }

    /**
     * Vanilla's {@code placeSpike}: the obsidian circle up to {@code height}, the bedrock cap at it,
     * and the rest of the square footprint cleared to air wherever {@code y > 65}
     * ({@code EndSpikeFeature.java:87-92, 122}). Applied to every spike, because a region reaches
     * more than one of them.
     */
    private static void carveEverySpike(FakeRegion region, IslandField field) {
        boolean carvedSolid = false;
        for (EndSpikes.Spike spike : EndSpikes.forSeed(SEED)) {
            int r = spike.radius();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = spike.centerX() + dx;
                    int z = spike.centerZ() + dz;
                    boolean inCircle = dx * dx + dz * dz <= r * r + 1;
                    for (int y = FLOOR; y <= spike.height() + 10; y++) {
                        if (inCircle && y < spike.height()) {
                            region.vanilla.put(key(x, y, z), Material.OBSIDIAN);
                        } else if (y > 65) {
                            if (field.isSolid(x, y, z)) carvedSolid = true;
                            region.vanilla.put(key(x, y, z), Material.AIR);
                        }
                    }
                    if (inCircle) region.vanilla.put(key(x, spike.height(), z), Material.BEDROCK);
                }
            }
        }
        assertTrue(carvedSolid, "the synthetic carve removed no solid terrain - the island is too low");
    }

    private static BlockData stub(String name) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * Records reskin writes separately from the vanilla block map the repair reads, so one region
     * serves both passes. A reskin write also stamps the vanilla view with BRICKS -- CraftEngine's
     * {@code block.deceive-bukkit-material.default} -- which is what makes
     * {@link #theRepairSeesAnIdenticalWorldWithOrWithoutTheReskin} a real test rather than a
     * tautology.
     */
    private static final class FakeRegion {
        final Map<Long, BlockData> blocks = new LinkedHashMap<>();
        final Map<Long, Material> vanilla = new HashMap<>();
        final Set<Long> repaired = new HashSet<>();
        final LimitedRegion region;

        FakeRegion(IslandField field, int chunkX, int chunkZ) {
            region = (LimitedRegion) Proxy.newProxyInstance(
                    LimitedRegion.class.getClassLoader(),
                    new Class<?>[]{LimitedRegion.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isInRegion" -> Math.abs((((Integer) args[0]) >> 4) - chunkX) <= 1
                                && Math.abs((((Integer) args[2]) >> 4) - chunkZ) <= 1;
                        case "getType" -> {
                            long k = key((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                            yield vanilla.computeIfAbsent(k, kk -> field.isSolid(x(kk), y(kk), z(kk))
                                    ? Material.END_STONE : Material.AIR);
                        }
                        case "setBlockData" -> {
                            long k = key((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                            BlockData data = (BlockData) args[3];
                            if (data == null) { // repairCore's end stone; null, as in BetterEndPopulatorTest
                                vanilla.put(k, Material.END_STONE);
                                repaired.add(k);
                            } else {
                                blocks.put(k, data);
                                vanilla.put(k, Material.BRICKS); // deceive-bukkit-material
                            }
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "FakeRegion";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFFL);
    }

    private static int x(long k) {
        return (int) (k >> 42);
    }

    private static int z(long k) {
        return (int) ((k >> 20) & 0x3FFFFF) << 10 >> 10;
    }

    private static int y(long k) {
        return (int) (k & 0xFFFFF);
    }
}
