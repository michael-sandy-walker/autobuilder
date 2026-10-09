package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-BX: tests for {@link EvaluationAgent#collectRejectionReasons} — which authored reasons get relayed. */
class EvaluationAgentRejectionReasonsTest {

    private static EvaluationOutcome rejected(String feedbackMessage) {
        return new EvaluationOutcome(0.3, MutationAcceptanceState.REJECTED,
                GoalProgressState.NOT_EVALUATED, feedbackMessage, null);
    }

    private static HeuristicOpinion opinion(String name, MutationAcceptanceState state,
                                            GoalProgressState progress, String rationale) {
        return HeuristicOpinion.of(name, 0.8, state, progress, rationale);
    }

    @Test
    void includesFeedbackMessageAndDissentingRationales() {
        NegotiationResult nr = new NegotiationResult(
                rejected("The rule is not implemented."),
                List.of(),
                List.of(
                        opinion("TestCoherenceHeuristic", MutationAcceptanceState.REJECTED,
                                GoalProgressState.STAGNATING, "Test files changed but implementation is absent."),
                        opinion("ScoreRegressionHeuristic", MutationAcceptanceState.NOT_EVALUATED,
                                GoalProgressState.STAGNATING, "Score dropped versus the subgoal baseline.")));

        List<String> reasons = EvaluationAgent.collectRejectionReasons(rejected("The rule is not implemented."), nr);

        assertThat(reasons).containsExactly(
                "The rule is not implemented.",
                "TestCoherenceHeuristic: Test files changed but implementation is absent.",
                "ScoreRegressionHeuristic: Score dropped versus the subgoal baseline.");
    }

    @Test
    void excludesApprovingOpinionsAndNoConcernsRationale() {
        NegotiationResult nr = new NegotiationResult(
                rejected("Rejected."),
                List.of(),
                List.of(
                        opinion("BuildFixAcceptanceHeuristic", MutationAcceptanceState.ACCEPTED,
                                GoalProgressState.PROGRESSING, "Build-config fix — defer to post-merge gate."),
                        opinion("QuietHeuristic", MutationAcceptanceState.REJECTED,
                                GoalProgressState.NOT_EVALUATED, "No concerns identified.")));

        List<String> reasons = EvaluationAgent.collectRejectionReasons(rejected("Rejected."), nr);

        assertThat(reasons).containsExactly("Rejected.");
    }

    @Test
    void emptyWhenNoAuthoredReasons() {
        NegotiationResult nr = new NegotiationResult(rejected(null), List.of(), List.of());
        assertThat(EvaluationAgent.collectRejectionReasons(rejected(null), nr)).isEmpty();
        assertThat(EvaluationAgent.collectRejectionReasons(rejected("  "), null)).isEmpty();
    }

    @Test
    void deduplicatesIdenticalRationaleLines() {
        NegotiationResult nr = new NegotiationResult(
                rejected(null),
                List.of(),
                List.of(
                        opinion("H", MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "same"),
                        opinion("H", MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "same")));

        assertThat(EvaluationAgent.collectRejectionReasons(rejected(null), nr))
                .containsExactly("H: same");
    }

    /**
     * T6-T, field52 run 1: ScoreRegressionHeuristic agreed with the evaluator at CRITICAL and its line
     * replaced the outcome's message, so evolution heard "Severe score drop" nine times and never the
     * two bugs the evaluator had named. The evaluator's diagnosis and recommendation travel first.
     */
    @Test
    void theEvaluatorsDiagnosisSurvivesAHeuristicThatOverrulesTheMessage() {
        EvaluationOutcome evaluator = new EvaluationOutcome(0.42, MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING,
                "promptBlock() does not filter to the signatures that recurred in the latest cycle.",
                "Filter promptBlock() to the latest cycle; build the signature with the simple class name.");
        EvaluationOutcome overruled = rejected("CRITICAL: Heuristic 'ScoreRegressionHeuristic' overruled (0.95): Severe score drop");
        NegotiationResult nr = new NegotiationResult(overruled, List.of(), List.of(
                opinion("ScoreRegressionHeuristic", MutationAcceptanceState.REJECTED,
                        GoalProgressState.STAGNATING, "Severe score drop")));

        assertThat(EvaluationAgent.collectRejectionReasons(evaluator, overruled, nr)).containsExactly(
                "The evaluator found: promptBlock() does not filter to the signatures that recurred in the latest cycle.",
                "The evaluator recommends: Filter promptBlock() to the latest cycle; build the signature with the simple class name.",
                "CRITICAL: Heuristic 'ScoreRegressionHeuristic' overruled (0.95): Severe score drop",
                "ScoreRegressionHeuristic: Severe score drop");
    }

    @Test
    void theEvaluatorsOwnMessageIsNotRelayedTwice() {
        EvaluationOutcome evaluator = new EvaluationOutcome(0.3, MutationAcceptanceState.REJECTED,
                GoalProgressState.NOT_EVALUATED, "The rule is not implemented.", null);
        NegotiationResult nr = new NegotiationResult(evaluator, List.of(), List.of());

        assertThat(EvaluationAgent.collectRejectionReasons(evaluator, evaluator, nr))
                .containsExactly("The evaluator found: The rule is not implemented.");
    }
}
