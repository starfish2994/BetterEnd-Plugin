package org.betterx.betterend.bukkit.config;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Everything a server owner can change, read ONCE at plugin load into an immutable snapshot.
 * <p>
 * Nothing here is ever consulted again during generation. {@code DustWastelandsGenerator} memoises
 * one {@code IslandField} per world seed for the life of the world (DustWastelandsGenerator.java:38,56)
 * and {@code IslandField} memoises its density lattice on top of that (IslandField.java:74), so a
 * value re-read mid-world would generate half a world against one field and half against another
 * while the cache kept serving the old one. That is why there is no reload command, and why this is
 * a record handed to the generator's constructor rather than a live view of the config file.
 * <p>
 * Plain Java on purpose -- no Bukkit imports, so it unit-tests without a server. The plugin wires
 * it up with method references:
 * <pre>{@code
 * saveDefaultConfig();
 * BetterEndConfig config = BetterEndConfig.load(getConfig()::get, getLogger()::warning);
 * }</pre>
 * A malformed value never stops the plugin: it is reported by key, value, accepted range and the
 * default it fell back to, and loading continues.
 *
 * @param installBundledPack       reinstall the bundled CraftEngine pack over the owner's copy.
 * @param generateCentralIsland    place the pinned spawn island at (0, 64, 0). TERRAIN.
 * @param bigIslands               TERRAIN.
 * @param mediumIslands            TERRAIN. The layer that carries the central island.
 * @param smallIslands             TERRAIN.
 * @param flavoliteLayersPerChunk  BetterEndPopulator.java:34 -- placed_feature count 6.
 * @param thallasiumVeinsPerChunk  BetterEndPopulator.java:70 -- placed_feature count 24.
 * @param enderVeinsPerChunk       BetterEndPopulator.java:71 -- placed_feature count 12.
 * @param violeciteLayersPerChunk  violecite_layer.json count 8, chorus_forest + shadow_forest only.
 * @param amberVeinsPerChunk       amber_ore.json count 60, amber_land only.
 * @param dragonBoneVeinsPerChunk  dragon_bone_ore.json count 24, dragon_graveyards only.
 * @param advancements             build the advancement tab, when UltimateAdvancementAPI is present.
 * @param advancementSampleTicks   how often each online player is probed for biome and inventory.
 */
public record BetterEndConfig(
        boolean installBundledPack,
        boolean generateCentralIsland,
        LayerSettings bigIslands,
        LayerSettings mediumIslands,
        LayerSettings smallIslands,
        int flavoliteLayersPerChunk,
        int thallasiumVeinsPerChunk,
        int enderVeinsPerChunk,
        int violeciteLayersPerChunk,
        int amberVeinsPerChunk,
        int dragonBoneVeinsPerChunk,
        boolean advancements,
        int advancementSampleTicks
) {
    /** Sanity ceiling: a flavolite layer is an unbounded flood fill, the priciest work per chunk. */
    public static final int MAX_FLAVOLITE_LAYERS = 64;
    /** Sanity ceiling on the two ore blob counts. 0 disables the ore. */
    public static final int MAX_VEINS = 256;
    /** Once a tick is as often as anyone could want; a minute is as rare as is still useful. */
    public static final int MIN_ADVANCEMENT_TICKS = 1;
    public static final int MAX_ADVANCEMENT_TICKS = 1200;

    /**
     * The defaults, and the only place they live. A key missing from the owner's file falls back to
     * the value here, which is Terra's whole answer to "config written by an older version"
     * (PluginConfigImpl.java:44-82) -- no migration framework, no rewriting the owner's file.
     * <p>
     * The three layers are GeneratorConfig.java:78,84,90 byte for byte; IslandField's constructor
     * builds its three Layers from them and hardcodes nothing.
     */
    public static final BetterEndConfig DEFAULTS = new BetterEndConfig(
            true,
            true,
            new LayerSettings(300f, 200f, 70f / 128, 10f / 128, 0.5f),
            new LayerSettings(150f, 100f, 70f / 128, 20f / 128, 0.5f),
            new LayerSettings(60f, 50f, 70f / 128, 30f / 128, 0.5f),
            6,
            24,
            12,
            8,
            60,
            24,
            true,
            20
    );

    /**
     * Reads the whole surface once.
     *
     * @param values a lookup by dotted path returning {@code null} for an absent key --
     *               {@code FileConfiguration::get}. Never called after this method returns.
     * @param warn   receives one human-readable line per problem -- {@code Logger::warning}.
     * @return an immutable snapshot; {@link #DEFAULTS} if the whole file was unreadable.
     */
    public static BetterEndConfig load(Function<String, Object> values, Consumer<String> warn) {
        try {
            return new BetterEndConfig(
                    bool(values, warn, "craftengine.install-bundled-pack", DEFAULTS.installBundledPack),
                    bool(values, warn, "generator.generate-central-island", DEFAULTS.generateCentralIsland),
                    layer(values, warn, "generator.layers.big", DEFAULTS.bigIslands),
                    layer(values, warn, "generator.layers.medium", DEFAULTS.mediumIslands),
                    layer(values, warn, "generator.layers.small", DEFAULTS.smallIslands),
                    integer(values, warn, "features.flavolite-layers-per-chunk",
                            DEFAULTS.flavoliteLayersPerChunk, 0, MAX_FLAVOLITE_LAYERS),
                    integer(values, warn, "features.thallasium-veins-per-chunk",
                            DEFAULTS.thallasiumVeinsPerChunk, 0, MAX_VEINS),
                    integer(values, warn, "features.ender-veins-per-chunk",
                            DEFAULTS.enderVeinsPerChunk, 0, MAX_VEINS),
                    integer(values, warn, "features.violecite-layers-per-chunk",
                            DEFAULTS.violeciteLayersPerChunk, 0, MAX_FLAVOLITE_LAYERS),
                    integer(values, warn, "features.amber-veins-per-chunk",
                            DEFAULTS.amberVeinsPerChunk, 0, MAX_VEINS),
                    integer(values, warn, "features.dragon-bone-veins-per-chunk",
                            DEFAULTS.dragonBoneVeinsPerChunk, 0, MAX_VEINS),
                    bool(values, warn, "advancements.enabled", DEFAULTS.advancements),
                    integer(values, warn, "advancements.sample-interval-ticks",
                            DEFAULTS.advancementSampleTicks,
                            MIN_ADVANCEMENT_TICKS, MAX_ADVANCEMENT_TICKS)
            );
        } catch (RuntimeException error) {
            // A broken config must never keep the server from starting. The defaults are valid.
            warn.accept("config.yml could not be read (" + error
                    + "); using the built-in defaults for everything.");
            return DEFAULTS;
        }
    }

    private static LayerSettings layer(Function<String, Object> values, Consumer<String> warn,
                                       String path, LayerSettings fallback) {
        return new LayerSettings(
                number(values, warn, path + ".distance", fallback.distance(),
                        LayerSettings.MIN_DISTANCE, LayerSettings.MAX_DISTANCE),
                number(values, warn, path + ".scale", fallback.scale(),
                        LayerSettings.MIN_SCALE, LayerSettings.MAX_SCALE),
                number(values, warn, path + ".average-height", fallback.averageHeight(),
                        LayerSettings.MIN_UNIT, LayerSettings.MAX_UNIT),
                number(values, warn, path + ".height-variation", fallback.heightVariation(),
                        LayerSettings.MIN_UNIT, LayerSettings.MAX_UNIT),
                number(values, warn, path + ".coverage", fallback.rawCoverage(),
                        LayerSettings.MIN_UNIT, LayerSettings.MAX_UNIT)
        );
    }

    /**
     * Reads one float and clamps it to the mod's range for that key, warning in
     * KernelTemplate.java:63's style: name the key, the value found and what was accepted.
     * The clamp is the same one {@link LayerSettings}' constructor applies; doing it here too is
     * what lets the message name the key, which the constructor cannot.
     */
    private static float number(Function<String, Object> values, Consumer<String> warn,
                                String key, float fallback, float min, float max) {
        float found = number(values, warn, key, fallback);
        float clamped = Math.clamp(found, min, max);
        if (clamped != found) {
            warn(warn, key, found, min + ".." + max, clamped);
        }
        return clamped;
    }

    private static float number(Function<String, Object> values, Consumer<String> warn,
                                String key, float fallback) {
        Object raw = values.apply(key);
        if (raw == null) return fallback;
        if (!(raw instanceof Number found)) {
            warn(warn, key, raw, "a number", fallback);
            return fallback;
        }
        float value = found.floatValue();
        if (!Float.isFinite(value)) {
            warn(warn, key, raw, "a finite number", fallback);
            return fallback;
        }
        return value;
    }

    private static int integer(Function<String, Object> values, Consumer<String> warn,
                               String key, int fallback, int min, int max) {
        Object raw = values.apply(key);
        if (raw == null) return fallback;
        if (!(raw instanceof Number found)
                || !Double.isFinite(found.doubleValue())
                || found.doubleValue() != Math.rint(found.doubleValue())) {
            warn(warn, key, raw, "a whole number " + min + ".." + max, fallback);
            return fallback;
        }
        long value = found.longValue();
        int clamped = (int) Math.clamp(value, min, max);
        if (clamped != value) {
            warn(warn, key, value, min + ".." + max, clamped);
        }
        return clamped;
    }

    private static boolean bool(Function<String, Object> values, Consumer<String> warn,
                                String key, boolean fallback) {
        Object raw = values.apply(key);
        if (raw == null) return fallback;
        if (!(raw instanceof Boolean found)) {
            warn(warn, key, raw, "true or false", fallback);
            return fallback;
        }
        return found;
    }

    private static void warn(Consumer<String> warn, String key, Object found, String allowed, Object used) {
        warn.accept("config.yml: '" + key + "' was " + found + ", which is not " + allowed
                + "; using " + used + " instead.");
    }
}
