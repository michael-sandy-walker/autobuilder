package com.walpro.autobuilder.analysis.pattern;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
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

        log.warn("Scope thrash detected: {} consecutive OUT_OF_SCOPE assessments for subGoal={}. Giving up subgoal.",
                consecutiveOutOfScope, subGoalId);

        GoalProgressApplicationResult result = goalPlannerService.applyGoalProgress(subGoalId, GoalProgressState.GIVEN_UP);
        String nextSubGoalId = result != null ? result.newSubGoalId() : null;

        log.info("Scope thrash: subGoal={} given up, advancing to nextSubGoalId={}", subGoalId, nextSubGoalId);

        // Update currentSubGoalId in the context so downstream hooks in this same cycle
        // (applyEvaluationProgressIfPresent, applyCompletion) operate on the correct subgoal.
        // Remaining goals and descriptions are re-synced from GoalPlannerService at the start
        // of the next cycle via syncContextFromGoals.
        return ctx.withCurrentSubGoalId(nextSubGoalId);
    }
}
