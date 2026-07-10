package com.walpro.autobuilder.analysis.pattern;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalProgressApplicationResult;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.scope.ScopeActionSnapshot;
import com.walpro.autobuilder.model.GoalProgressState;

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
    private final GoalPlannerService goalPlannerService;

    /**
     * T2-EK slice 1: route the thrash result to the SUBGOAL_EXHAUSTED handler instead of
     * applying GIVEN_UP directly. The direct give-up bypassed the rescue ladder that lives at
     * the exhaustion edge (banked-criteria promote, T4-BP output verification + residue
     * re-scope, T2-EI slice-1b retries, T2-EJ division) AND the exhaustion runaway brake never
     * counted this give-up class. Observed 2026-07-02: a scope-thrash give-up of one
     * root-level subgoal (6 unmet criteria, never verified) was the lone blocker that took the
     * root to GIVEN_UP. true = declare exhaustion and let the one give-up home decide;
     * false = previous behaviour (immediate give-up, pointer advance).
     */
    @Value("${autobuilder.goal.scope-thrash-rescue:true}")
    private boolean routeToRescue = true;

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

        if (routeToRescue) {
            log.warn("Scope thrash detected: {} consecutive OUT_OF_SCOPE assessments for subGoal={} — declaring "
                            + "exhaustion; the SUBGOAL_EXHAUSTED handler decides (verify / retry / divide / give up).",
                    consecutiveOutOfScope, subGoalId);
            return ctx.issueRequest(ChannelRequest.of(BasicSignals.SUBGOAL_EXHAUSTED, 1.0, "scope-thrash", ChannelRequest.horizonFromDecay(1.0, 0.05)));
        }

        log.warn("Scope thrash detected: {} consecutive OUT_OF_SCOPE assessments for subGoal={}. Giving up subgoal.",
                consecutiveOutOfScope, subGoalId);

        GoalProgressApplicationResult result = goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.GIVEN_UP,
                "scope-thrash after " + consecutiveOutOfScope + " consecutive out-of-scope assessments");
        String nextSubGoalId = result != null ? result.newSubGoalId() : null;

        log.info("Scope thrash: subGoal={} given up, advancing to nextSubGoalId={}", subGoalId, nextSubGoalId);

        // Update currentSubGoalId in the context so downstream hooks in this same cycle
        // (applyEvaluationProgressIfPresent, applyCompletion) operate on the correct subgoal.
        // Remaining goals and descriptions are re-synced from GoalPlannerService at the start
        // of the next cycle via syncContextFromGoals.
        return ctx.withCurrentSubGoalId(nextSubGoalId);
    }
}
