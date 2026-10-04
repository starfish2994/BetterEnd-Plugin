package org.betterx.betterend.bukkit.vanilla;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard's diagnosis, driven without a server: {@link VanillaEndCheck#problems} is deliberately
 * a pure function of the three facts {@code warnIfBroken} samples off the {@code World}, because
 * the common module's test classpath carries no paper-api.
 *
 * <p>What is NOT tested here, and why: that {@code WorldInitEvent} fires before {@code prepareLevel}
 * and that the world PDC is writable at that point are Paper's behaviour, not ours -- they are
 * cited in the class javadoc and only a running server can check them.
 */
class VanillaEndCheckTest {

    @Test
    void aHealthyEndIsSilent() {
        // Also the fresh-world case, which is the whole reason the check hangs off WorldInitEvent
        // and a first-boot marker: a world this boot generates itself reports originExisted false,
        // so it is not diagnosed as a broken migration and told to delete chunks that are fine.
        assertEquals(List.of(), VanillaEndCheck.problems("world_the_end", "THE_END", false));
    }

    @Test
    void aNonEndWorldIsReported() {
        List<String> problems = VanillaEndCheck.problems("overworld", "NORMAL", false);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("'overworld'"), problems.toString());
        assertTrue(problems.getFirst().contains("NORMAL"), "the message must name what it found");
        assertTrue(problems.getFirst().contains("THE_END"), "the message must name what it wanted");
        assertEquals(1, VanillaEndCheck.problems("nether", "NETHER", false).size());
        assertEquals(1, VanillaEndCheck.problems("custom", "CUSTOM", false).size());
    }

    @Test
    void aPreexistingOriginIsReportedWithTheChunksToDelete() {
        List<String> problems = VanillaEndCheck.problems("world_the_end", "THE_END", true);
        assertEquals(1, problems.size(), problems.toString());
        String message = problems.getFirst();
        assertTrue(message.contains("'world_the_end'"), message);
        // The migration advice is only useful if it names the exact chunk window; an off-by-one
        // leaves a pillar in a stale chunk.
        assertTrue(message.contains(VanillaEndCore.coreChunkMin() + ".." + VanillaEndCore.coreChunkMax()),
                message);
    }

    @Test
    void bothConditionsAreReportedTogether() {
        // The old code returned early in the middle of the checks, so the second one could never be
        // seen once the first fired.
        List<String> problems = VanillaEndCheck.problems("broken", "NORMAL", true);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().allMatch(line -> line.contains("'broken'")), problems.toString());
        assertEquals(2, problems.stream().distinct().count(), "the same line twice is not two problems");
    }
}
