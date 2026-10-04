package org.betterx.betterend.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class CraftEngineBridge {
    private static final String PACK = "betterend";
    private static final String RESOURCE_ROOT = "craftengine/" + PACK;
    private static final String STAMP = ".betterend-version";
    private final JavaPlugin plugin;
    private boolean overwrite;

    public CraftEngineBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Plugin plugin() {
        return Bukkit.getPluginManager().getPlugin("CraftEngine");
    }

    public boolean available() {
        Plugin craftEngine = plugin();
        return craftEngine != null && craftEngine.isEnabled();
    }

    /**
     * Installs every bundled pack file. The pack is plugin-owned: it is rewritten whenever
     * the installed stamp does not match this build, so new content actually reaches servers
     * that already have an older pack. Customise BetterEnd from a separate CraftEngine pack.
     */
    public void installBundledPack() throws IOException {
        Plugin craftEngine = plugin();
        if (craftEngine == null) {
            throw new IOException("CraftEngine is not installed");
        }

        Path root = craftEngine.getDataFolder().toPath().resolve("resources").resolve(PACK);
        Path stamp = root.resolve(STAMP);
        String version = plugin.getPluginMeta().getVersion();
        // A SNAPSHOT build changes without its version changing, so never trust the stamp for one.
        overwrite = version.endsWith("-SNAPSHOT")
            || !Files.isRegularFile(stamp)
            || !Files.readString(stamp).equals(version);

        if (overwrite && Files.exists(root)) {
            plugin.getLogger().info("Updating the bundled CraftEngine pack in " + root + " to " + version + ".");
        }
        copyDirectory(RESOURCE_ROOT, root);
        Files.createDirectories(root);
        Files.writeString(stamp, version);
    }

    private void copyResource(String resource, Path target) throws IOException {
        if (Files.exists(target) && !overwrite) return;
        Files.createDirectories(target.getParent());
        // Write beside the target and rename, so a crash mid-copy cannot leave a truncated
        // file that the exists-check would then treat as installed forever.
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (InputStream input = plugin.getResource(resource)) {
            if (input == null) throw new IOException("Missing bundled resource " + resource);
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void copyDirectory(String resourceRoot, Path targetRoot) throws IOException {
        URL url = plugin.getClass().getClassLoader().getResource(resourceRoot);
        if (url == null) throw new IOException("Missing bundled resource directory " + resourceRoot);
        if ("file".equals(url.getProtocol())) {
            final Path sourceRoot;
            try {
                sourceRoot = Path.of(url.toURI());
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Invalid bundled resource URL " + url, e);
            }
            try (var files = Files.walk(sourceRoot)) {
                files.filter(Files::isRegularFile).forEach(source -> {
                    try {
                        copyResource(resourceRoot + "/" + sourceRoot.relativize(source).toString().replace('\\', '/'),
                            targetRoot.resolve(sourceRoot.relativize(source).toString()));
                    } catch (IOException e) {
                        throw new ResourceCopyException(e);
                    }
                });
            } catch (ResourceCopyException e) {
                throw e.cause;
            }
            return;
        }
        if ("jar".equals(url.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                String prefix = resourceRoot + "/";
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                    copyResource(entry.getName(), targetRoot.resolve(entry.getName().substring(prefix.length())));
                }
            }
            return;
        }
        throw new IOException("Unsupported bundled resource URL " + url);
    }

    private static final class ResourceCopyException extends RuntimeException {
        private final IOException cause;

        private ResourceCopyException(IOException cause) {
            this.cause = cause;
        }
    }

}
