package com.walpro.autobuilder.graph.query;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.graph.diagnostic.DiagnosticScanResult;
import com.walpro.autobuilder.graph.diagnostic.DiagnosticScanService;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GraphQueryServiceTest {

    private DiagnosticScanService diagnosticScanService;
    private GraphQueryService service;

    @BeforeEach
    void setUp() {
        OrchestrationSnapshotService snapshotService = mock(OrchestrationSnapshotService.class);
        diagnosticScanService = mock(DiagnosticScanService.class);
        when(diagnosticScanService.scan(any(), any(), any(), anyInt()))
                .thenReturn(DiagnosticScanResult.empty());
        service = new GraphQueryService(snapshotService, diagnosticScanService,
                mock(com.walpro.autobuilder.core.init.RunIdentityService.class));
    }

    @Test
    void diagnosticScan_usesLastAttemptId_notParentAttemptId() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("goal-1")
                .currentSubGoalId("sub-1")
                .lastAttemptId("last-attempt-id")
                .parentAttemptId("parent-attempt-id")
                .build()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.STAGNATION, 1.0, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.1)));

        GraphQueryRequest req = GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.DIAGNOSTIC_SCAN, 10);
        service.execute(req, ctx);

        ArgumentCaptor<String> attemptIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(diagnosticScanService).scan(eq("goal-1"), eq("sub-1"), attemptIdCaptor.capture(), eq(10));
        assertThat(attemptIdCaptor.getValue()).isEqualTo("last-attempt-id");
    }
}
