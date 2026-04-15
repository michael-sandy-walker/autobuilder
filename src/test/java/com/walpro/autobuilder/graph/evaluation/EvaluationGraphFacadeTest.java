package com.walpro.autobuilder.graph.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.Graph;

import com.walpro.autobuilder.model.GoalProgressState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvaluationGraphFacadeTest {

    @Mock private AgentGraphService agentGraphService;

    @Test
    void recordEvaluation_shouldAddNodesAndEdges() {
        EvaluationGraphFacade facade = new EvaluationGraphFacade(agentGraphService);

        CycleContextData ctx = mock(CycleContextData.class);
        when(ctx.currentSubGoalDescription()).thenReturn("Tighten heuristics");

        EvaluationOutcome outcome = mock(EvaluationOutcome.class);
        when(outcome.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);
        when(outcome.mutationState()).thenReturn(com.walpro.autobuilder.model.MutationAcceptanceState.ACCEPTED);

        facade.recordEvaluation(ctx, outcome);

        Graph g = facade.getInternalGraph();
        assertThat(g.getAllNodes()).isNotEmpty();
        assertThat(g.getEdges()).isNotEmpty();
    }

    @Test
    void findLatestEvaluationStatusForSubgoal_delegatesToGraphService() {
        EvaluationGraphFacade facade = new EvaluationGraphFacade(agentGraphService);
        when(agentGraphService.findLatestEvaluationStatusForSubgoal("sub-1")).thenReturn("ACCEPTED");

        assertThat(facade.findLatestEvaluationStatusForSubgoal("sub-1")).isEqualTo("ACCEPTED");
    }
}
