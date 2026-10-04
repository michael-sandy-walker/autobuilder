package com.walpro.autobuilder.analysis.pattern;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.scope.ScopeActionSnapshot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Detects scope thrash: N consecutive OUT_OF_SCOPE assessments for the current subgoal.
 * When detected, the subgoal is given up programmatically and the pointer advances to
 * the next active subgoal — no LLM call required.
 *
 * <p>Future extension: if {@code bestMatchingSubGoalId} is consistent across the N
 * assessments, emit a SCOPE_SWITCH_CANDIDATE signal carrying that id so the planner can
 * reorder siblings rather than just advancing linearly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScopeThrashSignalAnalyzer {

    static final int CONSECUTIVE_THRESHOLD = 3;
    // Fetch a few extra to confirm the streak is unbroken at the head
    private static final int QUERY_LIMIT = CONSECUTIVE_THRESHOLD + 2;

    private final AgentGraphService agentGraphService;

    // T5-I: optional so unit tests constructing the analyzer directly need no logger stub.
    @lombok.Setter
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger;

    public CycleContextData analyze(CycleContextData ctx) {
        if (ctx == null) return null;

        String subGoalId = ctx.currentSubGoalId();
        if (subGoalId == null || subGoalId.isBlank()) return ctx;

        List<ScopeActionSnapshot> recent = agentGraphService.findRecentScopeActions(subGoalId, QUERY_LIMIT);
        if (recent.size() < CONSECUTIVE_THRESHOLD) return ctx;

        // Count consecutive OUT_OF_SCOPE at the head (most recent first)
        int consecutiveOutOfScope = 0;
        for (ScopeActionSnapshot snap : recent) {
            if ("OUT_OF_SCOPE".equals(snap.status())) {
                consecutiveOutOfScope++;
            } else {
                break;
            }
        }

        if (consecutiveOutOfScope < CONSECUTIVE_THRESHOLD) return ctx;

        // T5-I: fired-only emission — both routing flavours are the same detection.
        if (behaviourLogger != null) {
            behaviourLogger.patternDetected(ctx.cycleName(), "ScopeThrashSignalAnalyzer",
                    consecutiveOutOfScope + " consecutive OUT_OF_SCOPE assessments for subGoal=" + subGoalId,
                    "SUBGOAL_EXHAUSTED(scope-thrash)");
        }

        log.warn("Scope thrash detected: {} consecutive OUT_OF_SCOPE assessments for subGoal={} — declaring "
                        + "exhaustion; the SUBGOAL_EXHAUSTED handler decides (verify / retry / divide / give up).",
                consecutiveOutOfScope, subGoalId);
        return ctx.issueRequest(ChannelRequest.of(BasicSignals.SUBGOAL_EXHAUSTED, 1.0, "scope-thrash", ChannelRequest.horizonFromDecay(1.0, 0.05)));
    }
}
