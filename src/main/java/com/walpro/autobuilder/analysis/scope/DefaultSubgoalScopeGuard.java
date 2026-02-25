package com.walpro.autobuilder.analysis.scope;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Prevents committing goal-progress drift when the produced diff is out-of-scope for the current subgoal.
 *
 * Conservative behavior:
 * - only acts when EVOLUTION occurred and projectDiff exists
 * - only blocks progress application when OUT_OF_SCOPE confidence is high
 * - does not auto-switch subgoals (planner remains the reconciler)
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultSubgoalScopeGuard implements SubgoalScopeGuard {

    private static final double OUT_OF_SCOPE_THRESHOLD = 0.80;

    private final SubgoalScopeJudge judge;
    private final GoalPlannerService goalPlannerService;

    @Override
    public CycleContextData guard(CycleContextData ctx) {
        if (ctx == null) return null;

        // Make verdict strictly per-cycle (avoid stale carryover).
        ctx = ctx
                .withoutSignal(BasicSignals.SUBGOAL_SCOPE_MISMATCH)
                .withoutSignal(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE)
                .withBestMatchSubGoalId(null);

        // Only meaningful if we have a subgoal and a diff.
        if (ctx.currentSubGoalId() == null || ctx.currentSubGoalId().isBlank()) return ctx;

        ProjectDiffResult diff = ctx.projectDiff();
        if (diff == null) return ctx;

        // Only when something actually changed (avoid blocking on no-op cycles).
        if (!ctx.hasSignal(BasicSignals.EVOLUTION_OCCURRED)) return ctx;

        boolean noChanges =
                (diff.changedFiles() == null || diff.changedFiles().isEmpty())
                        && (diff.deletedPaths() == null || diff.deletedPaths().isEmpty());
        if (noChanges) {
            return ctx;
        }

        ScopeAssessment assessment;
        try {
            assessment = judge.assess(ctx, goalPlannerService.getRemainingGoals());
        } catch (Exception e) {
            // fail open
            log.warn("Scope guard failed open: {}", e.getMessage());
            return ctx;
        }

        if (assessment == null) return ctx;

        if (assessment.fit() != ScopeFit.OUT_OF_SCOPE || assessment.confidence() < OUT_OF_SCOPE_THRESHOLD) {
            return ctx;
        }

        String best = assessment.bestMatchingSubGoalId();

        log.info(
                "Subgoal scope mismatch: OUT_OF_SCOPE(conf={}) subGoalId={} bestMatch={} msg={}",
                assessment.confidence(), ctx.currentSubGoalId(), best, assessment.message()
        );

        // If no usable best match, keep mismatch signal (unresolved) and request replan.
        if (best == null || best.isBlank() || best.equals(ctx.currentSubGoalId())) {
            return ctx.withSignal(Signal.of(BasicSignals.SUBGOAL_SCOPE_MISMATCH, 1.0, 0.0, "subgoal-scope-guard"))
                    .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.05, "subgoal-scope-guard"));
        }

//        boolean switched = goalPlannerService.activateSubGoal(best, "scope-guard: " + assessment.message());
//        if (!switched) {
//            return ctx.withSignal(Signal.of(BasicSignals.SUBGOAL_SCOPE_MISMATCH, 1.0, 0.10, "subgoal-scope-guard"))
//                    .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.05, "subgoal-scope-guard"));
//        }

//        // make the SAME cycle coherent (downstream will see new scope id + description), do NOT emit the mismatch blocker signal.
//        GoalEntity g = goalPlannerService.getGoalById(best);
//        String desc = (g != null) ? g.getDescription() : null;

        return ctx
//                .withCurrentSubGoalId(best)
//                .withCurrentSubGoalDescription(desc)
                .withBestMatchSubGoalId(best)
                .withSignal(Signal.of(BasicSignals.SCOPE_VERDICT_OUT_OF_SCOPE, 1.0, 0.0, "subgoal-scope-guard"));


    }
}