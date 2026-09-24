package com.walpro.autobuilder.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DK (open half). Root closure verified the banked criteria of surviving subgoals and never the
 * seed, so a requirement whose subgoal was given up or subsumed left the ledger unchecked — which
 * is how run 2026-08-28_05-27-49 completed a flight simulator carrying a placeholder renderer, no
 * HUD and no GL dependency.
 */
class SeedRequirementsTest {

    private static final String FLIGHTSIM_SEED = """
            Create a Java 3D flight simulator with OpenGL.
            The application should include a start menu (including Start, Pause, Exit), the flight
            simulator itself (together with keyboard input for the controls) and a simple cockpit
            HUD, together with a simple (i.e., polygonic), randomly generated environment.
            """;

    @Test
    void theSeedsOwnObligationsAreExtracted() {
        List<String> stated = SeedRequirements.statedIn(FLIGHTSIM_SEED);

        assertThat(stated).isNotEmpty();
        // The requirement the false completion violated must be among them.
        assertThat(stated).anySatisfy(r -> assertThat(r).contains("cockpit").contains("HUD"));
    }

    @Test
    void aTitleLineIsNotARequirement() {
        // "Create a Java 3D flight simulator with OpenGL." names the domain; it places no checkable
        // obligation and would only add noise to the verifier.
        assertThat(SeedRequirements.statedIn(FLIGHTSIM_SEED))
                .noneSatisfy(r -> assertThat(r).isEqualTo("Create a Java 3D flight simulator with OpenGL"));
    }

    @Test
    void metaInstructionsAboutTheExerciseAreDropped() {
        String withMeta = """
                Your task is to build a parser. Do not use external libraries.
                The tool must print one line per malformed row to stdout.
                """;

        assertThat(SeedRequirements.statedIn(withMeta))
                .allSatisfy(r -> assertThat(r).doesNotContain("Your task").doesNotContain("Do not"));
        assertThat(SeedRequirements.statedIn(withMeta)).anySatisfy(r -> assertThat(r).contains("malformed row"));
    }

    @Test
    void requirementsAlreadyCoveredByBankedCriteriaAreNotAskedTwice() {
        // The verifier costs a call and a false block; asking the same question twice buys neither.
        // Each clause of the seed needs its own cover — a criterion that mentions the HUD says
        // nothing about the simulator loop, and treating one as covering both is how a dropped
        // requirement goes unnoticed.
        List<String> banked = List.of(
                "Start menu offering Start, Pause and Exit",
                "The flight simulator loop with keyboard input for the controls",
                "A cockpit HUD displaying altitude and heading",
                "A randomly generated polygonic environment");

        assertThat(SeedRequirements.uncovered(SeedRequirements.statedIn(FLIGHTSIM_SEED), banked, false)).isEmpty();
    }

    @Test
    void anUncoveredRequirementSurvivesTheCoverageCheck() {
        List<String> unrelated = List.of("mvn compile exits 0 with no errors");

        assertThat(SeedRequirements.uncovered(SeedRequirements.statedIn(FLIGHTSIM_SEED), unrelated, false)).isNotEmpty();
    }

    @Test
    void anEmptyOrUnreadableSpecYieldsNoOpinion() {
        // Empty must read as "no requirements recognised", never as "the seed asks for nothing" —
        // the second would make closure vacuous, which is the failure this exists to prevent.
        assertThat(SeedRequirements.statedIn(null)).isEmpty();
        assertThat(SeedRequirements.statedIn("   ")).isEmpty();
        assertThat(SeedRequirements.statedIn("Untitled.")).isEmpty();
    }

    @Test
    void theSetIsBoundedSoTheVerifierIsNotFloodedByALongSpecification() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 40; i++) many.append("The tool must print value ").append(i).append(" to stdout. ");

        assertThat(SeedRequirements.statedIn(many.toString())).hasSizeLessThanOrEqualTo(12);
    }

    // ── T4-DA: the plan-versus-seed comparison ───────────────────────────────

    /**
     * The plan run 2026-08-28_05-27-49 actually ended with — menu, controls, environment. The HUD
     * the seed states plainly was covered by no surviving subgoal, and nothing ever said so.
     */
    @Test
    void aRequirementNoSubgoalCovers_isNamed() {
        List<String> planTheRunEndedWith = List.of(
                "Implement the start menu with Start, Pause and Exit",
                "Implement keyboard input for the flight controls",
                "Generate a randomly generated polygonic environment");

        assertThat(SeedRequirements.uncovered(SeedRequirements.statedIn(FLIGHTSIM_SEED), planTheRunEndedWith, false))
                .anySatisfy(r -> assertThat(r).contains("cockpit").contains("HUD"));
    }

    @Test
    void aBundledSentenceIsSplitAtClauseBoundaries_withoutCuttingParentheses() {
        // "(including Start, Pause, Exit)" is one parenthetical; cutting it produced "a start menu
        // (including Start" as a requirement while losing the HUD entirely.
        List<String> stated = SeedRequirements.statedIn(FLIGHTSIM_SEED);

        assertThat(stated).anySatisfy(r -> assertThat(r).contains("Start, Pause, Exit)"));
        assertThat(stated).noneSatisfy(r -> assertThat(r).endsWith("(including Start"));
    }

    // ── found by running the extractor over the three REAL seeds, post-ship ──

    @Test
    void aSemicolonInsideAParentheticalDoesNotEndTheSentence() {
        // The firestarter seed's own shape: splitting at the interior semicolons spliced list
        // items into dangling-paren fragments that then sat at the closure edge.
        String seed = "Books: POST /books (create; title required; isbn required), "
                + "GET /books/{id} (single; 404 if absent) must be provided.";

        assertThat(SeedRequirements.statedIn(seed)).allSatisfy(r -> {
            long open = r.chars().filter(c -> c == '(').count();
            long close = r.chars().filter(c -> c == ')').count();
            assertThat(open).as("balanced parens in: %s", r).isEqualTo(close);
        });
    }

    @Test
    void aCommaInsideAQuotedFormatStringDoesNotSplitTheClause() {
        String seed = "The tool must print one line per malformed row to stdout as "
                + "\"line <n>: expected <h> fields, found <f>\" for every malformed row.";

        assertThat(SeedRequirements.statedIn(seed))
                .anySatisfy(r -> assertThat(r).contains("expected <h> fields, found <f>"));
        assertThat(SeedRequirements.statedIn(seed))
                .noneSatisfy(r -> assertThat(r).endsWith("expected <h> fields"));
    }

    @Test
    void theClosureEdgeSubsetCarriesOnlyClausesWithTheirOwnObligation() {
        // Two consumers, two risk profiles. The planner readout is advisory and wants recall —
        // "a simple cockpit HUD" belongs there. The closure edge wants precision, because an
        // unconfirmable shard becomes a requirement the verifier can never confirm, and walling
        // is the one failure this class must not have.
        List<String> closure = SeedRequirements.statedVerifiableIn(FLIGHTSIM_SEED);

        assertThat(closure).allSatisfy(r ->
                assertThat(r).matches("(?is).*\\b(should|include|includes|including|provide|provides"
                        + "|support|supports|display|displays|print|prints|return|returns|generate"
                        + "|generates|contain|contains|exit|exits|write|writes|must|shall|accept"
                        + "|accepts|allow|allows|expose|exposes)\\b.*"));
        // And the recall set still carries the HUD for the planner.
        assertThat(SeedRequirements.statedIn(FLIGHTSIM_SEED))
                .anySatisfy(r -> assertThat(r).contains("HUD"));
    }

    @Test
    void tailFragmentsOfOtherClausesAreNotRequirements() {
        String seed = "A quoted field may contain commas and newlines, which are then part of the "
                + "value and NOT separators, in ascending line order.";

        assertThat(SeedRequirements.statedIn(seed)).noneSatisfy(r ->
                assertThat(r.toLowerCase()).matches("(?:which|not|in|where)\\b.*"));
    }

    @Test
    void oneStrayQuoteDoesNotDesyncTheRestOfTheDocument() {
        // Quote state resets per line: a single odd quote upstream silenced every later sentence
        // terminator, gluing several sentences into one "requirement".
        String seed = "An example: \"unterminated\nThe tool must exit 2 when a row is malformed. "
                + "The tool must exit 0 otherwise.";

        assertThat(SeedRequirements.statedIn(seed))
                .anySatisfy(r -> assertThat(r).contains("exit 2"))
                .anySatisfy(r -> assertThat(r).contains("exit 0"));
    }

    /**
     * T6-S. " and " separates two ITEMS but not a compound OBJECT of one verb, and splitting blind
     * cost the operative word: the clitarget seed's "a quoted field may contain commas and
     * newlines" became "a quoted field may contain commas". The closure edge's seed backstop then
     * licensed neither of the two yardstick checks the T4-DK false completion actually failed.
     */
    @Test
    void aCompoundObjectOfOneVerbIsNotSplitIntoTwoRequirements() {
        String spec = "A field MAY be wrapped in double quotes, and a quoted field may contain "
                + "commas and newlines, which are then part of the value and NOT separators.";

        assertThat(SeedRequirements.statedIn(spec))
                .anySatisfy(r -> assertThat(r).contains("commas and newlines"));
        assertThat(SeedRequirements.statedIn(spec))
                .noneSatisfy(r -> assertThat(r).isEqualTo("a quoted field may contain commas"));
    }

    /**
     * The other half of the same rule, and the reason the split exists at all: the flight-simulator
     * seed states five obligations in one sentence, and judged whole, covering any part marks all of
     * it covered — which is how a HUD no subgoal addressed read as satisfied.
     */
    @Test
    void twoDistinctItemsJoinedByAndAreStillSplit() {
        String spec = "The application should include a start menu, the flight simulator itself "
                + "and a simple cockpit HUD and a randomly generated environment.";

        assertThat(SeedRequirements.statedIn(spec))
                .anySatisfy(r -> assertThat(r).contains("cockpit HUD"))
                .anySatisfy(r -> assertThat(r).contains("randomly generated environment"));
    }
}
