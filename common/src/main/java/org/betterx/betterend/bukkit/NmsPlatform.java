package org.betterx.betterend.bukkit;

import org.bukkit.plugin.java.JavaPlugin;

/** Version-specific hooks live in the nms module. */
public interface NmsPlatform {
    void enable(JavaPlugin plugin);

    void disable();

    default boolean ownsTerrain() {
        return false;
    }
}
