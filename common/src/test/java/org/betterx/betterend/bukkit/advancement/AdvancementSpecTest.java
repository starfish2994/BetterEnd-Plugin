package org.betterx.betterend.bukkit.advancement;

import org.betterx.betterend.bukkit.advancement.AdvancementSpec.Mechanic;
import org.betterx.betterend.bukkit.advancement.AdvancementSpec.Node;
import org.betterx.betterend.bukkit.advancement.AdvancementSpec.Strategy;
import org.betterx.betterend.bukkit.advancement.AdvancementSpec.Tree;
import org.betterx.betterend.bukkit.advancement.AdvancementSpec.Trigger;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties a broken table or a broken gate would violate. Every assertion here fails for a
 * specific wrong implementation: a table whose children precede their parents crashes
 * UltimateAdvancementAPI's constructor chain, a gate that forgets to re-parent hands it an orphan,
 * one that walks up only a single level orphans a node under two dropped ancestors, and a
 * criteria list that got flattened silently turns the sword-head pair into two requirements.
 */
class AdvancementSpecTest {
    /** The three ids the CraftEngine pack genuinely does not ship, verified against the yml. */
    private static final Set<String> MISSING_FROM_PACK = Set.of(
            "betterend:thallasium_anvil", "betterend:terminite_anvil", "betterend:aeternium_anvil");

    private static final Predicate<String> REAL_PACK = id -> !MISSING_FROM_PACK.contains(id);
    private static final Predicate<String> EVERYTHING = id -> true;
    /** A hypothetical fully-ported server, for exercising the gate past today's shipped state. */
    private static final Set<Mechanic> ALL_MECHANICS = Set.of(Mechanic.values());

    /**
     * UltimateAdvancementAPI's {@code BaseAdvancement} constructor dereferences its parent to find
     * the tab, so a child built before its parent throws. Declaration order is load-bearing, not
     * cosmetic.
     */
    @Test
    void parentsAreDeclaredBeforeTheirChildren() {
        Set<String> seen = new HashSet<>();
        for (Node node : AdvancementSpec.NODES) {
            if (node.parent() != null) {
                assertTrue(seen.contains(node.parent()),
                        node.id() + " names parent " + node.parent() + " which is declared later");
            }
            assertTrue(seen.add(node.id()), "duplicate id " + node.id());
        }
        assertEquals(29, AdvancementSpec.NODES.size(), "the mod ships 29 advancements");
    }

    /** Exactly one root, and every other row reaches it. */
    @Test
    void thereIsOneRootAndNothingIsDetached() {
        long roots = AdvancementSpec.NODES.stream().filter(n -> n.parent() == null).count();
        assertEquals(1, roots);
        for (Node node : AdvancementSpec.NODES) {
            int hops = 0;
            String id = node.id();
            while (id != null) {
                String parent = parentIn(AdvancementSpec.NODES, id);
                id = parent;
                assertTrue(++hops <= 29, "cycle reaching the root from " + node.id());
            }
        }
    }

    /**
     * With every mechanic ported and every item present the gate must be a no-op: same 29 rows, same
     * parents. A gate that drops or re-parents anything here is corrupting a healthy tree.
     */
    @Test
    void aFullyPortedServerKeepsTheModsTreeUnchanged() {
        Tree tree = AdvancementSpec.prune(ALL_MECHANICS, EVERYTHING);
        assertEquals(29, tree.nodes().size());
        assertTrue(tree.dropped().isEmpty());
        for (Node node : AdvancementSpec.NODES) {
            assertEquals(node.parent(), tree.parentOf(node.id()),
                    node.id() + " was re-parented when nothing was dropped");
        }
    }

    /**
     * The shipped configuration: only {@code NONE} is ported and the pack has no anvils. Exactly the
     * five rows a player can actually finish survive.
     */
    @Test
    void theShippedGateKeepsExactlyTheFiveObtainableRows() {
        Tree tree = AdvancementSpec.prune(AdvancementSpec.PORTED, REAL_PACK);
        assertEquals(
                List.of("betterend:root", "betterend:enter_end", "betterend:all_the_biomes",
                        "betterend:infusion", "betterend:hammer"),
                tree.nodes().stream().map(Node::id).toList());
        assertEquals(24, tree.dropped().size());
        assertNull(tree.parentOf("betterend:root"));
        assertEquals("betterend:enter_end", tree.parentOf("betterend:all_the_biomes"));
        assertEquals("betterend:enter_end", tree.parentOf("betterend:hammer"));
    }

    /** Whatever survives must hang off something that also survived, or the API rejects the tree. */
    @Test
    void everyKeptRowHasAKeptParent() {
        for (Set<Mechanic> ported : List.of(AdvancementSpec.PORTED, ALL_MECHANICS)) {
            Tree tree = AdvancementSpec.prune(ported, REAL_PACK);
            Set<String> kept = new HashSet<>(tree.nodes().stream().map(Node::id).toList());
            for (Node node : tree.nodes()) {
                String parent = tree.parentOf(node.id());
                if (node.parent() == null) {
                    assertNull(parent);
                } else {
                    assertNotNull(parent, node.id() + " lost its parent entirely");
                    assertTrue(kept.contains(parent), node.id() + " hangs off dropped " + parent);
                }
            }
        }
    }

    /**
     * The case that motivates the whole gate, and the one a single-level re-parent gets wrong.
     * Port the leveled anvil but leave the anvils out of the pack: all three anvil rows drop, and
     * {@code terminite_plate} -- whose parent AND grandparent are both gone -- must climb two levels
     * to {@code hammer}, not stop at the dropped {@code terminite_anvil}.
     */
    @Test
    void reparentingClimbsPastMoreThanOneDroppedAncestor() {
        Tree tree = AdvancementSpec.prune(ALL_MECHANICS, REAL_PACK);
        Set<String> kept = new HashSet<>(tree.nodes().stream().map(Node::id).toList());

        assertFalse(kept.contains("betterend:thallasium_anvil"), "no anvil item, so no anvil row");
        assertFalse(kept.contains("betterend:terminite_anvil"));
        assertFalse(kept.contains("betterend:aeternium_anvil"));

        // one dropped ancestor
        assertEquals("betterend:hammer", tree.parentOf("betterend:thallasium_plate"));
        // two dropped ancestors: terminite_anvil -> thallasium_anvil -> hammer
        assertEquals("betterend:hammer", tree.parentOf("betterend:terminite_plate"));
        // three: aeternium_anvil -> terminite_anvil -> thallasium_anvil -> hammer
        assertEquals("betterend:hammer", tree.parentOf("betterend:aeternium_hammer_head"));
        // a surviving intermediate is still used, not skipped
        assertEquals("betterend:aeternium_hammer_head", tree.parentOf("betterend:aeternium_hammer"));
    }

    /** all_the_biomes is the one advancement the port can fully deliver; its 27 must be its 27. */
    @Test
    void allTheBiomesEnumeratesTheTwentySevenShippedBiomes() {
        Node node = node("betterend:all_the_biomes");
        assertEquals(Trigger.VISIT_BIOME, node.trigger());
        assertEquals(Strategy.AND, node.strategy());
        assertEquals(27, node.keys().size());
        assertEquals(AdvancementSpec.BIOMES, node.flatKeys());
        assertTrue(node.isMulti(), "27 required criteria must render as a progress bar");
        assertEquals(1500, node.xp(), "the generator's reward, not the stale JSON's 500");
    }

    /**
     * The mod's {@code got_<metal>_sword_head} accepts a blade OR a handle in ONE criterion. Flatten
     * the groups and that silently becomes two separate requirements, which for the AND-strategy
     * aeternium row makes it need both halves instead of either.
     */
    @Test
    void theSwordHeadCriterionKeepsItsTwoAlternativesInOneGroup() {
        for (String metal : List.of("thallasium", "terminite", "aeternium")) {
            Node node = node("betterend:" + metal + "_tool_head");
            assertEquals(5, node.keys().size(), metal + " has five head criteria");
            assertEquals(6, node.flatKeys().size(), metal + " names six ids across those five");
            List<String> sword = node.keys().get(4);
            assertEquals(List.of("betterend:" + metal + "_sword_blade",
                    "betterend:" + metal + "_sword_handle"), sword);
        }
    }

    /** OR rows must not claim a progress bar, or the client shows "1/3" for a one-of-three test. */
    @Test
    void orRowsAreNeverMulti() {
        for (Node node : AdvancementSpec.NODES) {
            if (node.strategy() == Strategy.OR) {
                assertFalse(node.isMulti(), node.id() + " is OR but asks for a progress bar");
            }
        }
    }

    /** A row that watches for items must name some, or it can never fire. */
    @Test
    void everyLiveRowHasSomethingToWatchFor() {
        Tree tree = AdvancementSpec.prune(AdvancementSpec.PORTED, REAL_PACK);
        for (Node node : tree.nodes()) {
            switch (node.trigger()) {
                case HAS_ITEM, VISIT_BIOME -> assertFalse(node.keys().isEmpty(),
                        node.id() + " survived the gate with nothing to detect");
                case JOIN, ENTER_END -> assertTrue(node.keys().isEmpty(),
                        node.id() + " is detected by presence, so it must name no keys");
            }
        }
    }

    private static Node node(String id) {
        return AdvancementSpec.NODES.stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    private static String parentIn(List<Node> nodes, String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow().parent();
    }
}
