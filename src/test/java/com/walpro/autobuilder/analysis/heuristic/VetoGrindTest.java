package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.strategy.score.NoOpScoreAdjustmentStrategy;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.stuck.Recurrence;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-T, rule-extraction run 2026-10-18_19-33-28: one veto decided 22 of the last 27 candidates over 80 minutes, and
 * nothing counted it as stuck. The timeline here is that run's: started 19:33, the veto from 20:33 about every two
 * minutes, one candidate in five decided otherwise.
 */
class VetoGrindTest {

    private static final long MIN = 60_000L;
    private static final long START = 0L;

    private static final String LOSES = "This mutation takes something off a type that already had it, and puts nothing in"
            + " its place: SpecEndpointBehaviourQuery loses @Slf4j (src/main/java/a/SpecEndpointBehaviourQuery.java);"
            + " SpecEndpointBehaviourQuery loses @Component (src/main/java/a/SpecEndpointBehaviourQuery.java). Nothing"
            + " splices these files together -- the content you emit REPLACES the whole file.";

    private static HeuristicOpinion veto(String rationale) {
        return HeuristicOpinion.of("MergeRegressionHeuristic", 0.9, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, rationale);
    }

    @Test
    void theFindingIsTheSameAcrossCandidatesThatLoseTheSameThing_whateverThePathsAndTheAdvice() {
        String other = LOSES.replace("src/main/java/a/", "src/main/java/b/").replace("REPLACES the whole file.", "is all of it.");

        assertThat(VetoGrind.finding(veto(LOSES))).isEqualTo(VetoGrind.finding(veto(other)))
                .startsWith("MergeRegressionHeuristic: This mutation takes something off")
                .contains("SpecEndpointBehaviourQuery loses @Component").doesNotContain("src/main").doesNotContain("splices");
    }

    @Test
    void aVetoThatKeepsDecidingIsAGrind_byTheRunsOwnRatios_andThenItYields() {
        VetoGrind grind = new VetoGrind(new Recurrence(START));
        String finding = VetoGrind.finding(veto(LOSES));
        for (int minute = 0; minute < 60; minute += 3) grind.decided("c", null, START + minute * MIN);   // the hour before
        int n = 0;
        boolean yieldedAt70 = false;
        int yieldedFrom = -1;
        for (int minute = 60; minute <= 120; minute += 2, n++) {
            grind.decided("c", n % 5 == 4 ? null : finding, START + minute * MIN);
            if (minute == 70) yieldedAt70 = grind.yielded(finding);
            if (yieldedFrom < 0 && grind.yielded(finding)) yieldedFrom = minute;
        }

        assertThat(yieldedAt70).as("ten minutes of it is not yet a quarter of the run").isFalse();
        assertThat(yieldedFrom).as("a quarter of the run since it began: minute 80 of 120").isBetween(80, 84);
    }

    /** Once yielded, the finding abstains: the evaluator's verdict stands; another finding still decides. */
    @Test
    void aYieldedVetoAbstains_theEvaluatorsVerdictStands() {
        VetoGrind grind = new VetoGrind(new Recurrence(START));
        String finding = VetoGrind.finding(veto(LOSES));
        for (int minute = 0; minute <= 40; minute += 2) grind.decided("c", finding, START + minute * MIN);
        assertThat(grind.yielded(finding)).isTrue();
        HeuristicNegotiator negotiator = new HeuristicNegotiator(new NoOpScoreAdjustmentStrategy());
        negotiator.setVetoGrind(grind);
        EvaluationOutcome accepted = new EvaluationOutcome(0.8, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "ok", null);
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();

        NegotiationResult alone = negotiator.negotiate(ctx, accepted, List.of(new HeuristicNegotiatorTest.StaticHeuristic(veto(LOSES))));

        assertThat(alone.outcome().mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(alone.outcome().feedbackMessage()).doesNotContain("CRITICAL");
        EvaluationOutcome rejected = new EvaluationOutcome(0.3, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "no", null);
        assertThat(negotiator.negotiate(ctx, rejected, List.of(new HeuristicNegotiatorTest.StaticHeuristic(veto(LOSES))))
                .outcome().mutationState()).as("the evaluator's own rejection stands too").isEqualTo(MutationAcceptanceState.REJECTED);

        NegotiationResult otherVeto = negotiator.negotiate(ctx, accepted, List.of(new HeuristicNegotiatorTest.StaticHeuristic(
                veto("A different finding entirely: Quill loses @Service"))));
        assertThat(otherVeto.outcome().mutationState()).as("a finding not yet a grind still decides")
                .isEqualTo(MutationAcceptanceState.REJECTED);
    }
}
