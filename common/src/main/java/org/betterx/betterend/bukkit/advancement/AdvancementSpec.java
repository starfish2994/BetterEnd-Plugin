package org.betterx.betterend.bukkit.advancement;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * BetterEnd's advancement tree as data, plus the one rule that decides which rows a given server can
 * actually show.
 * <p>
 * Transcribed from the mod's generator, {@code Reference/BetterEnd/.../datagen/betterend/advancement/
 * EndAdvancementDataProvider.java}, which is authoritative over the generated JSON beside it: the two
 * agree on every parent, frame, icon, criterion and strategy, and disagree on the XP of six rows
 * because the JSON was emitted back when the code still passed a flat 500. The generator's numbers
 * are the ones here.
 * <p>
 * Pure Java -- no Bukkit, no CraftEngine, no UltimateAdvancementAPI -- so the table and the gate are
 * unit-tested without a server, and so the plugin can decide what to build before it touches an API
 * that may not be installed.
 */
public final class AdvancementSpec {
    private AdvancementSpec() {
    }

    /** The mod's {@code AdvancementType}. */
    public enum Frame { TASK, GOAL, CHALLENGE }

    /**
     * How this row is earned, which is also how the port detects it.
     * <p>
     * {@link #JOIN} and {@link #ENTER_END} are the mod's {@code minecraft:location} (empty predicate)
     * and {@code minecraft:changed_dimension}. {@link #HAS_ITEM} is
     * {@code minecraft:inventory_changed} -- a plain "is it in your inventory" test, not an
     * acquisition event. {@link #VISIT_BIOME} is {@code minecraft:location} with a biome predicate.
     */
    public enum Trigger { JOIN, ENTER_END, HAS_ITEM, VISIT_BIOME }

    /** {@code AdvancementRequirements.Strategy}: every criterion, or any one of them. */
    public enum Strategy { AND, OR }

    /**
     * The subsystem a row needs before it can ever be completed. {@link #PORTED} is the whole of the
     * port's answer -- add a constant to that set the day the subsystem lands and every row gated on
     * it appears, correctly parented, with no other edit.
     */
    public enum Mechanic {
        /** Nothing beyond what the port already generates. */
        NONE,
        /** The eternal portal structure, its ritual and its portal block. */
        ETERNAL_PORTAL,
        /** The end village structure. */
        END_VILLAGE,
        /** The infusion pedestal ritual. Also the only source of the crystalite elytra. */
        INFUSION,
        /** Structure chest loot, the mod's only non-copy source of the eight smithing templates. */
        TEMPLATE_LOOT,
        /** The leveled anvil ({@code bclib:smithing}): every forged plate, tool head and sword part. */
        LEVELED_ANVIL,
        /** The End Stone Smelter ({@code bclib:alloying}): terminite and aeternium ingots. */
        ALLOYING
    }

    /**
     * What the port can currently deliver. Everything else is gated off rather than shown as a row
     * no player could ever complete.
     * <p>
     * {@code LEVELED_ANVIL} and {@code ALLOYING} are the two that pay: between them they hold 14 of
     * the 29 rows, and the pack's own B7 notes call the anvil "the single biggest blocker in B7".
     */
    public static final Set<Mechanic> PORTED = Set.copyOf(EnumSet.of(Mechanic.NONE));

    /**
     * One row of the tree.
     *
     * @param keys for {@link Trigger#HAS_ITEM} the criteria, as the mod groups them: one inner list
     *             per criterion, holding the ids that each satisfy it on their own. The mod's
     *             {@code got_<metal>_sword_head} is the reason this is nested rather than flat --
     *             that single criterion accepts a sword blade OR a sword handle. For
     *             {@link Trigger#VISIT_BIOME} one biome id per criterion. Empty otherwise.
     * @param xp   the generator's reward, 0 for none. UltimateAdvancementAPI has no reward concept,
     *             so the port grants this itself on the completing transition.
     */
    public record Node(String id, String parent, Frame frame, String icon, Trigger trigger,
                       List<List<String>> keys, Strategy strategy, Mechanic needs, int xp) {
        public Node {
            keys = List.copyOf(keys);
        }

        /** Every id this row names, flattened -- what a detector has to watch for. */
        public List<String> flatKeys() {
            List<String> out = new ArrayList<>();
            for (List<String> group : keys) {
                out.addAll(group);
            }
            return List.copyOf(out);
        }

        /** True when completing this needs every criterion, i.e. it wants a progress bar. */
        public boolean isMulti() {
            return strategy == Strategy.AND && keys.size() > 1;
        }

        /** The row's short name, without the {@code betterend:} namespace. */
        public String shortId() {
            int colon = id.indexOf(':');
            return colon < 0 ? id : id.substring(colon + 1);
        }
    }

    private static final String NS = "betterend:";

    /** The 27 biome ids {@code all_the_biomes} enumerates, in the generator's sorted order. */
    public static final List<String> BIOMES = List.of(
            NS + "amber_land", NS + "blossoming_spires", NS + "chorus_forest", NS + "crystal_mountains",
            NS + "dragon_graveyards", NS + "dry_shrubland", NS + "dust_wastelands",
            NS + "empty_aurora_cave", NS + "empty_end_cave", NS + "empty_smaragdant_cave",
            NS + "flower_islets", NS + "foggy_mushroomland", NS + "glowing_grasslands",
            NS + "ice_starfield", NS + "jade_cave", NS + "lantern_woods", NS + "lush_aurora_cave",
            NS + "lush_smaragdant_cave", NS + "megalake", NS + "megalake_grove", NS + "neon_oasis",
            NS + "painted_mountains", NS + "shadow_forest", NS + "sulphur_springs", NS + "umbra_valley",
            NS + "umbrella_jungle", NS + "waterfall_ponds");

    private static List<List<String>> each(String... ids) {
        List<List<String>> out = new ArrayList<>(ids.length);
        for (String id : ids) {
            out.add(List.of(id));
        }
        return out;
    }

    private static List<List<String>> anyOf(String... ids) {
        return List.of(List.of(ids));
    }

    private static List<List<String>> none() {
        return List.of();
    }

    /** The five equipment ids of one metal, in the generator's criterion order. */
    private static List<List<String>> tools(String metal) {
        return each(NS + metal + "_pickaxe", NS + metal + "_hoe", NS + metal + "_axe",
                NS + metal + "_shovel", NS + metal + "_sword");
    }

    /** The five head criteria; the sword one accepts either half, which is why it is a group. */
    private static List<List<String>> toolHeads(String metal) {
        List<List<String>> out = new ArrayList<>(each(NS + metal + "_pickaxe_head",
                NS + metal + "_hoe_head", NS + metal + "_axe_head", NS + metal + "_shovel_head"));
        out.add(List.of(NS + metal + "_sword_blade", NS + metal + "_sword_handle"));
        return out;
    }

    private static List<List<String>> armor(String metal) {
        return each(NS + metal + "_helmet", NS + metal + "_chestplate",
                NS + metal + "_leggings", NS + metal + "_boots");
    }

    private static List<List<String>> biomes() {
        return each(BIOMES.toArray(new String[0]));
    }

    /**
     * All 29 rows, parents before children -- the order every consumer needs, because
     * UltimateAdvancementAPI's {@code BaseAdvancement} constructor dereferences its parent.
     * <p>
     * Note the AND/OR asymmetry the mod builds deliberately: thallasium and terminite tools, heads
     * and armour are OR (any single piece completes them, matching their lang strings "Get hold of
     * any Thallasium Armor Piece"), while the aeternium equivalents are AND.
     * <p>
     * The five rows with no keys at all are the mod's three custom triggers plus its two structure
     * predicates. They have nothing to watch for because the thing that fires them does not exist
     * here yet; {@link #PORTED} keeps them out of the tree regardless.
     */
    public static final List<Node> NODES = List.of(
            new Node(NS + "root", null, Frame.TASK, NS + "end_mycelium",
                    Trigger.JOIN, none(), Strategy.OR, Mechanic.NONE, 0),
            new Node(NS + "enter_end", NS + "root", Frame.TASK, NS + "cave_moss",
                    Trigger.ENTER_END, none(), Strategy.OR, Mechanic.NONE, 0),

            // -- eternal portal branch --------------------------------------------------------
            new Node(NS + "portal", NS + "enter_end", Frame.GOAL, NS + "eternal_pedestal",
                    Trigger.HAS_ITEM, none(), Strategy.OR, Mechanic.ETERNAL_PORTAL, 0),
            new Node(NS + "portal_on", NS + "portal", Frame.TASK, NS + "eternal_crystal",
                    Trigger.HAS_ITEM, none(), Strategy.OR, Mechanic.ETERNAL_PORTAL, 0),
            new Node(NS + "portal_travel", NS + "portal_on", Frame.CHALLENGE, "minecraft:grass_block",
                    Trigger.HAS_ITEM, none(), Strategy.OR, Mechanic.ETERNAL_PORTAL, 0),

            // -- biomes -----------------------------------------------------------------------
            new Node(NS + "all_the_biomes", NS + "enter_end", Frame.CHALLENGE, NS + "aeternium_boots",
                    Trigger.VISIT_BIOME, biomes(), Strategy.AND, Mechanic.NONE, 1500),
            new Node(NS + "village", NS + "all_the_biomes", Frame.GOAL, NS + "tenanea_door",
                    Trigger.HAS_ITEM, none(), Strategy.OR, Mechanic.END_VILLAGE, 0),

            // -- elytras, infusion, templates --------------------------------------------------
            new Node(NS + "all_elytras", NS + "enter_end", Frame.GOAL, NS + "elytra_crystalite",
                    Trigger.HAS_ITEM,
                    each("minecraft:elytra", NS + "elytra_crystalite", NS + "elytra_armored"),
                    Strategy.AND, Mechanic.INFUSION, 0),
            new Node(NS + "infusion", NS + "enter_end", Frame.TASK, NS + "infusion_pedestal",
                    Trigger.HAS_ITEM, each(NS + "infusion_pedestal"), Strategy.OR, Mechanic.NONE, 0),
            new Node(NS + "infusion_finished", NS + "infusion", Frame.GOAL, "minecraft:ender_eye",
                    Trigger.HAS_ITEM, none(), Strategy.OR, Mechanic.INFUSION, 0),
            new Node(NS + "all_the_templates", NS + "enter_end", Frame.CHALLENGE,
                    NS + "tool_assembly_smithing_template", Trigger.HAS_ITEM,
                    each(NS + "handle_attachment_smithing_template", NS + "tool_assembly_smithing_template",
                            NS + "leather_handle_attachment_smithing_template",
                            NS + "plate_upgrade_smithing_template", NS + "terminite_upgrade_smithing_template",
                            NS + "aeternium_upgrade_smithing_template", NS + "thallasium_upgrade_smithing_template",
                            NS + "netherite_upgrade_smithing_template"),
                    Strategy.AND, Mechanic.TEMPLATE_LOOT, 1500),

            // -- hammer / anvil spine ----------------------------------------------------------
            new Node(NS + "hammer", NS + "enter_end", Frame.TASK, NS + "diamond_hammer",
                    Trigger.HAS_ITEM,
                    anyOf(NS + "diamond_hammer", NS + "thallasium_hammer", NS + "terminite_hammer"),
                    Strategy.OR, Mechanic.NONE, 0),
            new Node(NS + "thallasium_anvil", NS + "hammer", Frame.TASK, NS + "thallasium_anvil",
                    Trigger.HAS_ITEM, each(NS + "thallasium_anvil"), Strategy.OR, Mechanic.LEVELED_ANVIL, 0),
            new Node(NS + "terminite_anvil", NS + "thallasium_anvil", Frame.TASK, NS + "terminite_anvil",
                    Trigger.HAS_ITEM, each(NS + "terminite_anvil"), Strategy.OR, Mechanic.LEVELED_ANVIL, 0),
            new Node(NS + "aeternium_anvil", NS + "terminite_anvil", Frame.CHALLENGE, NS + "aeternium_anvil",
                    Trigger.HAS_ITEM, each(NS + "aeternium_anvil"), Strategy.OR, Mechanic.LEVELED_ANVIL, 500),

            // -- thallasium --------------------------------------------------------------------
            new Node(NS + "thallasium_plate", NS + "thallasium_anvil", Frame.TASK,
                    NS + "thallasium_forged_plate", Trigger.HAS_ITEM,
                    each(NS + "thallasium_forged_plate"), Strategy.OR, Mechanic.LEVELED_ANVIL, 0),
            new Node(NS + "thallasium_tool_head", NS + "thallasium_anvil", Frame.TASK,
                    NS + "thallasium_pickaxe_head", Trigger.HAS_ITEM, toolHeads("thallasium"),
                    Strategy.OR, Mechanic.LEVELED_ANVIL, 0),
            new Node(NS + "thallasium_tool", NS + "thallasium_tool_head", Frame.TASK,
                    NS + "thallasium_pickaxe", Trigger.HAS_ITEM, tools("thallasium"),
                    Strategy.OR, Mechanic.LEVELED_ANVIL, 0),
            new Node(NS + "thallasium_armor", NS + "thallasium_plate", Frame.TASK,
                    NS + "thallasium_chestplate", Trigger.HAS_ITEM, armor("thallasium"),
                    Strategy.OR, Mechanic.LEVELED_ANVIL, 0),

            // -- terminite: the anvil AND the smelter, since terminite_ingot is alloyed ---------
            new Node(NS + "terminite_plate", NS + "terminite_anvil", Frame.TASK,
                    NS + "terminite_forged_plate", Trigger.HAS_ITEM,
                    each(NS + "terminite_forged_plate"), Strategy.OR, Mechanic.ALLOYING, 0),
            new Node(NS + "terminite_tool_head", NS + "terminite_anvil", Frame.TASK,
                    NS + "terminite_pickaxe_head", Trigger.HAS_ITEM, toolHeads("terminite"),
                    Strategy.OR, Mechanic.ALLOYING, 0),
            new Node(NS + "terminite_tool", NS + "terminite_tool_head", Frame.TASK,
                    NS + "terminite_pickaxe", Trigger.HAS_ITEM, tools("terminite"),
                    Strategy.OR, Mechanic.ALLOYING, 0),
            new Node(NS + "terminite_armor", NS + "terminite_plate", Frame.TASK,
                    NS + "terminite_chestplate", Trigger.HAS_ITEM, armor("terminite"),
                    Strategy.OR, Mechanic.ALLOYING, 0),

            // -- aeternium ---------------------------------------------------------------------
            new Node(NS + "aeternium_hammer_head", NS + "aeternium_anvil", Frame.TASK,
                    NS + "aeternium_hammer_head", Trigger.HAS_ITEM,
                    each(NS + "aeternium_hammer_head"), Strategy.OR, Mechanic.ALLOYING, 0),
            new Node(NS + "aeternium_hammer", NS + "aeternium_hammer_head", Frame.TASK,
                    NS + "aeternium_hammer", Trigger.HAS_ITEM,
                    each(NS + "aeternium_hammer"), Strategy.OR, Mechanic.ALLOYING, 0),
            new Node(NS + "aeternium_plate", NS + "aeternium_hammer", Frame.GOAL,
                    NS + "aeternium_forged_plate", Trigger.HAS_ITEM,
                    each(NS + "aeternium_forged_plate"), Strategy.OR, Mechanic.ALLOYING, 200),
            new Node(NS + "aeternium_tool_head", NS + "aeternium_hammer", Frame.GOAL,
                    NS + "aeternium_pickaxe_head", Trigger.HAS_ITEM, toolHeads("aeternium"),
                    Strategy.AND, Mechanic.ALLOYING, 200),
            new Node(NS + "aeternium_tool", NS + "aeternium_tool_head", Frame.CHALLENGE,
                    NS + "aeternium_pickaxe", Trigger.HAS_ITEM, tools("aeternium"),
                    Strategy.AND, Mechanic.ALLOYING, 2000),
            new Node(NS + "aeternium_armor", NS + "aeternium_plate", Frame.CHALLENGE,
                    NS + "aeternium_chestplate", Trigger.HAS_ITEM, armor("aeternium"),
                    Strategy.AND, Mechanic.ALLOYING, 2000));

    /** A tree pruned to what this server can actually award, with the survivors re-parented. */
    public record Tree(List<Node> nodes, Map<String, String> parents, List<String> dropped) {
        /** The parent id this row must hang off after pruning, or null for the root. */
        public String parentOf(String id) {
            return parents.get(id);
        }
    }

    /**
     * Keeps the rows whose subsystem is ported and whose items all exist, then hangs each survivor
     * off its nearest surviving ancestor.
     * <p>
     * Re-parenting is the whole point. Drop {@code thallasium_anvil} -- which is what happens today,
     * because the CraftEngine pack ships no anvil -- and its four children would otherwise name a
     * parent that was never built. Walking up to the nearest kept ancestor puts them under
     * {@code hammer} instead, which is where the mod's tree would place them if the anvil step did
     * not exist.
     *
     * @param ported     the subsystems this server has; normally {@link #PORTED}. Taken as an
     *                   argument rather than read from the constant so a test can prune against a
     *                   hypothetical future server without mutating global state.
     * @param itemExists answers whether an item id is registered; ids outside the {@code betterend}
     *                   namespace are vanilla and are never asked about.
     */
    public static Tree prune(Set<Mechanic> ported, Predicate<String> itemExists) {
        Map<String, String> declared = new LinkedHashMap<>();
        Set<String> kept = new LinkedHashSet<>();
        List<String> dropped = new ArrayList<>();
        for (Node node : NODES) {
            if (node.parent() != null) {
                declared.put(node.id(), node.parent());
            }
            if (isLive(node, ported, itemExists)) {
                kept.add(node.id());
            } else {
                dropped.add(node.id());
            }
        }

        List<Node> nodes = new ArrayList<>(kept.size());
        Map<String, String> parents = new LinkedHashMap<>();
        for (Node node : NODES) {
            if (!kept.contains(node.id())) {
                continue;
            }
            nodes.add(node);
            String parent = declared.get(node.id());
            while (parent != null && !kept.contains(parent)) {
                parent = declared.get(parent);
            }
            if (parent != null) {
                parents.put(node.id(), parent);
            }
        }
        return new Tree(List.copyOf(nodes), Map.copyOf(parents), List.copyOf(dropped));
    }

    private static boolean isLive(Node node, Set<Mechanic> ported, Predicate<String> itemExists) {
        if (!ported.contains(node.needs())) {
            return false;
        }
        // The icon is deliberately NOT gated on. It is cosmetic and the builder already falls back
        // to a placeholder, whereas gating on it would let one unresolvable icon delete the row --
        // and if that row were the root, the entire tab with it. A missing CRITERION item is the
        // opposite: it makes the row unwinnable, which is what this method is for.
        if (node.trigger() != Trigger.HAS_ITEM) {
            return true;
        }
        // Every criterion must still be reachable: one whose ids are all missing can never be
        // satisfied, which for an AND row makes the whole thing impossible and for an OR row makes
        // it a lie about what is required.
        for (List<String> group : node.keys()) {
            boolean any = false;
            for (String id : group) {
                if (exists(id, itemExists)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private static boolean exists(String id, Predicate<String> itemExists) {
        // A vanilla id is always there; only the pack's own ids can go missing.
        return !id.startsWith(NS) || itemExists.test(id);
    }
}
