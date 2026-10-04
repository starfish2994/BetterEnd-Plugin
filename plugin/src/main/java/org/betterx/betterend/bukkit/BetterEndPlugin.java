package org.betterx.betterend.bukkit;

import org.betterx.betterend.bukkit.advancement.AdvancementWatcher;
import org.betterx.betterend.bukkit.advancement.BetterEndAdvancements;
import org.betterx.betterend.bukkit.config.BetterEndConfig;
import org.betterx.betterend.bukkit.vanilla.VanillaEndCheck;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.logging.Level;

public final class BetterEndPlugin extends JavaPlugin {
    /** The API plugin's own name, from its plugin.yml. Optional: absent is a supported state. */
    private static final String ADVANCEMENT_API = "UltimateAdvancementAPI";

    private CraftEngineBridge craftEngine;
    private NmsPlatform nms;
    private boolean packInstalled;
    private BetterEndAdvancements advancements;
    /** Read once, here, and never re-read; see {@link BetterEndConfig}. Never null after onLoad. */
    private BetterEndConfig config = BetterEndConfig.DEFAULTS;

    @Override
    public void onLoad() {
        // Before the CraftEngine check: craftengine.install-bundled-pack is read out of this file,
        // and a config the generator needs must not depend on which branch below we take.
        saveDefaultConfig();
        config = BetterEndConfig.load(getConfig()::get, getLogger()::warning);

        craftEngine = new CraftEngineBridge(this);
        if (craftEngine.plugin() == null) {
            getLogger().severe("CraftEngine is required; BetterEnd will be disabled.");
            return;
        }
        if (!config.installBundledPack()) {
            // A supported owner choice, not a failure: packInstalled must still be true or
            // onEnable() below turns "I own my fork of the pack" into a hard startup abort.
            getLogger().info("craftengine.install-bundled-pack is false; leaving"
                + " plugins/CraftEngine/resources/betterend as you have it.");
            packInstalled = true;
            return;
        }
        try {
            craftEngine.installBundledPack();
            packInstalled = true;
        } catch (IOException error) {
            getLogger().log(Level.SEVERE, "Could not install the BetterEnd CraftEngine pack.", error);
        }
    }

    @Override
    public void onEnable() {
        // Throwing is Paper's supported bail-out; disablePlugin(this) from inside onEnable
        // closes our own classloader mid-callback and still fires PluginEnableEvent.
        if (craftEngine == null || !craftEngine.available()) {
            throw new IllegalStateException("CraftEngine is not enabled");
        }
        if (!packInstalled) {
            throw new IllegalStateException("The BetterEnd CraftEngine pack was not installed");
        }

        nms = NmsLoader.load(this);
        nms.enable(this);

        // paper-plugin.yml declares load: STARTUP and DedicatedServer enables STARTUP plugins
        // before loadLevel() (DedicatedServer.java.patch:166 vs :212), so onEnable runs before any
        // world exists: no Bukkit.getWorlds() sweep is needed and no world can be missed, primary
        // End included. The listener self-selects on the generator.
        getServer().getPluginManager().registerEvents(new VanillaEndCheck(), this);

        // Advancements wait for ServerLoadEvent rather than being built here. Both this plugin and
        // UltimateAdvancementAPI declare load: STARTUP, and the API only publishes its static
        // handle at the very end of its own enable, so building now would be a race decided by
        // plugin ordering. ServerLoadEvent fires once every plugin is enabled, and STARTUP means no
        // player can have joined before it.
        if (config.advancements()) {
            getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onServerLoad(ServerLoadEvent event) {
                    enableAdvancements();
                }
            }, this);
        }
    }

    /**
     * Builds the advancement tab, if the optional API is there.
     * <p>
     * The presence check has to happen HERE, in a class that never names an UltimateAdvancementAPI
     * type. The JVM resolves a class's referenced types on first use, so touching
     * {@link BetterEndAdvancements} at all when the API jar is missing would throw
     * NoClassDefFoundError -- which is why the gate cannot live inside that class.
     */
    private void enableAdvancements() {
        if (advancements != null) {
            return;
        }
        var api = getServer().getPluginManager().getPlugin(ADVANCEMENT_API);
        if (api == null || !api.isEnabled()) {
            getLogger().info(ADVANCEMENT_API + " is not installed; BetterEnd advancements are off."
                + " Everything else works exactly the same.");
            return;
        }
        advancements = BetterEndAdvancements.install(this);
        if (advancements != null) {
            getServer().getPluginManager().registerEvents(
                new AdvancementWatcher(this, advancements, config.advancementSampleTicks()), this);
        }
    }

    @Override
    public void onDisable() {
        if (advancements != null) {
            advancements.discard();
            advancements = null;
        }
        if (nms != null) {
            nms.disable();
        }
    }

    /** Opt-in Bukkit generator; world owners select it with generator=BetterEnd:betterend. */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        if (id != null && !id.isEmpty() && !id.equalsIgnoreCase("betterend")) {
            getLogger().severe("Unknown BetterEnd generator id '" + id + "' for world '" + worldName
                + "'; the only id is 'betterend'.");
            return null;
        }
        try {
            return new DustWastelandsGenerator(config);
        } catch (IllegalStateException | LinkageError error) {
            // Generating now would permanently bake plain end stone into the region files,
            // so hand the world back to vanilla instead and say exactly why.
            getLogger().severe("Refusing to generate '" + worldName + "': " + error.getMessage()
                + ". Set 'misc.delay-configuration-load: false' in plugins/CraftEngine/config.yml"
                + " so CraftEngine registers its blocks before worlds load.");
            return null;
        }
    }
}
