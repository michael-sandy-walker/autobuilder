package com.walpro.autobuilder.agent.evaluation.heuristic;

import com.walpro.autobuilder.agent.evaluation.heuristic.EvaluationHeuristic;
import com.walpro.autobuilder.agent.evaluation.heuristic.HeuristicNegotiator;
import com.walpro.autobuilder.agent.evaluation.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.agent.evaluation.heuristic.strategy.score.NoOpScoreAdjustmentStrategy;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicNegotiatorTest {

    private final HeuristicNegotiator negotiator = new HeuristicNegotiator(new NoOpScoreAdjustmentStrategy());

    private static final EvaluationInput dummyInput = new EvaluationInput(
            0.8, 0.7, Set.of(), Set.of(), "dummyGoal", "dummySubgoal", "1", "2",
            java.util.Map.of(), List.of(), java.util.Map.of(), java.util.Map.of(), Collections.emptyList()
    );
    private static final EvaluationOutcome originalOutcome = new EvaluationOutcome(
            0.8, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "Original OK", null
    );

    static class StaticHeuristic implements EvaluationHeuristic {
        private final HeuristicOpinion opinion;
        StaticHeuristic(HeuristicOpinion opinion) {
            this.opinion = opinion;
        }
        @Override
        public HeuristicOpinion evaluate(EvaluationInput input, EvaluationOutcome outcome) {
            return opinion;
        }
    }

    @Test
    void shouldOverrideOnCritical() {
        List<EvaluationHeuristic> heuristics = List.of(
                new StaticHeuristic(HeuristicOpinion.of("CriticalHeuristic", 0.95, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "Critical reason")),
                new StaticHeuristic(HeuristicOpinion.of("OtherHeuristic", 0.5, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "Mild concern"))
        );

        EvaluationOutcome result = negotiator.negotiate(dummyInput, originalOutcome, heuristics);

        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(result.feedbackMessage()).contains("CriticalHeuristic");
    }

    @Test
    void shouldOverrideOnTwoHighsWithSameState() {
        List<EvaluationHeuristic> heuristics = List.of(
                new StaticHeuristic(HeuristicOpinion.of("High1", 0.8, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "One reason")),
                new StaticHeuristic(HeuristicOpinion.of("High2", 0.78, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "Another reason"))
        );

        EvaluationOutcome result = negotiator.negotiate(dummyInput, originalOutcome, heuristics);

        assertThat(result.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(result.feedbackMessage()).contains("High1");
    }

    @Test
    void shouldFallbackToOriginalWhenNoStrongSignal() {
        List<EvaluationHeuristic> heuristics = List.of(
                new StaticHeuristic(HeuristicOpinion.of("Low1", 0.3, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "Low concern")),
                new StaticHeuristic(HeuristicOpinion.of("Medium1", 0.5, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "Mild suspicion"))
        );

        EvaluationOutcome result = negotiator.negotiate(dummyInput, originalOutcome, heuristics);

        assertThat(result).isEqualTo(originalOutcome);
    }

    @Test
    void shouldNotOverrideWhenHighsConflict() {
        List<EvaluationHeuristic> heuristics = List.of(
                new StaticHeuristic(HeuristicOpinion.of("High1", 0.8, MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, "Wants rejection")),
                new StaticHeuristic(HeuristicOpinion.of("High2", 0.77, MutationAcceptanceState.ACCEPTED, GoalProgressState.PROGRESSING, "Wants acceptance"))
        );

        EvaluationOutcome result = negotiator.negotiate(dummyInput, originalOutcome, heuristics);

        assertThat(result).isEqualTo(originalOutcome);
    }
}
