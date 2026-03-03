package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.strategy.score.ScoreAdjustmentStrategy;
import com.walpro.autobuilder.analysis.heuristic.strategy.score.WeightedAverageScoreAdjustmentStrategy;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ScoreAdjustmentStrategyTest {

    @Test
    void adjustScore_whenOriginalScoreNull_returnsNull() {
        ScoreAdjustmentStrategy s = new WeightedAverageScoreAdjustmentStrategy();

        EvaluationOutcome original = new EvaluationOutcome(
                null,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.NOT_EVALUATED,
                "x",
                null
        );

        Double adjusted = s.adjustScore(original, List.of());

        assertThat(adjusted).isNull();
    }
}
