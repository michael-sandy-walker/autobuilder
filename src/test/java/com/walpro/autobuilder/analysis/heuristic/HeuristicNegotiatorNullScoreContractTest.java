package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.strategy.score.ScoreAdjustmentStrategy;
import com.walpro.autobuilder.analysis.heuristic.strategy.score.WeightedAverageScoreAdjustmentStrategy;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class HeuristicNegotiatorNullScoreContractTest {

    @Test
    void negotiate_whenOriginalScoreIsNull_preservesNullAndDoesNotAdjust() {
        ScoreAdjustmentStrategy strategy = mock(ScoreAdjustmentStrategy.class);
        HeuristicNegotiator negotiator = new HeuristicNegotiator(strategy);

        EvaluationOutcome original = new EvaluationOutcome(
                null,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.NOT_EVALUATED,
                "msg",
                null
        );

        CycleContextData ctx = mock(CycleContextData.class);

        EvaluationOutcome out = negotiator.negotiate(ctx, original, List.of());

        assertThat(out.score()).isNull();
        assertThat(out.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(out.goalProgress()).isEqualTo(GoalProgressState.NOT_EVALUATED);

        verifyNoInteractions(strategy);
    }
}