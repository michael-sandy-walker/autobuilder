package com.walpro.autobuilder.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CQ: a written obligation marked as a RULE guides the work but never blocks completion.
 * Invented wording; the shape is the first adopted project's seed.
 */
class SeedObligationRuleMarkerTest {

    private static final String SEED = "OBJECTIVE: every widget route is covered by a test.";

    private static String fileWith(List<String> lines) {
        return SeedObligationsFile.render(SEED, lines);
    }

    @Test
    void aRuleIsNotAClosureObligation() {
        String file = fileWith(List.of(
                "Every widget route must be covered by a test.",
                "rule: A test must assert what the route actually does."));

        assertThat(SeedRequirements.verifiableObligationsOrSplit(SEED, file))
                .containsExactly("Every widget route must be covered by a test.");
    }

    @Test
    void aRuleStillReachesEveryReaderWithoutItsMarker() {
        String file = fileWith(List.of(
                "Every widget route must be covered by a test.",
                "rule: A test must assert what the route actually does."));

        assertThat(SeedRequirements.obligationsOrSplit(SEED, file))
                .contains("A test must assert what the route actually does.")
                .noneMatch(l -> l.startsWith("rule:"));
    }
}
