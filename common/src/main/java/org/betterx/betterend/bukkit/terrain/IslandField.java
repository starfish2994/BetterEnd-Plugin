package org.betterx.betterend.bukkit.terrain;

import org.betterx.betterend.bukkit.config.BetterEndConfig;
import org.betterx.betterend.bukkit.config.LayerSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BetterEnd's End island terrain, ported from the mod's
 * {@code org.betterx.betterend.world.generator.TerrainGenerator} / {@code IslandLayer} /
 * {@code LayerOptions} and the bclib SDF primitives they compose.
 * <p>
 * Pure math: no Bukkit, no CraftEngine, nothing outside {@code java.*}. Immutable after
 * construction (the noise permutation tables are final and read-only), so one instance is
 * safe to call from every worldgen worker at once. Nothing here depends on generation order.
 * <p>
 * Every {@code SDF*} / {@code MHelper} / {@code SplineHelper} citation below is read off
 * {@code Reference/BCLib}, which is release/26.1, while WorldWeaver and BetterEnd are 26.2. These
 * are stable geometry internals with no version-gated code, but they are unverified against a
 * 26.2 BCLib.
 */
public final class IslandField {
    /**
     * Vanilla {@code minecraft:end} noise settings height. Load-bearing in three places at once -
     * island centre Y, the fade-out start and the hard air ceiling - so it is one constant, never
     * three, exactly as the mod carries one {@code maxHeight} variable for all three.
     * <p>
     * The VALUE is right; the old justification for it was not. The {@code /128} divisors in
     * GeneratorConfig.java:78,84,90 are not the noise height: LayerOptions.java:22-23,68-74 clamps
     * arguments 3 and 4 (averageHeight, heightVariation) to {@code [0, 1]}, so they are FRACTIONS;
     * LayerOptions.java:34-35 derives minY/maxY from them and IslandLayer.java:78 scales the result
     * by the runtime {@code maxHeight}. That runtime value is {@code noiseSettings.height()}
     * (TerrainGenerator.java:134,:221; NoiseBasedChunkGeneratorHeightMixin:87-93), which is in
     * neither BCLib nor WorldWeaver - it is vanilla data.
     * <p>
     * ponytail: unverified constant. It is derivable from the CFR decompile of
     * {@code paper-26.2.jar} this port already used for {@code EndSpikes}; read
     * {@code minecraft:end}'s noise-settings height off that rather than trusting this line.
     */
    private static final int MAX_HEIGHT = 128;
    /**
     * Highest Y that can ever be solid: {@link #density} hard-returns -1 at {@code py >= MAX_HEIGHT}
     * (:313 below), and a block only interpolates between lattice rows at or below its own cell, so
     * everything from MAX_HEIGHT up is air whatever the world's build height is. Public because a
     * caller scanning "the whole column" otherwise pays for 128 rows of guaranteed air.
     */
    public static final int TOP_SOLID_Y = MAX_HEIGHT - 1;
    /** TerrainGenerator.java:45-46 - the End's noise cell width and height. */
    private static final int SCALE_XZ = 8;
    private static final int SCALE_Y = 4;
    /** TerrainGenerator.java:165-166. */
    private static final float FADE_OUT_DIST = 27.0f;
    private static final float FADE_OUT_START = MAX_HEIGHT - (FADE_OUT_DIST + 1);
    /**
     * IslandLayer.java:93-98 - islands whose centre falls inside this radius of (0,0) are
     * dropped, which is the real spawn void. Confirmed exactly: WoverEndConfig.java:104
     * (MINECRAFT_18, the HEX + PAULEVS preset BetterEnd runs) takes MINECRAFT_17's value at
     * WoverEndConfig.java:86, {@code VANILLA.innerVoidRadiusSquared * 16 * 16}, and VANILLA's is
     * 4096 at WoverEndConfig.java:69. So {@code 4096 * 16 * 16} = 1,048,576 = radius 1024 blocks.
     */
    public static final long INNER_VOID_RADIUS_SQUARED = 4096L * 16L * 16L;
    /** WoverBiomeDataImpl.java:25 / WoverBiomeBuilder.java:243 - the terrainHeight codec default. */
    private static final float DEFAULT_TERRAIN_HEIGHT = 0.1f;
    /**
     * The radial intensity 23 of BetterEnd's 27 biomes produce, and the one the biome-INDEPENDENT
     * land test uses. TerrainGenerator.java:188 is {@code getAverageDepth(x << 1, z << 1) * 0.5F}
     * and {@link #COEF} sums to 1, so a uniform {@link #DEFAULT_TERRAIN_HEIGHT} source yields
     * exactly this. Public because {@code BiomePlacement.isLand} is the caller.
     * <p>
     * ponytail: the mod's own {@code isLand} reads {@code IslandLayer.getDensity(x, y, z)}
     * (IslandLayer.java:138-140), the overload that sets nothing, so it inherits whatever intensity
     * the last terrain column left on the shared static layer. 0.05 is the codec-default value that
     * almost always is, and the only choice that is deterministic. The alternative reading is 0.2
     * (SDFRadialNoiseMap's constructor default, IslandLayer.java:46-49), which only the very first
     * call after initNoise ever sees.
     */
    public static final float LAND_INTENSITY = DEFAULT_TERRAIN_HEIGHT * 0.5f;
    private static final float RADIAL_COS = (float) Math.cos(0.5);
    private static final float RADIAL_SIN = (float) Math.sin(0.5);

    /**
     * MathHelper.java:72-76 {@code getSeed(seed, x, y)}, the position hash WorldWeaver seeds a
     * per-cell RNG with. Int arithmetic with sign-propagating shifts, reproduced literally.
     * <p>
     * Public and living here because it is the ONE copy: {@link Layer#islandSeed} is
     * IslandLayer.java:53-57, which is character-for-character this same hash, and the hex biome
     * map needs it for HexBiomeMap.java:84. A second copy would be a second thing to get wrong.
     */
    public static int mixSeed(int seed, int x, int z) {
        int h = seed + x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    /** Deepest dust cap {@link #dustDepth} can return; bounds the scan in {@link #isEndStone}. */
    private static final int MAX_DUST = 5;

    /**
     * Lattice rows the cached planes cover: {@code py} from -64 (the lowest world bottom Bukkit
     * can hand us) to {@link #MAX_HEIGHT} + one cell. Everything at or above MAX_HEIGHT is a hard
     * -1, so the top two rows are both -1 and {@link #densityColumn} can clamp any higher y onto
     * them and still interpolate the right answer.
     */
    private static final int PLANE_MIN_CELL_Y = -16;
    private static final int PLANE_ROWS = MAX_HEIGHT / SCALE_Y - PLANE_MIN_CELL_Y + 2;

    /**
     * TerrainGenerator.java:284-303, reproduced literally: the radius-3 disc of QUART offsets and
     * their normalised weights. {@code dist = MHelper.length(x, z) / 3F}, kept where
     * {@code dist <= 1} - 29 points - then {@code COEF[i] = dist / sum}, so COEF sums to 1 and a
     * uniform source of value v returns exactly v.
     * <p>
     * The CENTRE point's weight is its own distance, i.e. ZERO. It does not contribute to the mean
     * at all; it reaches the answer only through the early-out in {@link #columnHeight}.
     */
    private static final int[] OFFS_X;
    private static final int[] OFFS_Z;
    private static final float[] COEF;

    static {
        float sum = 0;
        List<int[]> pos = new ArrayList<>();
        List<Float> coef = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                // MHelper.java:174-175 - length is (float) Math.sqrt(x*x + z*z).
                float dist = (float) Math.sqrt(x * x + z * z) / 3F;
                if (dist <= 1) {
                    sum += dist;
                    coef.add(dist);
                    pos.add(new int[]{x, z});
                }
            }
        }
        OFFS_X = new int[pos.size()];
        OFFS_Z = new int[pos.size()];
        COEF = new float[coef.size()];
        for (int i = 0; i < COEF.length; i++) {
            OFFS_X[i] = pos.get(i)[0];
            OFFS_Z[i] = pos.get(i)[1];
            COEF[i] = coef.get(i) / sum;
        }
    }

    /** {@link #column}'s marker for "ask every lattice column for its own biome height". */
    private static final float PER_COLUMN = Float.NaN;

    /**
     * Memo of the density lattice, keyed on the lattice column. {@link #density} is a pure function
     * of (cellX, cellZ, py), so caching it cannot change what generates or make anything depend on
     * generation order - it only stops all 256 columns of a chunk re-evaluating the same nine
     * lattice columns, each node of which costs ~30 simplex evaluations.
     * <p>
     * The key carries the column's radial intensity too, because that is now a per-column biome
     * value ({@link #columnHeight}). Where a column takes the common {@link #LAND_INTENSITY} the
     * terrain path and the land path share one entry, so the common case costs one computation.
     * <p>
     * ponytail: cleared wholesale past a cap rather than LRU-evicted, and a lost race just
     * recomputes an identical array. Swap in a real eviction policy only if a profile asks for it.
     */
    private final ConcurrentHashMap<PlaneKey, double[]> planes = new ConcurrentHashMap<>();

    /** {@link #planes}' key. The intensity goes in as raw bits, so NaN and -0.0 behave. */
    private record PlaneKey(int cellX, int cellZ, int intensityBits) {
    }

    /**
     * Memo of {@link #columnHeight}, keyed on the lattice column. NOT an optimisation that can be
     * dropped: {@code columnHeight} runs BEFORE {@link #planes} is consulted (it produces that
     * map's key), so without this every one of a chunk's 256 block columns pays 30 biome lookups
     * per lattice corner -- 30,720 per chunk against the 9 the lattice actually has. Measured at
     * 11-12 ms per chunk of density outside the inner-void ring, against 0.5 ms with this memo.
     * <p>
     * DELIBERATELY get/put and NOT {@code computeIfAbsent}, for the same reason as {@link #planes}:
     * the mapping function calls out to the biome placement, which calls {@code isLand}, which
     * re-enters {@link #densityColumn} and hence {@link #planes}, all on the one thread inside the
     * one call. That re-entry stops short of this map only because {@code isLand} takes the
     * intensity-forced path -- which is exactly the invariant a later refactor would break, and
     * {@code computeIfAbsent} would then throw or livelock instead of just recomputing.
     */
    private final ConcurrentHashMap<Long, Float> columnHeights = new ConcurrentHashMap<>();

    private final Layer big;
    private final Layer medium;
    private final Layer small;
    private final OpenSimplexNoise noise1;
    private final OpenSimplexNoise noise2;
    private final OpenSimplexNoise surfaceNoise;
    private final OpenSimplexNoise altNoise;
    /**
     * IslandLayer.java:89 - {@code GeneratorOptions.hasCentralIsland()}, the GLOBAL flag. It gates
     * two different things at once and they must not be confused with the per-layer flag below:
     * the inner-void cull, for EVERY layer, and (together with the per-layer flag) the pinned
     * island insert. {@code TerrainGenerator.config.centerBiomesSize} also narrows the cull to
     * cells near the origin in the mod (IslandLayer.java:89 gates on the CELL index against the
     * block-valued 256), so the cull is unconditional in x/z here - and that is provably
     * equivalent, not merely close: the window is +-15,360 blocks on the small layer and +-76,800
     * on the big one, and the gated block only culls islands within 1024 blocks of the origin and
     * inserts the pinned island. Both are vacuous outside the window.
     * <p>
     * Equivalence BY VACUITY, so it has a floor: a config shrinking centerBiomesSize below about
     * 18 cells would pull the window inside the inner-void radius and break it.
     */
    private final boolean centralIsland;
    /**
     * Where the per-column radial intensity comes from. Final and read-only from here; the one
     * implementation that is not a constant reads a reference published before this object is -
     * see {@code DustWastelandsGenerator.bind}.
     */
    private final TerrainHeights heights;

    /** Defaults, for callers that have no config of their own (and for the unit tests). */
    public IslandField(long seed) {
        this(seed, BetterEndConfig.DEFAULTS);
    }

    /** Every biome at the terrainHeight codec default - see {@link TerrainHeights#DEFAULT}. */
    public IslandField(long seed, BetterEndConfig config) {
        this(seed, config, TerrainHeights.DEFAULT);
    }

    /** TerrainGenerator.java:122-127 - LegacyRandomSource is the java.util.Random algorithm. */
    public IslandField(long seed, BetterEndConfig config, TerrainHeights heights) {
        this.heights = heights;
        Random random = new Random(seed);
        // GeneratorConfig.java:78,84,90, now owned by config.yml. IslandLayer.java:99-101 - only the
        // medium layer carries the pinned central island, so THAT flag stays per-layer and hardcoded.
        big = new Layer(random.nextInt(), config.bigIslands(), false);
        medium = new Layer(random.nextInt(), config.mediumIslands(), true);
        small = new Layer(random.nextInt(), config.smallIslands(), false);
        centralIsland = config.generateCentralIsland();
        noise1 = new OpenSimplexNoise(random.nextInt());
        noise2 = new OpenSimplexNoise(random.nextInt());
        // ponytail: vanilla's coherent surface-depth noise is NOT FOUND in the reference tree
        // (no decompiled SurfaceSystem), so the dust cap rides its own field off the same seed.
        surfaceNoise = new OpenSimplexNoise(random.nextInt());
        // Drawn LAST, deliberately: appending a nextInt() shifts no earlier draw, so every seed
        // generates byte-identical terrain to the build before the speckle existed.
        altNoise = new OpenSimplexNoise(random.nextInt());
    }

    /**
     * Where a biome's alt surface block replaces its top block, one block deep on the segment floor.
     * {@code wover:threshold_condition} with scale_x/scale_z 0.1 and threshold 0.0, the shape the
     * mod's own floor speckle takes in surface_rules/amber_land.json (and in 15 other rule files;
     * in every one of them both branches happen to name the same block, so the condition is inert
     * there).
     * <p>
     * ponytail: the rule's {@code roughness} uniform(-0.4, 0.4) jitter is dropped -- the same field
     * with a crisper edge. Add the jitter only if the speckle reads too geometric in game.
     * <p>
     * ponytail: sulphur_springs is the ONLY shipped biome whose alt actually differs from its top,
     * and its rule is not this one -- it is a {@code wover:switch_rule} over
     * {@code betterend:sulphuric_surf} alternating sulphuric_rock and brimstone
     * (surface_rules/sulphur_springs.json), whose noise is not in this checkout. A 50/50 coherent
     * field at the same scale stands in for it. neon_oasis' alt is a {@code betterend:split_noise}
     * switch, likewise unported and moot while end_moss is missing from the pack.
     */
    public boolean altSurface(int x, int z) {
        return altNoise.eval(x * 0.1, z * 0.1) > 0;
    }

    public boolean isSolid(int x, int y, int z) {
        return densityColumn(x, z, y, y)[0] > 0;
    }

    /**
     * Highest solid y in the column, or {@link Integer#MIN_VALUE} if it is void.
     * <p>
     * Prefer this to the explicit-range overload for a whole-column question. The field produces
     * terrain only in {@code [0, TOP_SOLID_Y]} -- the density fades to nothing well before either
     * end -- so widening the scan to the world's own {@code -64..319} cannot change the answer and
     * costs three times the density evaluations to prove it.
     */
    public int topY(int x, int z) {
        return topY(x, z, 0, TOP_SOLID_Y);
    }

    /** Highest solid y in {@code [minY, maxY]}, or {@link Integer#MIN_VALUE} if the column is void. */
    public int topY(int x, int z, int minY, int maxY) {
        double[] column = densityColumn(x, z, minY, maxY);
        for (int i = column.length - 1; i >= 0; i--) {
            if (column[i] > 0) return minY + i;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Thickness of the {@code betterend:endstone_dust} cap on a solid segment {@code segmentHeight}
     * blocks tall. The mod's surface rules put dust over {@code 2 + surfaceDepth} blocks plus the top
     * block (surface_rules/dust_wastelands.json:23-38), but the ceiling rule (json:7-22) keeps the
     * underside end stone - so a segment is never dust all the way down, and neither is this. Dust
     * is a falling block; a segment capped to the floor would be a column of falling sand over air.
     * <p>
     * The READING is confirmed: dust_wastelands.json:27-30 is
     * {@code {add_surface_depth: true, offset: 2, surface_type: "floor"}}, and the ceiling rule at
     * json:11-14 confirms the underside stays end stone.
     * <p>
     * ponytail: only vanilla's {@code surfaceDepth} noise itself is still unknown (vanilla
     * SurfaceSystem, in neither BCLib nor WorldWeaver); the coherent 1..5 band below stands in for
     * that one term. Same CFR-decompile route as {@link #MAX_HEIGHT}.
     * <p>
     * Adjacent, out of scope here: only 2 of the 27 surface_rules files set
     * {@code add_surface_depth} (dust_wastelands, neon_oasis), while {@code BiomeSurface.resolve}'s
     * {@code dusty} flag keys on the top block and so also catches painted_mountains. That is a
     * BiomeSurface bug, not an IslandField one.
     */
    public int dustDepth(int x, int z, int segmentHeight) {
        double n = surfaceNoise.eval(x * 0.0625, z * 0.0625);
        int depth = 3 + (int) Math.round(n * 2.0);
        return Math.max(0, Math.min(depth, segmentHeight - 1));
    }

    /**
     * Solid, and below the deepest surface cap this column could carry. This is what feature
     * placement must ask instead of reading blocks back: a populator runs after its neighbours may
     * or may not have written into its chunk, so a block read is visit-order dependent, and
     * CraftEngine's deceive-material makes it lie anyway.
     * <p>
     * It is deliberately conservative and knows nothing about biomes, because it is pure terrain.
     * Since per-biome surfaces landed it is no longer the same predicate as "the generator wrote
     * minecraft:end_stone here" in two directions, and neither costs anything: a biome with no dust
     * band caps only its one floor block, so this excludes up to 4 blocks of filler that ARE end
     * stone; and umbra_valley's filler is umbralith, which this still calls solid-below-the-cap.
     * The ore path pairs it with {@code DustWastelandsGenerator.fillsWithEndStone} for the second
     * one, which is the half that would otherwise be visible.
     */
    public boolean isEndStone(int x, int y, int z) {
        int lo = y - (MAX_DUST + 1);
        double[] column = densityColumn(x, z, lo, y + MAX_DUST + 1);
        int here = y - lo;
        if (column[here] <= 0) return false;
        int top = Integer.MIN_VALUE;
        for (int i = here + 1; i < column.length; i++) {
            if (column[i] <= 0) {
                top = lo + i - 1;
                break;
            }
        }
        if (top == Integer.MIN_VALUE) return true; // deeper than any dust cap can reach
        int bottom = lo;
        for (int i = here; i >= 0; i--) {
            if (column[i] <= 0) {
                bottom = lo + i + 1;
                break;
            }
        }
        return y < top - dustDepth(x, z, top - bottom + 1) + 1;
    }

    /**
     * Per-block density for the column at {@code (x, z)} over {@code [minY, maxY]} inclusive;
     * {@code > 0} means solid. Indexed by {@code y - minY}.
     * <p>
     * The mod evaluates density only on an 8(x) x 4(y) x 8(z) lattice and lets vanilla's
     * NoiseInterpolator trilerp between the corners (TerrainGenerator.java:442-466). Sampling
     * per block instead would both change the silhouette and cost 128x more, so the lattice and
     * the interpolation are reproduced here.
     */
    public double[] densityColumn(int x, int z, int minY, int maxY) {
        return column(x, z, minY, maxY, PER_COLUMN);
    }

    /**
     * {@link #densityColumn} with the radial intensity FORCED, so the caller never asks what biome
     * is here. It exists for exactly one caller, {@code BiomePlacement.isLand}, and for exactly one
     * reason: the biome source asks this class where the land is, so a land test that read a biome
     * would close a cycle. The mod breaks it the same way -- {@code TerrainGenerator.isLand} calls
     * {@code IslandLayer.getDensity(x, y, z)} (IslandLayer.java:138-140), the overload that sets no
     * intensity, while terrain fill calls the 4-arg one (IslandLayer.java:142-146).
     * <p>
     * Do not "unify" this with the 4-arg form. It is the cycle break.
     */
    public double[] densityColumn(int x, int z, int minY, int maxY, float intensity) {
        return column(x, z, minY, maxY, intensity);
    }

    private double[] column(int x, int z, int minY, int maxY, float intensity) {
        boolean perColumn = Float.isNaN(intensity);
        int cellX = Math.floorDiv(x, SCALE_XZ);
        int cellZ = Math.floorDiv(z, SCALE_XZ);
        double tx = (double) (x - cellX * SCALE_XZ) / SCALE_XZ;
        double tz = (double) (z - cellZ * SCALE_XZ) / SCALE_XZ;
        double w00 = (1 - tx) * (1 - tz);
        double w10 = tx * (1 - tz);
        double w01 = (1 - tx) * tz;
        double w11 = tx * tz;

        // IslandLayer.java:143 sets the intensity once per computeColumnDensity call and the mod
        // calls that with a cell-aligned position, so per-lattice-column is exactly right: each of
        // the four trilerp corners gets its own biome height.
        double[] p00 = perColumn ? plane(cellX, cellZ) : plane(cellX, cellZ, intensity);
        double[] p10 = perColumn ? plane(cellX + 1, cellZ) : plane(cellX + 1, cellZ, intensity);
        double[] p01 = perColumn ? plane(cellX, cellZ + 1) : plane(cellX, cellZ + 1, intensity);
        double[] p11 = perColumn
                ? plane(cellX + 1, cellZ + 1) : plane(cellX + 1, cellZ + 1, intensity);

        double[] out = new double[maxY - minY + 1];
        for (int y = minY; y <= maxY; y++) {
            int cellY = Math.floorDiv(y, SCALE_Y);
            double ty = (double) (y - cellY * SCALE_Y) / SCALE_Y;
            // Clamped only for a y outside every real world's height; the lattice is air there.
            int i = Math.max(0, Math.min(PLANE_ROWS - 2, cellY - PLANE_MIN_CELL_Y));
            double a = w00 * p00[i] + w10 * p10[i] + w01 * p01[i] + w11 * p11[i];
            double b = w00 * p00[i + 1] + w10 * p10[i + 1] + w01 * p01[i + 1] + w11 * p11[i + 1];
            out[y - minY] = a + (b - a) * ty;
        }
        return out;
    }

    /**
     * True if any block in the inclusive block box {@code [x0..x1] x [z0..z1]} can be solid over
     * {@code [minY, maxY]}. EXACT, not a heuristic: {@link #densityColumn} interpolates in Y first
     * and mixes the four lattice corners with non-negative weights that sum to 1, so an interior
     * column's value at any Y is the convex combination of the four corner COLUMNS' values at that
     * same Y. All four corners empty over the band therefore means the whole cell is empty.
     * <p>
     * Costs {@code (span/8 + 2)^2} columns instead of {@code span^2} - the cheap gate that keeps a
     * whole-area scan off the ~2500 chunks of the vanilla core that hold no terrain at all.
     */
    public boolean anySolid(int x0, int z0, int x1, int z1, int minY, int maxY) {
        for (int x = Math.floorDiv(x0, SCALE_XZ) * SCALE_XZ; x <= x1 + SCALE_XZ; x += SCALE_XZ) {
            for (int z = Math.floorDiv(z0, SCALE_XZ) * SCALE_XZ; z <= z1 + SCALE_XZ; z += SCALE_XZ) {
                for (double d : densityColumn(x, z, minY, maxY)) {
                    if (d > 0) return true;
                }
            }
        }
        return false;
    }

    /** The density lattice for one column of cells, at that column's own biome-driven intensity. */
    private double[] plane(int cellX, int cellZ) {
        return plane(cellX, cellZ, columnHeight(cellX, cellZ));
    }

    /**
     * The density lattice for one column of cells, computed once per (cellX, cellZ, intensity).
     * <p>
     * DELIBERATELY get/put and NOT {@code computeIfAbsent}: {@link #columnHeight} above calls out
     * to the biome placement, which calls {@code isLand}, which calls {@link #densityColumn}, which
     * re-enters this map on the same thread inside one call.
     * {@code ConcurrentHashMap.computeIfAbsent} throws or livelocks on a recursive update; get/put
     * just recomputes an identical array in the rare losing race.
     */
    private double[] plane(int cellX, int cellZ, float intensity) {
        PlaneKey key = new PlaneKey(cellX, cellZ, Float.floatToIntBits(intensity));
        double[] cached = planes.get(key);
        if (cached != null) return cached;
        if (planes.size() > 1 << 16) planes.clear();
        Cell cell = cell(cellX, cellZ);
        double[] plane = new double[PLANE_ROWS];
        for (int i = 0; i < PLANE_ROWS; i++) {
            plane[i] = density(cell, (double) (PLANE_MIN_CELL_Y + i) * SCALE_Y, intensity);
        }
        planes.put(key, plane);
        return plane;
    }

    /**
     * The radial intensity for one lattice column: TerrainGenerator.java:188,
     * {@code getAverageDepth(x << 1, z << 1) * 0.5F}, over TerrainGenerator.java:247-262. The mod's
     * {@code x} there is the noise CELL index, so the quart it samples is that index doubled.
     * <p>
     * Package-private for the unit tests, which pin the two things that are easy to get wrong.
     */
    float columnHeight(int cellX, int cellZ) {
        long key = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        Float cached = columnHeights.get(key);
        if (cached != null) return cached;
        if (columnHeights.size() > 1 << 16) columnHeights.clear();
        float height = computeColumnHeight(cellX, cellZ);
        columnHeights.put(key, height);
        return height;
    }

    private float computeColumnHeight(int cellX, int cellZ) {
        int qx = cellX << 1;
        int qz = cellZ << 1;
        // TerrainGenerator.java:251-254 - the early-out tests the CENTRE sample ONLY, and the test
        // is strictly less-than. 0.1f < 0.1F is FALSE, so the codec default flattens nothing; only
        // a biome that actively declares 0.0 (megalake, megalake_grove, sulphur_springs) trips it,
        // and those become genuinely flat islands, as they are in the mod.
        if (heights.at(qx, qz) < DEFAULT_TERRAIN_HEIGHT) return 0f;
        float depth = 0F;
        for (int i = 0; i < COEF.length; i++) {
            depth += heights.at(qx + OFFS_X[i], qz + OFFS_Z[i]) * COEF[i];
        }
        return depth * 0.5f;
    }

    // --- lattice cell -------------------------------------------------------------------------

    /** One noise-lattice column: the distorted sample position and the islands that reach it. */
    private record Cell(double px, double pz, Island[] big, Island[] medium, Island[] small) {
    }

    /** TerrainGenerator.java:171-182 - note the noise argument is the CELL INDEX, not the block. */
    private Cell cell(int cellX, int cellZ) {
        double distortion1 = noise1.eval(cellX * 0.1, cellZ * 0.1) * 20
                + noise2.eval(cellX * 0.2, cellZ * 0.2) * 10
                + noise1.eval(cellX * 0.4, cellZ * 0.4) * 5;
        double distortion2 = noise2.eval(cellX * 0.1, cellZ * 0.1) * 20
                + noise1.eval(cellX * 0.2, cellZ * 0.2) * 10
                + noise2.eval(cellX * 0.4, cellZ * 0.4) * 5;
        double px = (double) cellX * SCALE_XZ + distortion1;
        double pz = (double) cellZ * SCALE_XZ + distortion2;
        return new Cell(px, pz, big.islands(px, pz, centralIsland),
                medium.islands(px, pz, centralIsland), small.islands(px, pz, centralIsland));
    }

    /** TerrainGenerator.java:191-204 - the three layers, the inflating octaves, the top fade-out. */
    private double density(Cell cell, double py, float intensity) {
        // TerrainGenerator.java:201 overwrites the density with -1 here after computing it; the
        // result is the same and this is half the lattice.
        if (py >= MAX_HEIGHT) return -1;
        float dist = layerDensity(cell.big(), big, cell.px(), py, cell.pz(), intensity);
        if (dist <= 1) {
            dist = Math.max(dist, layerDensity(cell.medium(), medium, cell.px(), py, cell.pz(), intensity));
        }
        if (dist <= 1) {
            dist = Math.max(dist, layerDensity(cell.small(), small, cell.px(), py, cell.pz(), intensity));
        }

        if (dist > -0.5F) {
            // Each term is amp*noise + amp, so the sum lies in [0, 0.07]: these INFLATE the
            // surface, they can never carve it (TerrainGenerator.java:195-199).
            dist += (float) (noise1.eval(cell.px() * 0.01, py * 0.01, cell.pz() * 0.01) * 0.02 + 0.02);
            dist += (float) (noise2.eval(cell.px() * 0.05, py * 0.05, cell.pz() * 0.05) * 0.01 + 0.01);
            dist += (float) (noise1.eval(cell.px() * 0.1, py * 0.1, cell.pz() * 0.1) * 0.005 + 0.005);
        }

        if (py > FADE_OUT_START) {
            double t = (py - FADE_OUT_START) / FADE_OUT_DIST;
            return dist + t * (-1 - dist);
        }
        return dist;
    }

    /** IslandLayer.java:128-140 - density is the negated minimum signed distance over the layer. */
    private static float layerDensity(Island[] islands, Layer layer, double x, double y, double z,
                                      float intensity) {
        float distance = 10;
        for (Island island : islands) {
            float lx = (float) (x - island.x) / layer.scale;
            float ly = (float) (y - island.y) / layer.scale;
            float lz = (float) (z - island.z) / layer.scale;
            distance = Math.min(distance, islandSdf(lx, ly, lz, island, intensity));
        }
        return -distance;
    }

    // --- island SDF ---------------------------------------------------------------------------

    /**
     * The mod's island: four capped cones smooth-unioned into a lens, with the top cap displaced
     * by a radial simplex patch (IslandLayer.java:39-51). {@code SDFScale} divides the query by
     * the per-island scale and multiplies the distance back out (SDFScale.java:12-15).
     * <p>
     * Confirmed exact: SDFCappedCone.java:29-46, SDFSmoothUnion.java:15-20, and the four cones'
     * parameters against IslandLayer.java:39-42,154-158.
     */
    private static float islandSdf(float x, float y, float z, Island island, float intensity) {
        float s = island.scale;
        float sx = x / s;
        float sy = y / s;
        float sz = z / s;
        float qx = (float) Math.sqrt(sx * sx + sz * sz);
        // IslandLayer.java:39-42,154-158 - makeCone(rBottom, rTop, height, minY), hh = height/2,
        // translated to (0, minY + hh, 0), so the query y is shifted by -(minY + hh).
        float cone1 = cappedCone(qx, sy + 0.2f, 0f, 0.4f, 0.1f);
        float cone2 = cappedCone(qx, sy + 0.05f, 0.4f, 0.5f, 0.05f);
        float cone3 = cappedCone(qx, sy - 0.015f, 0.5f, 0.45f, 0.015f);
        float cone4 = cappedCone(qx, sy - 0.04f, 0.45f, 0f, 0.01f);
        float coneBottom = smoothUnion(cone1, cone2, 0.02f);
        float coneTop = smoothUnion(cone3, cone4, 0.02f) + radial(sx, sz, island, intensity);
        return smoothUnion(coneTop, coneBottom, 0.01f) * s;
    }

    /** bclib SDFCappedCone (iq's capped cone), evaluated in cylindrical (qx, y). */
    private static float cappedCone(float qx, float y, float radius1, float radius2, float height) {
        float k2x = radius2 - radius1;
        float k2y = 2 * height;
        float cax = qx - Math.min(qx, (y < 0f) ? radius1 : radius2);
        float cay = Math.abs(y) - height;
        float mlt = clamp(((radius2 - qx) * k2x + (height - y) * k2y) / (k2x * k2x + k2y * k2y), 0f, 1f);
        float cbx = qx - radius2 + k2x * mlt;
        float cby = y - height + k2y * mlt;
        float sign = (cbx < 0f && cay < 0f) ? -1f : 1f;
        return sign * (float) Math.sqrt(Math.min(cax * cax + cay * cay, cbx * cbx + cby * cby));
    }

    /** bclib SDFSmoothUnion - polynomial smin, {@code a} = sourceA, {@code b} = sourceB. */
    private static float smoothUnion(float a, float b, float k) {
        float h = clamp(0.5f + 0.5f * (b - a) / k, 0f, 1f);
        return (b + h * (a - b)) - k * h * (1f - h);
    }

    /**
     * SDFRadialNoiseMap.java:19-34, term for term: a simplex patch confined to the inner
     * {@code 0.5 / (1 + intensity)} of the island and faded linearly to zero at that rim,
     * displacing the top cap by up to {@code +-1.7 * intensity * scale} island units. Confirmed
     * against bclib -- the falloff, the 0.5-radian rotation, the {@code * 0.75} frequency and
     * {@link Layer#radialNoise} (SDFRadialNoiseMap.java:37-42) are all exact, and
     * SDFDisplacement.java:20 confirms the displacement is ADDED to the source.
     * <p>
     * There is no central-island special case, and there was never meant to be one. The premise for
     * the old {@code if (island.central) return 0f} was that {@code minecraft:the_end} has no
     * WoverBiomeData. It has one (VanillaBiomeDataProvider.java:44-47) and its json omits
     * terrainHeight, so it takes the 0.1f codec default, {@code 0.1f < 0.1F} is false, and the
     * mod's early-out never fires there: the central island gets the same 0.05 as everywhere else.
     * What DOES fall out of the general rule is the flattening the port never had -- the three
     * biomes that declare terrainHeight 0.0 return 0 from {@link #columnHeight} and land on the
     * hard zero below. The mod's rule is per-COLUMN; the port's old one was per-ISLAND.
     */
    private static float radial(float x, float z, Island island, float intensity) {
        // SDFRadialNoiseMap.java:20-22 - a hard zero, before the radius is even divided by.
        if (intensity == 0f) return 0f;
        // IslandLayer.java:144 - setRadius(0.5F / (1 + height)), derived from the same height.
        float radius = 0.5f / (1f + intensity);
        float px = x / radius;
        float pz = z / radius;
        float d2 = px * px + pz * pz;
        if (d2 > 1) return 0f;
        float t = 1f - (float) Math.sqrt(d2);
        float nx = px * RADIAL_COS - pz * RADIAL_SIN;
        float nz = pz * RADIAL_COS + px * RADIAL_SIN;
        return t * island.layer.radialNoise(nx * 0.75f + island.offsetX, nz * 0.75f + island.offsetZ)
                * intensity;
    }

    // --- layers and islands -------------------------------------------------------------------

    private static final class Layer {
        final int seed;
        final float distance;
        final float scale;
        final float minY;
        final float maxY;
        final long centerDist;
        /** IslandLayer.java:99-101 - the PER-LAYER flag: does this layer carry the pinned island. */
        final boolean hasCentralIsland;
        /** LayerOptions.java:64-66, via {@link LayerSettings#coverage()} - the density threshold. */
        final float coverage;
        private final OpenSimplexNoise density;
        private final OpenSimplexNoise noise;

        Layer(int seed, LayerSettings settings, boolean hasCentralIsland) {
            this.seed = seed;
            this.distance = settings.distance();
            this.scale = settings.scale();
            this.minY = settings.minY();
            this.maxY = settings.maxY();
            this.centerDist = settings.centerDist();
            this.coverage = settings.coverage();
            this.hasCentralIsland = hasCentralIsland;
            this.density = new OpenSimplexNoise(seed);
            this.noise = new OpenSimplexNoise(seed);
        }

        /**
         * SDFRadialNoiseMap.java:37-42, exactly - including the two {@code +1000} x offsets.
         * <p>
         * Two measured, deliberate precision divergences, both sub-block, both left alone. bclib
         * takes {@code double} arguments and forms {@code nx * 0.75 + offsetX} in double, while
         * {@link #radial} forms it in float; at the worst offset the mask allows (32767) that
         * quantises the noise input to about 1/380th of the patch, i.e. 0.03 blocks of
         * displacement at the codec-default intensity. And bclib casts each of the three octaves
         * to float before summing where this sums in double, worth about 1e-6 blocks. Widening
         * both would be strictly closer to upstream and strictly invisible.
         */
        float radialNoise(float x, float z) {
            return (float) (noise.eval(x, z)
                    + noise.eval(x * 3 + 1000, z * 3) * 0.5
                    + noise.eval(x * 9 + 1000, z * 9) * 0.2);
        }

        /**
         * IslandLayer.java:59-104 - the 3x3 cell neighbourhood around a distorted sample point.
         *
         * @param centralIsland the GLOBAL {@code GeneratorOptions.hasCentralIsland()}, NOT this
         *                      layer's {@link #hasCentralIsland}.
         */
        Island[] islands(double x, double z, boolean centralIsland) {
            int ix = floor(x / distance);
            int iz = floor(z / distance);
            List<Island> out = new ArrayList<>(10);
            for (int pox = -1; pox < 2; pox++) {
                int px = pox + ix;
                for (int poz = -1; poz < 2; poz++) {
                    int pz = poz + iz;
                    // IslandLayer.java:71. Yes, a cell COUNT is compared against a squared cell
                    // distance - that is what the mod does, so it is what happens here.
                    if ((long) px * (long) px + (long) pz * (long) pz <= centerDist) continue;
                    Random random = new Random(islandSeed(px, pz));
                    // Draw order is X, Y, Z, and the coverage test comes AFTER all three, at the
                    // island's jittered world position (IslandLayer.java:76-80).
                    double posX = (px + random.nextFloat()) * distance;
                    // MHelper.java:88-90 randRange, scaled by maxHeight at IslandLayer.java:78.
                    double posY = (minY + random.nextFloat() * (maxY - minY)) * MAX_HEIGHT;
                    double posZ = (pz + random.nextFloat()) * distance;
                    if (density.eval(posX * 0.01, posZ * 0.01) <= coverage) continue;
                    int cx = (int) posX;
                    int cy = (int) posY;
                    int cz = (int) posZ;
                    // IslandLayer.java:89-98 - the real spawn void, in world coordinates. The
                    // cull sits inside the GLOBAL flag's if, so it culls EVERY layer. Gating it on
                    // the per-layer flag instead would leave big and small islands in the void.
                    if (centralIsland && (long) cx * cx + (long) cz * cz < INNER_VOID_RADIUS_SQUARED) {
                        continue;
                    }
                    // IslandLayer.java:112-113 - a fresh RNG seeded on the island's WORLD
                    // position (not the cell indices line 76 used), so the scale is an
                    // independent draw. One draw, all three axes.
                    float scale = new Random(islandSeed(cx, cz)).nextFloat() + 0.5f;
                    out.add(new Island(this, cx, cy, cz, scale));
                }
            }
            // IslandLayer.java:99-102 - the insert needs BOTH flags: the global one (it is nested
            // inside that same if) and the per-layer one, which only the medium layer sets.
            // IslandLayer.java:101,110 - (0, 64, 0) at fixed scale 1.3. It carries no flag of its
            // own any more: the radial map is per-COLUMN now, so the pinned island is shaped by
            // whatever terrainHeight its columns report, exactly like every other island.
            if (centralIsland && hasCentralIsland) out.add(new Island(this, 0, 64, 0, 1.3f));
            return out.toArray(new Island[0]);
        }

        /** IslandLayer.java:53-57 - the same hash as {@link IslandField#mixSeed}. */
        int islandSeed(int x, int z) {
            return mixSeed(seed, x, z);
        }

        /**
         * MHelper.java:114-116, character for character - asymmetric about zero, so
         * {@code floor(-3.0) == -4}. Bug-compatible on purpose.
         */
        private static int floor(double x) {
            return x < 0 ? (int) (x - 1) : (int) x;
        }
    }

    private static final class Island {
        final Layer layer;
        final int x;
        final int y;
        final int z;
        final float scale;
        /**
         * SDFRadialNoiseMap.java:59-63, set at IslandLayer.java:117. Both halves of the old open
         * question turn out to be true and compatible: IslandLayer does pass the raw int, and the
         * {@code (short) (x & 32767)} mask lives inside {@code setOffset}. Byte-exact.
         */
        final float offsetX;
        final float offsetZ;

        Island(Layer layer, int x, int y, int z, float scale) {
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.z = z;
            this.scale = scale;
            this.offsetX = (short) (x & 32767);
            this.offsetZ = (short) (z & 32767);
        }
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
