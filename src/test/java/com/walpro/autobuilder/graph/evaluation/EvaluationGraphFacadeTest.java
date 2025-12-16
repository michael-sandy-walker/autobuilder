package com.walpro.autobuilder.graph.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.core.Graph;

import com.walpro.autobuilder.model.GoalProgressState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EvaluationGraphFacadeTest {

    @Test
    void recordEvaluation_shouldAddNodesAndEdges() {
        EvaluationGraphFacade facade = new EvaluationGraphFacade();

        CycleContextData ctx = Mockito.mock(CycleContextData.class);
        Mockito.when(ctx.currentSubGoalDescription()).thenReturn("Tighten heuristics");

        EvaluationOutcome outcome = Mockito.mock(EvaluationOutcome.class);
        Mockito.when(outcome.score()).thenReturn(0.82);
        Mockito.when(outcome.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        Mockito.when(outcome.mutationState()).thenReturn(com.walpro.autobuilder.model.MutationAcceptanceState.ACCEPTED);

        facade.recordEvaluation(ctx, outcome);

        Graph g = facade.getInternalGraph();
        assertThat(g.getAllNodes()).isNotEmpty();
        assertThat(g.getEdges()).isNotEmpty();
    }
}
