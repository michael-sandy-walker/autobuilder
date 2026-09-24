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

    /** T4-CQ: a line the test gate answers is not put to a model reading the source. */
    @Test
    void aLineTheTestGateAnswersIsLeftToTheTestGate() {
        String file = fileWith(List.of(
                "Every widget route is covered by a test.",
                "gate: tests The whole widget suite passes.",
                "rule: Keep the widget packages as they are."));

        assertThat(SeedRequirements.verifiableObligationsOrSplit(SEED, file))
                .containsExactly("Every widget route is covered by a test.");
        assertThat(SeedRequirements.answeredByGate(SEED, file, SeedRequirements.GATE_TESTS))
                .containsExactly("The whole widget suite passes.");
        assertThat(SeedRequirements.obligationsOrSplit(SEED, file))
                .as("every other reader still sees the line, without its marker")
                .contains("The whole widget suite passes.");
    }

    @Test
    void anUnknownGateAnswersNothingSoTheLineStaysAnObligation() {
        String file = fileWith(List.of("gate: lint The widget code is tidy."));

        assertThat(SeedRequirements.verifiableObligationsOrSplit(SEED, file))
                .containsExactly("The widget code is tidy.");
        assertThat(SeedRequirements.answeredByGate(SEED, file, SeedRequirements.GATE_TESTS)).isEmpty();
    }
}
