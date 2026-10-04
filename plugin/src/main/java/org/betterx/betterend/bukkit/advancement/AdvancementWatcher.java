package org.betterx.betterend.bukkit.advancement;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.betterx.betterend.bukkit.advancement.BetterEndAdvancements.Hit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches for the three things BetterEnd's live advancements actually test: being in the End,
 * standing in a biome, and holding an item.
 * <p>
 * <b>One repeating per-player probe, and no inventory events at all.</b> The mod's criterion is
 * {@code minecraft:inventory_changed}, which is a test of inventory CONTENTS, not of how they got
 * there -- and Bukkit has no event for "an ItemStack entered a player's inventory". {@code /give}
 * calls {@code Inventory#addItem} directly and fires nothing; so do kit, crate, grave and reward
 * plugins, so does a shulker box being emptied, and so does an item that was already in the
 * inventory before this plugin was installed. Sixteen listeners would still miss all of that. A
 * probe that reads the resulting state cannot, because it does not model acquisition routes at all.
 * <p>
 * It is also cheap: one {@code getContents()} array copy, ~41 null checks and one hash probe per
 * non-vanilla stack, per player, per second. The biome sample rides along in the same tick.
 * <p>
 * The probe runs on {@code player.getScheduler()}, whose documented guarantee is that callbacks run
 * on the region owning the entity -- so it follows the player across regions and worlds, and reads
 * their world and inventory from the only thread allowed to. A location-pinned
 * {@code RegionScheduler} task would read a moving player from the wrong thread.
 */
public final class AdvancementWatcher implements Listener {
    /**
     * ponytail: 1 s sampling, so a player crossing a biome faster than that can miss it on the first
     * pass -- realistically only the three speckle sub-biomes (megalake_grove, neon_oasis,
     * painted_mountains), which stay reachable on any later pass. Drop to 10 if elytra transit ever
     * needs to count.
     */
    private final long periodTicks;
    private final Plugin plugin;
    private final BetterEndAdvancements advancements;

    public AdvancementWatcher(Plugin plugin, BetterEndAdvancements advancements, long periodTicks) {
        this.plugin = plugin;
        this.advancements = advancements;
        this.periodTicks = periodTicks;
    }

    /**
     * The whole registration surface. There is no PlayerChangedWorldEvent handler and no quit
     * handler: the probe re-reads the world every second anyway, and the EntityScheduler retires the
     * task when the player is removed, which takes the closure and both sets with it. That is the
     * design's cleanest property -- no {@code Map<UUID, ?>} to leak.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Granted ids, not merely observed ones: an id is only retired once the API confirms the
        // grant stuck, so the three ticks before a player's advancement data finishes loading cost
        // a retry rather than a lost advancement. Written from the global region, read from the
        // player's -- hence concurrent.
        Set<String> done = ConcurrentHashMap.newKeySet();
        player.getScheduler().runAtFixedRate(plugin, task -> probe(player, done), null,
                periodTicks, periodTicks);
    }

    private void probe(Player player, Set<String> done) {
        if (!player.isOnline()) {
            return;
        }
        enterEnd(player, done);
        biome(player, done);
        inventory(player, done);
    }

    /**
     * The mod's {@code minecraft:changed_dimension} to {@code minecraft:the_end}, tested as a fact
     * rather than as a transition. Doing it here instead of in a PlayerChangedWorldEvent handler is
     * what makes logging out in the End and back in still count -- a join fires no world-change
     * event, and logging out in the End is the most common way to be there.
     * <p>
     * Selected on Environment, never on world name: a BetterEnd world is whatever bukkit.yml points
     * at the generator, under any Multiverse name.
     */
    private void enterEnd(Player player, Set<String> done) {
        String id = advancements.enterEndId();
        if (id == null || done.contains(id)) {
            return;
        }
        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            advancements.award(player, new Hit(id, -1), () -> done.add(id));
        }
    }

    private void biome(Player player, Set<String> done) {
        if (advancements.watchedBiomes().isEmpty()) {
            return;
        }
        // Biome is a registry-backed interface in this Paper version, not an enum: it has no name()
        // and valueOf() would never see a datapack biome, so the key is the only handle. getBiome
        // reads the chunk the player stands in, which is loaded by definition, so this can never
        // trigger a chunk load.
        String biome = player.getWorld().getBiome(player.getLocation()).getKey().toString();
        if (done.contains(biome)) {
            return;
        }
        for (Hit hit : advancements.biomeHits(biome)) {
            advancements.award(player, hit, () -> done.add(biome));
        }
    }

    private void inventory(Player player, Set<String> done) {
        Set<String> watched = advancements.watchedItems();
        if (watched.isEmpty()) {
            return;
        }
        // getContents() is all 41 slots -- 36 storage plus armour plus offhand -- in one array copy,
        // so the armour and offhand accessors are not needed on top of it.
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            String id = idOf(stack);
            if (id == null || done.contains(id) || !watched.contains(id)) {
                continue;
            }
            for (Hit hit : advancements.itemHits(id)) {
                advancements.award(player, hit, () -> done.add(id));
            }
        }
        // Deliberately no recursion into shulker boxes or bundles: the mod's criterion is about the
        // inventory, and "stored inside a container you are carrying" is a different question.
    }

    /** The CraftEngine id if it has one, else the vanilla id -- all_elytras names a plain elytra. */
    private static String idOf(ItemStack stack) {
        try {
            Key key = CraftEngineItems.getCustomItemId(stack);
            if (key != null) {
                return key.toString();
            }
        } catch (Exception | LinkageError ignored) {
            // CraftEngine cannot answer for this stack; fall back to the vanilla id.
        }
        return BetterEndAdvancements.vanillaId(stack.getType());
    }
}
