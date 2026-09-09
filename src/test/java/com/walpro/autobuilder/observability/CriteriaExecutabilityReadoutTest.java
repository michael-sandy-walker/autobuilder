package com.walpro.autobuilder.observability;

import com.walpro.autobuilder.goal.GoalEntity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CriteriaExecutabilityReadoutTest {

    private static GoalEntity goal(String... criteria) {
        GoalEntity g = new GoalEntity();
        g.setDescription("a work item");
        Map<String, Object> meta = new HashMap<>();
        if (criteria.length > 0) meta.put("acceptanceCriteria", List.of((Object[]) criteria));
        g.setMetadata(meta);
        return g;
    }

    @Test
    void separatesTheTwoCausesOfAnUnverifiableCriterion() {
        // The distinction the readout exists for: a criterion that addresses no endpoint is an
        // AUTHORING problem, and one that addresses an endpoint but does not parse is a PARSER
        // problem. Conflating them is the mistake this was written after making.
        var counts = CriteriaExecutabilityReadout.over(List.of(
                goal("POST /loans returns HTTP 404 when bookId does not exist"),   // executable
                goal("GET /books/{id} returns HTTP 200 or HTTP 404 if not found"), // endpoint, two codes
                goal("Book is stored in the in-memory repository")));              // no endpoint at all

        assertThat(counts.authored()).isEqualTo(3);
        assertThat(counts.namingEndpoint()).isEqualTo(2);
        assertThat(counts.executable()).isEqualTo(1);
        assertThat(counts.namedButUnparsed()).isEqualTo(1);
    }

    @Test
    void reproducesTheShapeOfTheRunThatWasDarkOnVerification() {
        // Run 2026-07-30_06-28-19 authored structural criteria where its siblings authored
        // behavioural ones, and nothing in the loop said so for its whole length.
        var structural = CriteriaExecutabilityReadout.over(List.of(
                goal("Book is stored in the in-memory repository and retrievable"),
                goal("BookController.java contains @RequestMapping annotation"),
                goal("LoanService.findLoansByMemberId() correctly retrieves loans")));

        assertThat(structural.executable()).isZero();
        assertThat(structural.executableShare()).isZero();
        assertThat(structural.namedButUnparsed()).isZero(); // the shortfall is authoring, not parsing
    }

    @Test
    void countsTheShareTheProbePathWillActuallySee() {
        var counts = CriteriaExecutabilityReadout.over(List.of(
                goal("POST /books returns HTTP 400 when the title is missing",
                        "POST /books returns HTTP 400 when the isbn is malformed"),
                goal("The service is well structured")));

        assertThat(counts.authored()).isEqualTo(3);
        assertThat(counts.executable()).isEqualTo(2);
        assertThat(counts.executableShare()).isEqualTo(2.0 / 3.0);
    }

    @Test
    void holdsNoOpinionWithoutCriteria() {
        assertThat(CriteriaExecutabilityReadout.over(null).isEmpty()).isTrue();
        assertThat(CriteriaExecutabilityReadout.over(List.of()).isEmpty()).isTrue();
        assertThat(CriteriaExecutabilityReadout.over(List.of(goal())).isEmpty()).isTrue();
        assertThat(CriteriaExecutabilityReadout.over(List.of(new GoalEntity())).isEmpty()).isTrue();
    }

    /**
     * T1-FW. The readout's own javadoc promised "the figure the probe path will actually see", and
     * for this one shape it did not deliver: a requirement naming an input PATTERN parses cleanly
     * and is then abstained on by the synthesizer, so counting it as executable reported an upper
     * bound under the name of an observation. It is now counted under its own cause, because the
     * shortfall is neither the authoring nor the parser -- it is a missing capability (T1-FV), and
     * folding it into namedButUnparsed would blame the wording for it.
     */
    @Test
    void aCriterionNamingAnInputNothingCanGroundIsNotCountedAsExecutable() {
        var counts = CriteriaExecutabilityReadout.over(List.of(
                goal("GET /loans?memberId={id} returns HTTP 200 with the member's loans"), // ungroundable
                goal("GET /loans?memberId=1 returns HTTP 200 with the member's loans"),    // value: fine
                goal("POST /books returns HTTP 400 when the title is missing")));          // plain: fine

        assertThat(counts.authored()).isEqualTo(3);
        assertThat(counts.namingEndpoint()).isEqualTo(3);
        assertThat(counts.executable()).isEqualTo(2);
        assertThat(counts.ungroundableInput()).isEqualTo(1);
        // Not blamed on the wording: the criterion parses, and it names its endpoint.
        assertThat(counts.namedButUnparsed()).isZero();
    }

    @Test
    void usesTheParserRatherThanASecondCopyOfItsRules() {
        // "returns 404" with no HTTP marker parses since T4-DB widened the pattern; a readout
        // carrying its own regex would drift from what the probe path sees.
        assertThat(CriteriaExecutabilityReadout.over(List.of(
                goal("POST /loans returns 404 Not Found when book or member does not exist")))
                .executable()).isEqualTo(1);
    }

    /** A goal whose criteria carry the T3-CC binding stamp; 1-based, as the writer stamps it. */
    private static GoalEntity stampedGoal(Map<String, String> binding, String... criteria) {
        GoalEntity g = goal(criteria);
        Map<String, Object> meta = new HashMap<>(g.getMetadata());
        meta.put("criteriaBinding", binding);
        g.setMetadata(meta);
        return g;
    }

    @Test
    void countsElaborationSeparatelyFromWhatTheSpecificationAsked() {
        // T3-CC slice 2: the readout could not tell a criterion the specification stated from one
        // the loop added, so criteria_executable_pct counted obligations that are never asserted.
        var counts = CriteriaExecutabilityReadout.over(List.of(
                stampedGoal(Map.of("2", "AUTHORED"),
                        "GET /loans?memberId=1 returns HTTP 200 with the member's loans", // spec
                        "GET /loans?memberId=1 returns HTTP 404 for an unknown member")));  // ours

        assertThat(counts.authored()).as("both are declared").isEqualTo(2);
        assertThat(counts.elaboration()).as("one of them is ours").isEqualTo(1);
    }

    @Test
    void theExecutableShareKeepsEveryDeclaredCriterionInItsDenominator() {
        // Deliberate: criteria_executable_pct already changed meaning on 2026-09-08 under T1-FW,
        // and moving it again immediately would leave no window in which run rows compare. The
        // elaboration count is emitted beside it so the specification-only share is derivable.
        var counts = CriteriaExecutabilityReadout.over(List.of(
                stampedGoal(Map.of("2", "AUTHORED"),
                        "GET /loans?memberId=1 returns HTTP 200 with the member's loans",
                        "GET /loans?memberId=1 returns HTTP 404 for an unknown member")));

        assertThat(counts.executable()).isEqualTo(2);
        assertThat(counts.executableShare()).isEqualTo(1.0);
        // and the reader can still get the specification-only view from the addend
        assertThat(counts.authored() - counts.elaboration()).isEqualTo(1);
    }

    @Test
    void anUnstampedGoalReportsNoElaboration() {
        // Absence of a stamp means SPEC — the safe direction, and the common one for pre-gate
        // goals, abstained classifications and runs with grounding disabled.
        assertThat(CriteriaExecutabilityReadout.over(List.of(
                goal("POST /books returns HTTP 400 when the title is missing")))
                .elaboration()).isZero();
    }
}
