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

        assertThat(SeedRequirements.notAlreadyCovered(FLIGHTSIM_SEED, banked)).isEmpty();
    }

    @Test
    void anUncoveredRequirementSurvivesTheCoverageCheck() {
        List<String> unrelated = List.of("mvn compile exits 0 with no errors");

        assertThat(SeedRequirements.notAlreadyCovered(FLIGHTSIM_SEED, unrelated)).isNotEmpty();
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

        assertThat(SeedRequirements.notAlreadyCovered(FLIGHTSIM_SEED, planTheRunEndedWith))
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
}
