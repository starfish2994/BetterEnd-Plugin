package org.betterx.betterend.bukkit.advancement;

import com.fren_gor.ultimateAdvancementAPI.AdvancementTab;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.MultiTasksAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.TaskAdvancement;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import org.betterx.betterend.bukkit.RegionSchedulerAdapter;
import org.betterx.betterend.bukkit.advancement.AdvancementSpec.Node;
import org.betterx.betterend.bukkit.advancement.AdvancementSpec.Tree;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * The only class in the port that names an UltimateAdvancementAPI type.
 * <p>
 * That is a hard requirement, not tidiness. UltimateAdvancementAPI is an optional dependency; when
 * its jar is absent the JVM cannot resolve any class whose signature, field, superclass or interface
 * mentions one of its types. Keeping every reference behind this one class means
 * {@link org.betterx.betterend.bukkit.BetterEndPlugin} can check for the plugin and simply never
 * load this class -- terrain, biomes and everything else keep working with no advancements.
 * <p>
 * Builds the tree from {@link AdvancementSpec} and hands the detection layer three operations:
 * {@link #watchedItems()}, {@link #watchedBiomes()} and {@link #award}.
 */
public final class BetterEndAdvancements {
    /** The tab namespace, and therefore the tab's on-screen identity. */
    private static final String TAB = "betterend";
    /**
     * The vanilla End background. The fork's 26.2 wrapper takes a {@code textures/**.png} path and
     * converts it to the 1.21.2+ GUI sprite id itself
     * ({@code NMS/26_2_R1/.../Util.java:95-110}) -- passing the bare sprite id the mod uses is
     * rejected with a severe log line and no background at all.
     */
    private static final String BACKGROUND = "minecraft:textures/gui/advancements/backgrounds/end.png";
    private static final String LANG_PREFIX = "advancements.betterend.";

    /** One criterion of one row: which advancement it feeds, and which task inside it. */
    public record Hit(String nodeId, int criterion) {
    }

    private final Plugin plugin;
    private final Tree tree;
    private final Map<String, Advancement> byId = new LinkedHashMap<>();
    private final Map<String, List<TaskAdvancement>> tasksById = new HashMap<>();
    private final Map<String, List<Hit>> byItemId = new HashMap<>();
    private final Map<String, List<Hit>> byBiomeId = new HashMap<>();
    private AdvancementTab tab;

    private BetterEndAdvancements(Plugin plugin, Tree tree) {
        this.plugin = plugin;
        this.tree = tree;
    }

    /**
     * Prunes the tree against the CraftEngine item registry, builds the tab and returns the live
     * handle, or null if UltimateAdvancementAPI refused. Never throws: an advancement tab that will
     * not build is a cosmetic loss, and taking the whole plugin down over it would take the world
     * generator with it.
     */
    public static BetterEndAdvancements install(Plugin plugin) {
        Tree tree = AdvancementSpec.prune(AdvancementSpec.PORTED, BetterEndAdvancements::itemExists);
        BetterEndAdvancements advancements = new BetterEndAdvancements(plugin, tree);
        try {
            advancements.build();
        } catch (Exception | LinkageError error) {
            plugin.getLogger().log(Level.WARNING, "BetterEnd advancements were not registered.", error);
            advancements.discard();
            return null;
        }
        plugin.getLogger().info("Registered " + advancements.byId.size() + " advancements ("
                + tree.dropped().size() + " gated off: the subsystems they need are not ported yet).");
        return advancements;
    }

    private static boolean itemExists(String id) {
        try {
            return CraftEngineItems.byId(id) != null;
        } catch (Exception | LinkageError error) {
            // A registry that cannot answer must not hide working content.
            return true;
        }
    }

    private void build() {
        UltimateAdvancementAPI api = UltimateAdvancementAPI.getInstance(plugin);
        if (api.isAdvancementTabRegistered(TAB)) {
            api.unregisterAdvancementTab(TAB);
        }
        tab = api.createAdvancementTab(TAB);

        RootAdvancement root = null;
        Set<BaseAdvancement> children = new HashSet<>();
        // AdvancementSpec.NODES is parent-before-child and prune() preserves that order, which is
        // exactly what BaseAdvancement's constructor needs: it dereferences its parent to find the
        // tab, so a child built first would NPE.
        for (Node node : tree.nodes()) {
            String parentId = tree.parentOf(node.id());
            if (parentId == null) {
                root = new RootAdvancement(tab, node.shortId(), display(node, false, false), BACKGROUND);
                byId.put(node.id(), root);
                index(node);
                continue;
            }
            Advancement parent = byId.get(parentId);
            BaseAdvancement built = node.isMulti()
                    ? multi(node, parent)
                    : new BaseAdvancement(node.shortId(), display(node, true, true), parent);
            byId.put(node.id(), built);
            children.add(built);
            index(node);
        }
        if (root == null) {
            throw new IllegalStateException("the pruned tree has no root");
        }

        // true = auto-layout: the fork walks the parent graph and stamps vanilla-style coordinates
        // over every display, so the 29-row table carries no hand-placed x/y at all.
        tab.registerAdvancements(root, children, true);
        // Root is the mod's empty location predicate -- true for everyone, always -- so the API can
        // grant it itself on PlayerLoadingCompletedEvent. No listener, no race with data loading.
        tab.automaticallyGrantRootAdvancement();
        tab.automaticallyShowToPlayers();
    }

    /**
     * An AND row with more than one criterion, as a progress bar. The API demands the task max
     * progressions sum to the parent's, so each task is worth 1 and the parent is worth the number
     * of criteria; and it demands registerTasks happen before the tab is registered, because
     * validation at registration time rejects an uninitialised multi.
     */
    private MultiTasksAdvancement multi(Node node, Advancement parent) {
        int count = node.keys().size();
        MultiTasksAdvancement built =
                new MultiTasksAdvancement(node.shortId(), display(node, true, true), parent, count);
        List<TaskAdvancement> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tasks.add(new TaskAdvancement(node.shortId() + "/" + criterionName(node, i), built));
        }
        built.registerTasks(new HashSet<>(tasks));
        tasksById.put(node.id(), List.copyOf(tasks));
        return built;
    }

    /** A task key must be a legal advancement path, so the namespace colon has to go. */
    private static String criterionName(Node node, int criterion) {
        String first = node.keys().get(criterion).get(0);
        int colon = first.indexOf(':');
        return colon < 0 ? first : first.substring(colon + 1);
    }

    private void index(Node node) {
        Map<String, List<Hit>> target = switch (node.trigger()) {
            case HAS_ITEM -> byItemId;
            case VISIT_BIOME -> byBiomeId;
            case JOIN, ENTER_END -> null;
        };
        if (target == null) {
            return;
        }
        for (int i = 0; i < node.keys().size(); i++) {
            for (String id : node.keys().get(i)) {
                // One id can feed several rows: thallasium_hammer satisfies both `hammer` and, once
                // the anvil lands, nothing else -- but aeternium_pickaxe_head feeds two for real.
                target.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new Hit(node.id(), i));
            }
        }
    }

    private LocalizedDisplay display(Node node, boolean showToast, boolean announceChat) {
        return new LocalizedDisplay(icon(node.icon()), LANG_PREFIX + node.shortId() + ".title",
                LANG_PREFIX + node.shortId() + ".description", frame(node), showToast, announceChat);
    }

    private static AdvancementFrameType frame(Node node) {
        return switch (node.frame()) {
            case TASK -> AdvancementFrameType.TASK;
            case GOAL -> AdvancementFrameType.GOAL;
            case CHALLENGE -> AdvancementFrameType.CHALLENGE;
        };
    }

    /** A CraftEngine id becomes its real item; a vanilla one becomes its Material. */
    private static ItemStack icon(String id) {
        if (id.startsWith("minecraft:")) {
            Material material = Material.matchMaterial(id);
            return new ItemStack(material == null ? Material.END_STONE : material);
        }
        try {
            BukkitItemDefinition definition = CraftEngineItems.byId(id);
            ItemStack stack = definition == null ? null : definition.buildBukkitItem();
            if (stack != null && !stack.getType().isAir()) {
                return stack;
            }
        } catch (Exception | LinkageError ignored) {
            // fall through to the placeholder
        }
        // prune() only keeps rows whose icon exists, so reaching this means the registry changed
        // under us between the prune and the build. A visible wrong icon beats a failed tab.
        return new ItemStack(Material.END_STONE);
    }

    /** Item ids worth looking for in an inventory. Empty when nothing live watches items. */
    public Set<String> watchedItems() {
        return byItemId.keySet();
    }

    /** Biome ids worth looking for underfoot. */
    public Set<String> watchedBiomes() {
        return byBiomeId.keySet();
    }

    public List<Hit> itemHits(String itemId) {
        return byItemId.getOrDefault(itemId, List.of());
    }

    public List<Hit> biomeHits(String biomeId) {
        return byBiomeId.getOrDefault(biomeId, List.of());
    }

    /** The row granted for simply being in an End world, or null when it was gated off. */
    public String enterEndId() {
        return byId.containsKey("betterend:enter_end") ? "betterend:enter_end" : null;
    }

    /**
     * Grants one criterion, on the one thread the API tolerates.
     * <p>
     * Every UltimateAdvancementAPI write ends at {@code AdvancementUtils.checkSync()}, a bare
     * {@code Bukkit.isPrimaryThread()} test that the fork did NOT make region-aware. Under Folia
     * that passes on any region thread, which would let two region threads write the same team's
     * progression at once. Funnelling every grant onto the global region makes the whole detection
     * layer's thread-safety a non-question, and costs one dispatch per grant -- a handful per player
     * for the life of the world, not one per sample.
     *
     * @param onGranted run on the caller's behalf once the grant is known to have stuck, so the
     *                  detection layer only stops re-checking an id after it really landed.
     */
    public void award(Player player, Hit hit, Runnable onGranted) {
        Advancement advancement = byId.get(hit.nodeId());
        if (advancement == null) {
            return;
        }
        RegionSchedulerAdapter.runGlobal(plugin, () -> {
            try {
                if (advancement.isGranted(player)) {
                    onGranted.run();
                    return;
                }
                List<TaskAdvancement> tasks = tasksById.get(hit.nodeId());
                if (tasks != null && hit.criterion() >= 0 && hit.criterion() < tasks.size()) {
                    TaskAdvancement task = tasks.get(hit.criterion());
                    if (!task.isGranted(player)) {
                        task.grant(player);
                    }
                } else {
                    advancement.grant(player);
                }
                onGranted.run();
                if (advancement.isGranted(player)) {
                    giveXp(player, hit.nodeId());
                }
            } catch (Exception ignored) {
                // Almost always UserNotLoadedException in the three ticks before the API finishes
                // loading this player. onGranted is deliberately NOT run, so the probe retries a
                // second later instead of marking the id done and losing it.
            }
        });
    }

    /**
     * The mod's XP reward. UltimateAdvancementAPI has no reward concept, so the port pays it, and
     * only on the transition -- {@link #award} calls this after checking the row was not already
     * granted, so a rebuilt cache or a rejoin never pays twice.
     */
    private void giveXp(Player player, String nodeId) {
        int xp = tree.nodes().stream().filter(n -> n.id().equals(nodeId))
                .mapToInt(Node::xp).findFirst().orElse(0);
        if (xp > 0) {
            // Back onto the player's own region: giveExp mutates the entity.
            RegionSchedulerAdapter.run(plugin, player, () -> player.giveExp(xp));
        }
    }

    /** The vanilla id of a stack, for the criteria that name vanilla items (all_elytras' elytra). */
    public static String vanillaId(Material material) {
        return "minecraft:" + material.name().toLowerCase(Locale.ROOT);
    }

    public void discard() {
        try {
            UltimateAdvancementAPI api = UltimateAdvancementAPI.getInstance(plugin);
            if (api.isAdvancementTabRegistered(TAB)) {
                api.unregisterAdvancementTab(TAB);
            }
        } catch (Exception | LinkageError ignored) {
            // The API is already gone; there is nothing left to unregister.
        }
        tab = null;
        byId.clear();
        tasksById.clear();
        byItemId.clear();
        byBiomeId.clear();
    }
}
