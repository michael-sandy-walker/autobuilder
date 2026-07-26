package com.walpro.autobuilder.graph.query;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotFormatter;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.graph.diagnostic.DiagnosticScanFormatter;
import com.walpro.autobuilder.graph.diagnostic.DiagnosticScanResult;
import com.walpro.autobuilder.graph.diagnostic.DiagnosticScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GraphQueryService {

    private static final int MAX_LIMIT = 30;
    private static final int MAX_SCAN_HISTORY = 5;

    private final OrchestrationSnapshotService orchestrationSnapshotService;
    private final DiagnosticScanService diagnosticScanService;
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentityService;

    private final Deque<DiagnosticScanResult> scanHistory = new ArrayDeque<>();

    public boolean canQuery(CycleContextData ctx) {
        if (ctx == null) return false;

        boolean stuck =
                ctx.hasOpenRequest(BasicSignals.STAGNATION)
                        || ctx.hasStrongRequest(BasicSignals.LOW_CONFIDENCE, 0.20)
                        || ctx.hasStrongRequest(BasicSignals.REPLAN_REQUESTED, 0.20)
                        || DerivedFacts.goalExhausted(ctx);

        return stuck;
    }

    public GraphQueryResult execute(GraphQueryRequest req, CycleContextData ctx) {
        if (req == null || !req.requested()) return GraphQueryResult.empty(GraphQueryRequest.none(), "(no request)");
        if (!canQuery(ctx)) return GraphQueryResult.empty(req, "error=query_not_allowed_in_current_state");

        int limit = Math.max(1, Math.min(req.limit(), MAX_LIMIT));

        return switch (req.type()) {
            case ORCH_SNAPSHOT -> orchSnapshot(ctx, limit);
            case DIAGNOSTIC_SCAN -> diagnosticScan(ctx, limit);
        };
    }

    private GraphQueryResult orchSnapshot(CycleContextData ctx, int limit) {
        if (ctx == null) return GraphQueryResult.empty(GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.ORCH_SNAPSHOT, limit), "error=context_null");

        String attemptId = ctx.lastAttemptId();
        if (attemptId == null || attemptId.isBlank()) {
            return GraphQueryResult.empty(GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.ORCH_SNAPSHOT, limit), "error=missing_attemptId");
        }

        OrchestrationSnapshot snap = orchestrationSnapshotService.fetchSnapshot(
                ctx.currentGoalId(),
                ctx.currentSubGoalId(),
                attemptId,
                limit
        );

        String block = OrchestrationSnapshotFormatter.toLLMBlock(snap, limit,
                runIdentityService != null ? runIdentityService.runId() : null);
        return new GraphQueryResult(
                GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.ORCH_SNAPSHOT, limit),
                block,
                null
        );
    }


    private GraphQueryResult diagnosticScan(CycleContextData ctx, int limit) {
        if (ctx == null) return GraphQueryResult.empty(GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.DIAGNOSTIC_SCAN, limit), "error=context_null");

        String attemptId = ctx.lastAttemptId();
        if (attemptId == null || attemptId.isBlank()) {
            return GraphQueryResult.empty(GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.DIAGNOSTIC_SCAN, limit), "error=missing_attemptId");
        }

        DiagnosticScanResult r = diagnosticScanService.scan(
                ctx.currentGoalId(),
                ctx.currentSubGoalId(),
                attemptId,
                limit
        );

        List<DiagnosticScanResult> history = List.copyOf(scanHistory);
        pushScanHistory(r);

        String block = DiagnosticScanFormatter.toLLMBlock(r, history);
        return new GraphQueryResult(GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.DIAGNOSTIC_SCAN, limit), block, null);
    }

    private void pushScanHistory(DiagnosticScanResult r) {
        if (scanHistory.size() >= MAX_SCAN_HISTORY) {
            scanHistory.pollFirst();
        }
        scanHistory.addLast(r);
    }
}