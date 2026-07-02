package com.walpro.autobuilder.graph.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.RolledBackReport;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MergeActionRegistrarTest {

    private AgentGraphService agentGraphService;
    private MergeActionRegistrar registrar;

    @BeforeEach
    void setUp() {
        agentGraphService = mock(AgentGraphService.class);
        registrar = new MergeActionRegistrar(agentGraphService, new ObjectMapper());
    }

    @Test
    void registerMergeRollback_nullEvidence_isNoOp() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-x").currentGoalId("g-x").currentSubGoalId("sg-x").build();

        registrar.registerMergeRollback("agent-x", ctx, null);

        verify(agentGraphService, never()).registerAction(any());
        verify(agentGraphService, never()).linkActionOutput(anyString(), any());
    }

    @Test
    void registerMergeRollback_writesRollbackActionAndPayload() throws Exception {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-x").currentGoalId("g-x").currentSubGoalId("sg-x")
                .lastAttemptId("att-1").parentAttemptId("att-0").build();
        RolledBackReport evidence = new RolledBackReport(
                "cycle-x",
                List.of("src/main/java/Foo.java", "pom.xml"),
                "symbol: class Foo",
                BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);

        registrar.registerMergeRollback("agent-x", ctx, evidence);

        ArgumentCaptor<ActionEntity> actionCaptor = ArgumentCaptor.forClass(ActionEntity.class);
        verify(agentGraphService).registerAction(actionCaptor.capture());
        ActionEntity action = actionCaptor.getValue();
        assertThat(action.type()).isEqualTo("merge_rollback");
        assertThat(action.status()).isEqualTo("ROLLED_BACK");
        assertThat(action.createdByAgentId()).isEqualTo("agent-x");
        assertThat(action.targetsGoalId()).isEqualTo("sg-x");
        assertThat(action.rootGoalId()).isEqualTo("g-x");
        assertThat(action.occurredInCycle()).isEqualTo("cycle-x");

        ArgumentCaptor<ArtifactEntity> artifactCaptor = ArgumentCaptor.forClass(ArtifactEntity.class);
        verify(agentGraphService).linkActionOutput(any(), artifactCaptor.capture());
        ArtifactEntity artifact = artifactCaptor.getValue();
        assertThat(artifact.type()).isEqualTo("merge_rollback_payload");

        JsonNode payload = new ObjectMapper().readTree(artifact.content());
        assertThat(payload.get("cycleId").asText()).isEqualTo("cycle-x");
        assertThat(payload.get("stderrSummary").asText()).isEqualTo("symbol: class Foo");
        assertThat(payload.get("errorKind").asText()).isEqualTo("CANNOT_FIND_SYMBOL");
        assertThat(payload.get("restoredPaths").isArray()).isTrue();
        assertThat(payload.get("restoredPaths").size()).isEqualTo(2);
    }
}
