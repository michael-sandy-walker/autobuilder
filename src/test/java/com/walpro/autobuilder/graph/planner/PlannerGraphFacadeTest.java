package com.walpro.autobuilder.graph.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.core.Graph;
import com.walpro.autobuilder.graph.core.GraphNode;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlannerGraphFacadeTest {

    @Test
    void recordPlanning_shouldCreatePlannerNode_forCurrentSubGoalDescription() {
        PlannerGraphFacade facade = new PlannerGraphFacade();

        CycleContextData ctx = Mockito.mock(CycleContextData.class);
        Mockito.when(ctx.currentSubGoalDescription()).thenReturn("Implement goal dedupe");
        Mockito.when(ctx.currentGoalDescription()).thenReturn("Improve planning quality");

        PlannerOutcome outcome = new PlannerOutcome(true, java.util.List.of("g1", "g2"), "test");

        facade.recordPlanning(ctx, outcome);

        Graph g = facade.getInternalGraph();
        assertThat(g.getAllNodes()).extracting(GraphNode::id)
                .contains("Implement goal dedupe", "Improve planning quality");
        assertThat(g.getEdges()).isNotEmpty();
    }
}
