package org.betterx.betterend.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Keeps world and entity work on the owner region on Folia and Paper. */
public final class RegionSchedulerAdapter {
    private RegionSchedulerAdapter() {
    }

    public static void run(Plugin plugin, Location location, Runnable task) {
        Bukkit.getRegionScheduler().run(plugin, location, ignored -> task.run());
    }

    public static void run(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    public static void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }
}
