package com.walpro.autobuilder.graph.evolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.core.Graph;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EvolutionGraphFacadeTest {

    @Test
    void recordEvolution_shouldCreateFileNodes_forChangedFiles() {
        EvolutionGraphFacade facade = new EvolutionGraphFacade();

        CycleContextData ctx = Mockito.mock(CycleContextData.class);
        ProjectDiffResult diff = Mockito.mock(ProjectDiffResult.class);
        Mockito.when(diff.changedFiles()).thenReturn(Map.of(
                "src/main/java/A.java", "",
                "src/main/java/B.java", ""
        ));
        Mockito.when(ctx.projectDiff()).thenReturn(diff);

        EvolutionOutcome outcome = Mockito.mock(EvolutionOutcome.class);
        Mockito.when(outcome.summary()).thenReturn("Changed A and B");

        facade.recordEvolution(ctx, outcome);

        Graph g = facade.getInternalGraph();
        assertThat(g.getAllNodes().stream().map(n -> n.id()).toList())
                .contains("src/main/java/A.java", "src/main/java/B.java", "Changed A and B");
        assertThat(g.getEdges()).isNotEmpty();
    }
}
