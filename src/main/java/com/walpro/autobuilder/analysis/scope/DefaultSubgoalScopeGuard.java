package com.walpro.autobuilder.analysis.scope;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.core.lifecycle.ScopeGuardResult;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.scope.ScopeActionRegistrar;
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

    private final SubgoalScopeReviewer reviewer;
    private final GoalPlannerService goalPlannerService;
    private final ScopeActionRegistrar scopeActionRegistrar;

    @Override
    public CycleContextData guard(CycleContextData ctx) {
        if (ctx == null) return null;

        // Make scope assessment strictly per-cycle (avoid stale carryover).
        // T4-BZ-2: the result is a context field; DerivedFacts.scopeOutOfScope/
        // subgoalScopeMismatch read it.
        ctx = ctx
                .withScopeGuardResult(null)
                // bridge hygiene: REST-injected/recorded stored entries are consumed here too
                .withoutSignal(BasicSignals.SUBGOAL_SCOPE_MISMATCH)
                .withoutSignal(BasicSignals.SCOPE_OUT_OF_SCOPE)
                .withBestMatchSubGoalId(null);

        // Only meaningful if we have a subgoal and a diff.
        if (ctx.currentSubGoalId() == null || ctx.currentSubGoalId().isBlank()) return ctx;

        ProjectDiffResult diff = ctx.projectDiff();
        if (diff == null) return ctx;

        // Only when something actually changed (avoid blocking on no-op cycles).
        if (!DerivedFacts.evolutionOccurred(ctx)) return ctx;

        boolean noChanges =
                (diff.changedFiles() == null || diff.changedFiles().isEmpty())
                        && (diff.deletedPaths() == null || diff.deletedPaths().isEmpty());
        if (noChanges) {
            return ctx;
        }

        ScopeAssessment assessment;
        try {
            assessment = reviewer.assess(ctx, goalPlannerService.getRemainingGoals(), goalPlannerService.getCompletedGoals());
        } catch (Exception e) {
            // fail open
            log.warn("Scope guard failed open: {}", e.getMessage());
            return ctx;
        }

        if (assessment == null) return ctx;

        try {
            scopeActionRegistrar.registerScope(ctx, assessment);
        } catch (Exception e) {
            log.warn("Failed to persist scope assessment: {}", e.getMessage());
        }

        if (assessment.fit() != ScopeFit.OUT_OF_SCOPE || assessment.confidence() < OUT_OF_SCOPE_THRESHOLD) {
            return ctx;
        }

        String best = assessment.bestMatchingSubGoalId();

        log.info(
                "Subgoal scope mismatch: OUT_OF_SCOPE(conf={}) subGoalId={} bestMatch={} msg={}",
                assessment.confidence(), ctx.currentSubGoalId(), best, assessment.message()
        );

        // T2-EM: a structural repair leaf's actuator must not be disarmed while its named gap
        // is still open. The leaf exists precisely to close a deterministically-verified gap;
        // an LLM out-of-scope opinion cannot outrank that open-gap fact — suppressing
        // evaluation/merge here leaves the run unable to close (the structural guard refuses)
        // AND unable to fix (the only fixer is disarmed): run 20's 63-minute spin.
        if (goalPlannerService.isOpenStructuralRepairLeaf(ctx.currentSubGoalId())) {
            log.info("T2-EM: scope-mismatch suppression skipped for structural repair leaf {} — its named gap is still open (the gap is the scope)",
                    ctx.currentSubGoalId());
            return ctx;
        }

        // If no usable best match, record the unresolved mismatch result and request replan.
        if (best == null || best.isBlank() || best.equals(ctx.currentSubGoalId())) {
            return ctx.withScopeGuardResult(ScopeGuardResult.MISMATCH)
                    .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.05, "subgoal-scope-guard"));
        }

        return ctx
                .withBestMatchSubGoalId(best)
                .withScopeGuardResult(ScopeGuardResult.OUT_OF_SCOPE);
    }
}