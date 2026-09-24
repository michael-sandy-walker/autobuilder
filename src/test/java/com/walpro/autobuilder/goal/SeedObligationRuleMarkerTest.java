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

    /** T4-CQ: a universal requirement is never covered by a criterion about one of its members. */
    @Test
    void aRequirementOverEveryMemberIsAlwaysAskedAtClosure() {
        String file = fileWith(List.of("Every widget route must be covered by a test in the widget test suite."));
        List<String> banked = List.of("A widget test suite test covers the GET /widgets/sum route");

        assertThat(SeedRequirements.notAlreadyCoveredVerifiable(SEED, banked, file))
                .containsExactly("Every widget route must be covered by a test in the widget test suite.");
    }

    /** T4-CQ: once the author marks rules, an unmarked line is an obligation whatever its verb. */
    @Test
    void anAuthorWhoMarksRulesIsNotOverruledByTheVerbList() {
        String file = fileWith(List.of(
                "Every widget route is covered by a test.",
                "The whole widget suite passes.",
                "rule: Keep the widget packages as they are."));

        assertThat(SeedRequirements.verifiableObligationsOrSplit(SEED, file))
                .containsExactly("Every widget route is covered by a test.", "The whole widget suite passes.");
    }

    @Test
    void aFileWithoutMarkersKeepsTheVerbList() {
        String file = fileWith(List.of(
                "Every widget route must be covered by a test.",
                "Widgets are blue."));

        assertThat(SeedRequirements.verifiableObligationsOrSplit(SEED, file))
                .containsExactly("Every widget route must be covered by a test.");
    }
}
