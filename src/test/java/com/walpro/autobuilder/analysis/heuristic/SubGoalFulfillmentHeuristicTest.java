package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.model.StructuralDiffNode;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubGoalFulfillmentHeuristicTest {

    private final SubGoalFulfillmentHeuristic heuristic = new SubGoalFulfillmentHeuristic();

    private static CycleContextData baseAcceptedProgressing(double score, int plateauCycles) {
        StructuralDiffNode diffNode = new StructuralDiffNode(null, null, List.of());
        ProjectDiffResult diff = new ProjectDiffResult(diffNode, Map.of("Foo.java", "changed"), Set.of());
        EvaluationOutcome evaluation = new EvaluationOutcome(score, null, null, null, null);
        return CycleContextData.builder()
                .currentSubGoalId("sub-1")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .goalProgress(GoalProgressState.PROGRESSING)
                .projectDiff(diff)
                .evaluationOutcome(evaluation)
                .plateauCycles(plateauCycles)
                .build();
    }

    @Test
    void decide_scoreAboveThreshold_fulfilled() {
        var ctx = baseAcceptedProgressing(0.80, 0);
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isTrue();
        assertThat(result.rationale()).contains("score-based closure");
    }

    @Test
    void decide_scoreBelowThreshold_noPlateauYet_notFulfilled() {
        var ctx = baseAcceptedProgressing(0.73, 5);
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isFalse();
        assertThat(result.rationale()).contains("score below closure threshold");
    }

    @Test
    void decide_scoreBelowThreshold_plateauOverrideTriggered_fulfilled() {
        // T1-AQ: after 10+ flat cycles with score >= 0.65, must promote despite score < 0.75
        var ctx = baseAcceptedProgressing(0.7375, 10);
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isTrue();
        assertThat(result.rationale()).contains("plateau-override");
        assertThat(result.rationale()).contains("10 flat cycles");
    }

    @Test
    void decide_scoreBelowOverrideFloor_plateauHigh_notFulfilled() {
        // Even with long plateau, score below 0.65 must not promote
        var ctx = baseAcceptedProgressing(0.60, 15);
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isFalse();
    }

    @Test
    void decide_mutationNotAccepted_notFulfilled() {
        var ctx = baseAcceptedProgressing(0.80, 0)
                .withMutationState(MutationAcceptanceState.REJECTED);
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isFalse();
        assertThat(result.rationale()).contains("mutation not accepted");
    }

    @Test
    void decide_interpreterFulfilled_closesImmediately() {
        var ctx = baseAcceptedProgressing(0.50, 0)
                .withGoalProgress(GoalProgressState.FULFILLED);
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isTrue();
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.rationale()).contains("interpreter=FULFILLED");
    }

    // T3-BA: unmet criterion veto tests

    @Test
    void decide_interpreterFulfilled_suppressed_whenCriterionUnmet() {
        var ctx = baseAcceptedProgressing(0.90, 0)
                .withGoalProgress(GoalProgressState.FULFILLED)
                .withCriteriaAssessments(List.of("[1] ✓ criterion one", "[2] ✗ criterion two — not implemented"));
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isFalse();
        assertThat(result.rationale()).contains("unmet acceptance criterion");
    }

    @Test
    void decide_scoreBasedClosure_suppressed_whenCriterionUnmet() {
        var ctx = baseAcceptedProgressing(0.85, 0)
                .withCriteriaAssessments(List.of("[1] ✗ GET /books — endpoint missing"));
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isFalse();
        assertThat(result.rationale()).contains("unmet acceptance criterion");
    }

    @Test
    void decide_allCriteriaMet_doesNotSuppressFulfillment() {
        var ctx = baseAcceptedProgressing(0.85, 0)
                .withCriteriaAssessments(List.of("[1] ✓ criterion one", "[2] ✓ criterion two"));
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isTrue();
        assertThat(result.rationale()).contains("score-based closure");
    }

    @Test
    void decide_noCriteriaAssessments_doesNotSuppressFulfillment() {
        var ctx = baseAcceptedProgressing(0.85, 0)
                .withCriteriaAssessments(List.of());
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isTrue();
    }

    // T3-BC: missing-criterion safety fallback tests

    @Test
    void decide_llmOmittedOneCriterion_suppressesFulfillment() {
        // 2 criteria defined, LLM assessed only 1 (partial compliance) — implicit fail
        var ctx = baseAcceptedProgressing(0.85, 0)
                .withCurrentSubGoalCriteria(List.of("GET /books returns 200", "Includes all previously created books"))
                .withCriteriaAssessments(List.of("[1] ✓ GET /books returns 200"));
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isFalse();
        assertThat(result.rationale()).contains("unmet acceptance criterion");
    }

    @Test
    void decide_llmOmittedAllCriteria_suppressesFulfillment() {
        // 2 criteria defined, LLM produced no CriteriaCheck lines at all
        var ctx = baseAcceptedProgressing(0.85, 0)
                .withCurrentSubGoalCriteria(List.of("POST /books returns 201", "GET /books returns 200"))
                .withCriteriaAssessments(List.of());
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isFalse();
        assertThat(result.rationale()).contains("unmet acceptance criterion");
    }

    @Test
    void decide_llmOmittedOneCriterion_interpreterFulfilled_suppressesFulfillment() {
        // Same missing-criterion check must also fire on the interpreter=FULFILLED path
        var ctx = baseAcceptedProgressing(0.90, 0)
                .withGoalProgress(GoalProgressState.FULFILLED)
                .withCurrentSubGoalCriteria(List.of("POST /books returns 201", "GET /books returns 200"))
                .withCriteriaAssessments(List.of("[1] ✓ POST /books returns 201"));
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isFalse();
        assertThat(result.rationale()).contains("unmet acceptance criterion");
    }

    @Test
    void decide_allCriteriaAssessed_noCriteriaDefinedMismatch_doesNotSuppressFulfillment() {
        // No definedCriteria on context — assessments list irrelevant for count check
        var ctx = baseAcceptedProgressing(0.85, 0)
                .withCriteriaAssessments(List.of("[1] ✓ criterion one"));
        var result = heuristic.decide(ctx);
        assertThat(result.fulfilled()).isTrue();
    }
}
