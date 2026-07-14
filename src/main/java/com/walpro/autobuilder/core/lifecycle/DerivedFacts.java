package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.RecurringDefectTracker;
import com.walpro.autobuilder.core.build.TestOnlyCompileFailure;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * T4-BZ-2: read-time predicates over {@link CycleContextData} and the run-level trackers — the
 * derived-view family of the T4-BZ family map. Each predicate replaces a formerly stored+decayed
 * signal; the fact is computed where it is read and can never go stale.
 *
 * Pure context predicates are static; tracker/goal-backed facts go through the component
 * (collaborators optional so plain unit tests can construct it bare, matching the
 * ContextLineageTracker pattern).
 */
@Component
public class DerivedFacts {

    /** T2-EF: run-level recurring-defect tracker; null in pure-unit constructions. */
    @Autowired(required = false)
    private RecurringDefectTracker recurringDefectTracker;

    /** Root-status source for {@link #goalExhausted}; null in pure-unit constructions. */
    @Autowired(required = false)
    private GoalPlannerService goalPlannerService;

    public void setRecurringDefectTracker(RecurringDefectTracker tracker) {
        this.recurringDefectTracker = tracker;
    }

    public void setGoalPlannerService(GoalPlannerService service) {
        this.goalPlannerService = service;
    }

    // T4-BZ-5: the migration bridge is gone — every fact derives from its carrier alone.

    /** T1-DU: the codebase is effectively broken — carried COMPILE_ERROR or a rollback observation. */
    public static boolean buildBroken(CycleContextData ctx) {
        if (ctx == null) return false;
        return (ctx.buildOutcome() != null && ctx.buildOutcome().result() == BuildResult.COMPILE_ERROR)
                || ctx.lastRollback() != null;
    }

    /** T4-BN-3: spec requires tests AND the resolved compile failure is confined to test sources. */
    public static boolean testRepairFocus(CycleContextData ctx) {
        if (ctx == null) return false;
        return TestRunGate.specRequiresTests(ctx.currentGoalDescription())
                && TestOnlyCompileFailure.isTestOnly(ctx);
    }

    /** T2-DD: the consecutive same-failure streak reached the first repeat. */
    public static boolean buildFixLocusStuck(CycleContextData ctx) {
        if (ctx == null) return false;
        return ctx.sameFailureStreak() >= 2 && ctx.sameFailureStreakKey() != null;
    }

    /** T4-BZ-2: evolution wrote files that no merge has landed yet. */
    public static boolean evolutionOccurred(CycleContextData ctx) {
        if (ctx == null) return false;
        return ctx.unmergedEvolutionWork();
    }

    /** T4-BZ-2: this cycle's evolution produced a different fingerprint than the previous attempt. */
    public static boolean evolutionFingerprintChanged(CycleContextData ctx) {
        if (ctx == null) return false;
        return ctx.evolutionFingerprintChangedThisCycle();
    }

    /** Scope guard result: diff matches a DIFFERENT subgoal better than the current one. */
    public static boolean scopeOutOfScope(CycleContextData ctx) {
        if (ctx == null) return false;
        return ctx.scopeGuardResult() == ScopeGuardResult.OUT_OF_SCOPE;
    }

    /** Scope guard result: diff matches neither the current subgoal nor any better candidate. */
    public static boolean subgoalScopeMismatch(CycleContextData ctx) {
        if (ctx == null) return false;
        return ctx.scopeGuardResult() == ScopeGuardResult.MISMATCH;
    }

    /** T2-EF: a failure shape dominates the run's residual failure mix across >=2 subgoals. */
    public boolean recurringDefectDominant() {
        return recurringDefectTracker != null && recurringDefectTracker.dominantDefect().isPresent();
    }

    /**
     * T1-EI: ids of goals permanently re-given-up at the rehabilitation fail-cap. The planner
     * stamps {@code metadata["irreducibleGap"]=true} at the cap decision; this fact reads the
     * durable marker (one home for the decision, computed read).
     */
    public java.util.List<String> irreducibleGoalIds() {
        if (goalPlannerService == null) return java.util.List.of();
        return goalPlannerService.getAllGoals().stream()
                .filter(g -> g.getStatus() == GoalStatus.GIVEN_UP)
                .filter(g -> Boolean.TRUE.equals(g.getMetadata().get("irreducibleGap")))
                .map(GoalEntity::getId)
                .toList();
    }

    /** T1-EI: at least one goal sits permanently at the rehabilitation fail-cap. */
    public boolean irreducibleGap() {
        return !irreducibleGoalIds().isEmpty();
    }

    /**
     * T4-BZ-5: the system-pause toggle — a recorded pause reason (or a stored SYSTEM_PAUSED
     * twin from a recorded state / REST injection, honored until the store is deleted).
     */
    public static boolean systemPaused(CycleContextData ctx) {
        if (ctx == null) return false;
        return ctx.pausedReason() != null;
    }

    /**
     * The loop's terminal fact: a halt-deciding mechanism recorded a goal-exhausted reason on the
     * context — either a detector (convergence stall, runaway guards, ...) or the coordinator
     * observing the root goal COMPLETED/GIVEN_UP at cycle start. One home: the reason field.
     */
    public static boolean goalExhausted(CycleContextData ctx) {
        if (ctx == null) return false;
        return ctx.goalExhaustedReason() != null;
    }
}
