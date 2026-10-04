package org.betterx.betterend.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class NmsLoader {
    private NmsLoader() {
    }

    public static NmsPlatform load(JavaPlugin plugin) {
        String version = Bukkit.getBukkitVersion();
        boolean is26_2 = version.equals("26.2") || version.startsWith("26.2.") || version.startsWith("26.2-");
        if (!is26_2) {
            plugin.getLogger().info("Unsupported server version " + version + "; running BetterEnd Lite (26.2 only).");
            return lite();
        }
        String className = "org.betterx.betterend.bukkit.nms.v26_2.Platform";
        try {
            Object platform = Class.forName(className, true, plugin.getClass().getClassLoader())
                .getDeclaredConstructor().newInstance();
            if (platform instanceof NmsPlatform nms) {
                return nms;
            }
            plugin.getLogger().warning("Invalid NMS platform class: " + className);
        } catch (ClassNotFoundException ignored) {
            plugin.getLogger().info("No NMS binding for " + version + "; running BetterEnd Lite.");
        } catch (ReflectiveOperationException | LinkageError error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                "Failed to load NMS binding " + className + "; running BetterEnd Lite.", error);
        }
        return lite();
    }

    private static NmsPlatform lite() {
        return new NmsPlatform() {
            @Override
            public void enable(JavaPlugin ignored) {
            }

            @Override
            public void disable() {
            }
        };
    }
}
