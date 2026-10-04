package org.betterx.betterend.bukkit.biome;

import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.datapack.DiscoveredDatapack;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.Objects;

/**
 * Hands the server the bundled biome datapack, from inside the plugin jar.
 * <p>
 * This is the whole delivery mechanism, and it has to be a {@link PluginBootstrap}: biomes are a
 * worldgen registry, the worldgen registries are built and frozen inside {@code WorldLoader.load}
 * during {@code Main.main}, and plugins are not loaded until several steps later. The only hook
 * that fires early enough is {@code DATAPACK_DISCOVERY}, which Paper types on
 * {@link BootstrapContext} and nothing else (LifecycleEvents.java:42), and only a
 * {@code paper-plugin.yml} can declare a bootstrapper (PluginFileType.java:28-45).
 * <p>
 * Two consequences worth knowing before debugging this: installing the plugin on a running server
 * does nothing for biomes until a full restart ({@code Datapack.setEnabled} routes to
 * {@code reloadResources}, which passes the registries through untouched), and worlds created later
 * inherit the biomes for free, because the biome registry is server-global, not per-world.
 */
public final class BiomeDatapack implements PluginBootstrap {
    /** Directory inside the jar, from {@code plugin/src/main/resources/datapack}. */
    private static final String PACK_RESOURCE = "/datapack";
    /** Paper prefixes the plugin name, so the server sees this as {@code BetterEnd/biomes}. */
    private static final String PACK_ID = "biomes";

    @Override
    public void bootstrap(BootstrapContext context) {
        // Unconditional and idempotent on purpose: this event fires on every PackRepository.reload(),
        // and a pack not re-discovered on every fire disappears from the server
        // (DatapackRegistrar.java:20-22). A one-shot guard here would delete the biomes on /reload.
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                DiscoveredDatapack pack = event.registrar().discoverPack(
                        Objects.requireNonNull(BiomeDatapack.class.getResource(PACK_RESOURCE),
                                PACK_RESOURCE + " is missing from the plugin jar").toURI(),
                        PACK_ID,
                        // Defaults to false, which would leave the biomes out of every world.
                        configurer -> configurer.autoEnableOnServerStart(true));
                if (pack == null) {
                    context.getLogger().error("The BetterEnd biome datapack was rejected; the End will"
                            + " generate with vanilla biomes only.");
                } else {
                    // Its own try/catch: getCompatibility() is a diagnostic, and on Purpur 26.2 it
                    // THROWS -- PaperDiscoveredDatapack does Compatibility.valueOf on a string the
                    // api enum has no constant for ("UNKNOWN", when the server had to fall back on
                    // unreadable pack metadata). Letting that escape aborted the whole handler and
                    // logged "Could not register" for a pack that had in fact already registered.
                    // A broken diagnostic must never be able to take out the thing it reports on.
                    try {
                        Datapack.Compatibility compatibility = pack.getCompatibility();
                        if (compatibility != Datapack.Compatibility.COMPATIBLE) {
                            context.getLogger().warn("The BetterEnd biome datapack reports {} for this"
                                    + " server version; biomes may not load.", compatibility);
                        }
                    } catch (RuntimeException ignored) {
                        // Server reported a compatibility value this API build cannot name. The pack
                        // is registered either way; nothing actionable to say.
                    }
                }
            } catch (Exception error) {
                // Nothing here may abort startup: a world with wrong biomes still beats no server.
                context.getLogger().error("Could not register the BetterEnd biome datapack.", error);
            }
        });
    }
}
