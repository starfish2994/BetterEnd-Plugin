package org.betterx.betterend.bukkit.vanilla;

import org.betterx.betterend.bukkit.DustWastelandsGenerator;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Startup diagnosis for the vanilla End furniture. The plugin cannot place the pillars itself
 * (see {@link VanillaEndCore}), so the only thing it can do about a misconfigured or already-broken
 * world is say so, loudly, once.
 *
 * <p>{@code WorldInitEvent}, NOT {@code WorldLoadEvent}, and that is the whole point of the class:
 * {@code World#getGenerator} is already set when init fires ({@code MinecraftServer.java.patch:438}
 * reads {@code overworld.generator} two lines before firing it at :444) but nothing has generated
 * yet -- {@code setInitialSpawn} and {@code prepareLevel} both come after
 * ({@code CraftServer.java:1306} then :1310). {@code WorldLoadEvent} fires at the END of
 * {@code prepareLevel} ({@code MinecraftServer.java.patch:572}), by which point this boot may have
 * generated the origin itself, and the "your origin chunks already exist" warning would be a
 * permanent false positive that tells owners to delete chunks.
 *
 * <p>Runs inline on the world-init thread. Nothing here is an entity or a location, there is no
 * region to schedule into yet, and deferring through any scheduler would let {@code prepareLevel}
 * run first and destroy the one bit being sampled.
 *
 * <p>Reads no blocks. {@link World#isChunkGenerated} asks the region file whether a chunk exists
 * and never inspects its contents, so CraftEngine's material deception does not apply.
 */
public final class VanillaEndCheck implements Listener {
    /** Written to the world's own PDC, so the migration warning fires at most once per world. */
    private static final NamespacedKey SEEN = new NamespacedKey("betterend", "seen");

    @EventHandler
    public void onWorldInit(@NotNull WorldInitEvent event) {
        warnIfBroken(event.getWorld());
    }

    /**
     * Registered blind for every world and self-selecting: which worlds use this generator lives in
     * bukkit.yml, which is not ours to read, so the world itself is asked instead.
     */
    public static void warnIfBroken(@NotNull World world) {
        if (!(world.getGenerator() instanceof DustWastelandsGenerator)) {
            return;
        }
        // ponytail: the byte only reaches disk at the next world save (ServerLevel.saveLevelData
        // copies this container into PaperWorldPDC), so a crash between world init and the first
        // save replays firstBoot on the next start -- and by then this boot HAS generated the
        // origin, so the migration warning below fires once as a false alarm advising a chunk
        // delete that is not needed. That is why this is a warning and never a repair action.
        // Upgrade path if it ever bites: write the marker to a plugin-owned file instead.
        boolean firstBoot = !world.getPersistentDataContainer().has(SEEN, PersistentDataType.BYTE);
        if (firstBoot) {
            world.getPersistentDataContainer().set(SEEN, PersistentDataType.BYTE, (byte) 1);
        }
        Logger log = Logger.getLogger("BetterEnd");
        problems(world.getName(), world.getEnvironment().name(),
                firstBoot && world.isChunkGenerated(0, 0)).forEach(log::warning);
    }

    /**
     * The diagnosis itself, as text: pure, so it is unit-tested without a server.
     *
     * @param environment    {@code World.Environment.name()}; anything but {@code THE_END} is broken
     * @param originExisted  the origin chunks were already in the region files BEFORE this boot --
     *                       i.e. this is the first boot with BetterEnd on a world that already ran
     * @return one line per problem, empty for a healthy world
     */
    static List<String> problems(String world, String environment, boolean originExisted) {
        List<String> out = new ArrayList<>();
        if (!"THE_END".equals(environment)) {
            // CraftWorld.java:1947-1949 returns a DragonBattle only when ServerLevel has one, and
            // ServerLevel only builds one when the DIMENSION TYPE has hasEnderDragonFight() -- never
            // based on the chunk generator. A NORMAL world running this generator therefore has no
            // dragon fight, no exit portal and no arrival platform, and no plugin can add them.
            out.add("World '" + world + "' uses the BetterEnd generator but its"
                    + " environment is " + environment + ", not THE_END. It will have no"
                    + " ender dragon, no exit portal and no obsidian arrival platform. Those are"
                    + " owned by the dimension type and cannot be restored by a plugin - generate"
                    + " the End dimension itself with this generator instead.");
        }
        if (originExisted) {
            // Chunk decoration runs exactly once, when a chunk is first generated. Installing
            // BetterEnd does not revisit a chunk that is already in the region file, and
            // World#regenerateChunk throws UnsupportedOperationException on this version
            // (paper-api World.java:506-508).
            out.add("This is the first startup of world '" + world + "' with BetterEnd, and its"
                    + " origin chunks already exist. Whatever they contain is what they keep: chunks"
                    + " are decorated once, at generation, and this version cannot revisit them."
                    + " If the obsidian pillars are missing, stop the server and delete chunks "
                    + VanillaEndCore.coreChunkMin() + ".." + VanillaEndCore.coreChunkMax()
                    + " on both axes in this dimension, then let them regenerate. A world that"
                    + " already has its pillars can ignore this.");
        }
        return out;
    }
}
