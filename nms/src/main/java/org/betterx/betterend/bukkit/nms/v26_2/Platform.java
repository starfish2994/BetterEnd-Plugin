package org.betterx.betterend.bukkit.nms.v26_2;

import org.betterx.betterend.bukkit.NmsPlatform;
import org.bukkit.plugin.java.JavaPlugin;

/** Version seam reserved for hooks that cannot be expressed through Bukkit. */
public final class Platform implements NmsPlatform {
    @Override
    public void enable(JavaPlugin plugin) {
        plugin.getLogger().info("Loaded BetterEnd 26.2 NMS seam; Bukkit terrain generator is opt-in.");
    }

    @Override
    public void disable() {
    }
}
