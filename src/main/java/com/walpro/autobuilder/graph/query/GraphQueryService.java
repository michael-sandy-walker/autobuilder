package com.walpro.autobuilder.graph.query;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotFormatter;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import com.walpro.autobuilder.graph.diagnostic.DiagnosticScanFormatter;
import com.walpro.autobuilder.graph.diagnostic.DiagnosticScanResult;
import com.walpro.autobuilder.graph.diagnostic.DiagnosticScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GraphQueryService {

    private static final int MAX_LIMIT = 30;

    private final OrchestrationSnapshotService orchestrationSnapshotService;
    private final DiagnosticScanService diagnosticScanService;

    public boolean canQuery(CycleContextData ctx) {
        if (ctx == null) return false;

        boolean stuck =
                ctx.hasSignal(BasicSignals.STAGNATION)
                        || ctx.hasStrongSignal(BasicSignals.LOW_CONFIDENCE, 0.20)
                        || ctx.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.20)
                        || ctx.hasStrongSignal(BasicSignals.GOAL_EXHAUSTED, 0.50);

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

        String block = OrchestrationSnapshotFormatter.toLLMBlock(snap, limit);
        return new GraphQueryResult(
                GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.ORCH_SNAPSHOT, limit),
                block,
                null
        );
    }


    private GraphQueryResult diagnosticScan(CycleContextData ctx, int limit) {
        if (ctx == null) return GraphQueryResult.empty(GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.DIAGNOSTIC_SCAN, limit), "error=context_null");

        String attemptId = ctx.parentAttemptId(); // TODO: fix this for making lastAttemptId retrievable
        if (attemptId == null || attemptId.isBlank()) {
            return GraphQueryResult.empty(GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.DIAGNOSTIC_SCAN, limit), "error=missing_attemptId");
        }

        DiagnosticScanResult r = diagnosticScanService.scan(
                ctx.currentGoalId(),
                ctx.currentSubGoalId(),
                attemptId,
                limit
        );

        String block = DiagnosticScanFormatter.toLLMBlock(r);
        return new GraphQueryResult(GraphQueryRequest.of(GraphQueryRequest.GraphQueryType.DIAGNOSTIC_SCAN, limit), block, null);
    }
}