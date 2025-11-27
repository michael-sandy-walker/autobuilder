package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SequentialCoordinationStrategyTest {

    @Test
    void evaluationCoordinatorGetsPromotedWhenBiasIsHigh() throws Exception {
        SequentialCoordinationStrategy strategy = new SequentialCoordinationStrategy();

        // Manually set a non-zero bias to simulate previous bad runs
        Field biasField = SequentialCoordinationStrategy.class.getDeclaredField("evaluationBias");
        biasField.setAccessible(true);
        biasField.set(strategy, 0.3);

        AgentCoordinator eval = mock(AgentCoordinator.class);
        when(eval.role()).thenReturn("evaluation");
        when(eval.priority(any(CycleContextData.class))).thenReturn(1.0);

        AgentCoordinator other = mock(AgentCoordinator.class);
        when(other.role()).thenReturn("evolution");
        when(other.priority(any(CycleContextData.class))).thenReturn(1.0);

        CycleContextData ctx = mock(CycleContextData.class);

        // With same base priority, evaluationBias should make EVALUATION come first
        List<AgentCoordinator> ordered =
                strategy.determineExecutionOrder(List.of(other, eval), ctx);

        assertEquals(eval, ordered.get(0), "Evaluation coordinator should be scheduled first");
        assertEquals(other, ordered.get(1));
    }

    @Test
    void adaptAfterCycleRaisesBiasOnRegressionOrLowScore() {
        SequentialCoordinationStrategy strategy = new SequentialCoordinationStrategy();

        CycleContextData ctx = mock(CycleContextData.class);
        EvaluationOutcome outcome = mock(EvaluationOutcome.class);

        // Current score worse than last → bias should go up
        when(ctx.evaluationOutcome()).thenReturn(outcome);
        when(outcome.score()).thenReturn(0.4); // below 0.5 threshold
        when(ctx.lastScore()).thenReturn(0.8);  // delta = -0.4

        ArgumentCaptor<Double> biasCaptor = ArgumentCaptor.forClass(Double.class);
        when(ctx.withEvaluationBias(anyDouble())).thenReturn(ctx);

        CycleContextData result = strategy.adaptAfterCycle(ctx);

        assertSame(ctx, result);
        verify(ctx).withEvaluationBias(biasCaptor.capture());

        // Starting from 0.0, we expect +0.1
        assertEquals(0.1, biasCaptor.getValue(), 1e-6);
    }

    @Test
    void adaptAfterCycleLowersBiasOnConsistentImprovement() throws Exception {
        SequentialCoordinationStrategy strategy = new SequentialCoordinationStrategy();

        // Pretend bias has already been raised earlier
        Field biasField = SequentialCoordinationStrategy.class.getDeclaredField("evaluationBias");
        biasField.setAccessible(true);
        biasField.set(strategy, 0.3);

        CycleContextData ctx = mock(CycleContextData.class);
        EvaluationOutcome outcome = mock(EvaluationOutcome.class);

        // Good improvement: delta > 0.05 and score > 0.6
        when(ctx.evaluationOutcome()).thenReturn(outcome);
        when(outcome.score()).thenReturn(0.8);
        when(ctx.lastScore()).thenReturn(0.7); // delta = +0.1

        ArgumentCaptor<Double> biasCaptor = ArgumentCaptor.forClass(Double.class);
        when(ctx.withEvaluationBias(anyDouble())).thenReturn(ctx);

        strategy.adaptAfterCycle(ctx);

        verify(ctx).withEvaluationBias(biasCaptor.capture());

        // 0.3 - 0.05 = 0.25
        assertEquals(0.25, biasCaptor.getValue(), 1e-6);
    }
}
