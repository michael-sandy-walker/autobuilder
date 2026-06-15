package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.merge.RolledBackEvidence;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * T1-DT: hard build-block gate. While the codebase carries a {@link BuildResult#COMPILE_ERROR},
 * only build-fix-related roles are eligible for execution.
 *
 * <p>Eligible under COMPILE_ERROR:</p>
 * <ul>
 *   <li>{@code analysis} — always (may produce diagnostic context)</li>
 *   <li>{@code planner} — only when no active BUILD_FIX subgoal exists (injection fallback)</li>
 *   <li>{@code evolution} / {@code merge} / {@code evaluation} — only when the current
 *       subgoal carries {@code metadata[blockerType]=BUILD_FIX} (T1-EA: evaluation
 *       included so the BUILD_FIX cycle completes evolve→evaluate→merge→build atomically)</li>
 * </ul>
 *
 * <p>Only active when {@code autobuilder.build.enabled=true}.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "autobuilder.build", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class BuildBlockGate {

    private final GoalPlannerService goalPlannerService;
    private final BlockerSubgoalInjector blockerSubgoalInjector;

    /** T2-EF: run-level recurring-defect tracker + behaviour logger. Optional so the gate's
     *  existing constructor-based unit tests keep working without them. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RecurringDefectTracker recurringDefectTracker;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger;

    public void setRecurringDefectTracker(RecurringDefectTracker tracker) { this.recurringDefectTracker = tracker; }
    public void setBehaviourLogger(com.walpro.autobuilder.observability.behaviour.BehaviourLogger logger) { this.behaviourLogger = logger; }

    /**
     * Returns {@code true} when the build is effectively broken — either the current cycle's
     * {@code BuildOutcome} reports COMPILE_ERROR, or the previous cycle was rolled back
     * (T1-DZ: the rollback evidence is the cross-cycle signal that the codebase is broken,
     * since T1-DW restores {@code buildOutcome} to the previous OK value on rollback and
     * would otherwise hide the breakage from this gate).
     */
    public boolean isActive(CycleContextData ctx) {
        return effectiveBuildOutcome(ctx) != null;
    }

    /**
     * T1-DZ: returns the BuildOutcome that should drive Chain H decisions for this cycle —
     * either the real {@code ctx.buildOutcome()} when COMPILE_ERROR is freshly observed, or
     * a synthetic outcome reconstructed from {@code lastRollback} evidence (preserving cycleId,
     * stderrSummary, and an implied COMPILE_ERROR result so {@link BlockerSubgoalInjector}'s
     * existing {@link BuildErrorClassifier} flow re-identifies the kind and produces the
     * right BUILD_FIX subgoal description and acceptance criteria).
     *
     * <p>Strictly internal: never written back onto the context, so other readers of
     * {@code ctx.buildOutcome()} (e.g. {@link com.walpro.autobuilder.analysis.heuristic.BuildResultHeuristic})
     * continue to see the real outcome.</p>
     */
    private BuildOutcome effectiveBuildOutcome(CycleContextData ctx) {
        if (ctx == null) return null;
        BuildOutcome current = ctx.buildOutcome();
        if (current != null && current.result() == BuildResult.COMPILE_ERROR) {
            return current;
        }
        RolledBackEvidence ev = ctx.lastRollback();
        if (ev != null) {
            // cycleId from the rolled-back cycle — keeps BuildResultHeuristic.isFromCycle correct
            // (the rollback is from a previous cycle, not the current one).
            return new BuildOutcome(BuildResult.COMPILE_ERROR, ev.stderrSummary(),
                    0L, "<rollback>", ev.cycleId());
        }
        return null;
    }

    /**
     * Returns {@code true} when {@code role} may execute under an active build block.
     * Does not perform any side-effecting injection — call {@link #ensureBuildFixSubgoalExists}
     * separately before agent ranking to guarantee a BUILD_FIX subgoal exists.
     */
    public boolean isEligible(String role, CycleContextData ctx) {
        if (role == null) return false;
        return switch (role.toLowerCase()) {
            case "analysis" -> true;
            case "planner" -> !blockerSubgoalInjector.hasActiveBuildFixSubgoal();
            // T1-EA: evaluation included so a BUILD_FIX cycle can run end-to-end
            // (evolve → evaluate → merge → build) without forfeiting a cycle waiting
            // for the next deliberation pass.
            case "evolution", "merge", "evaluation" -> isCurrentSubgoalBuildFix(ctx);
            default -> false;
        };
    }

    /**
     * T1-DT: if no active BUILD_FIX subgoal exists while the build is broken, inject one now.
     * Called by {@code AdaptiveExecutionEngine} before agent ranking so at least one eligible
     * role (evolution/merge against the new BUILD_FIX subgoal) exists in the same cycle.
     * <p>
     * T1-DZ: source can be the real current {@code BuildOutcome.COMPILE_ERROR} OR a synthetic
     * outcome reconstructed from {@code lastRollback} evidence — see {@link #effectiveBuildOutcome}.
     * </p>
     */
    public void ensureBuildFixSubgoalExists(CycleContextData ctx) {
        BuildOutcome effective = effectiveBuildOutcome(ctx);
        if (effective == null) return;
        if (blockerSubgoalInjector.hasActiveBuildFixSubgoal()) return;

        boolean fromRollback = ctx.buildOutcome() == null
                || ctx.buildOutcome().result() != BuildResult.COMPILE_ERROR;
        log.info("T1-DT/T1-DZ: no BUILD_FIX subgoal exists — injecting from {} signal",
                fromRollback ? "rollback evidence" : "current build COMPILE_ERROR");
        // T2-CY: thread the rolled-back paths through so the BUILD_FIX subgoal description
        // can name the actual file when stderr (often summarised) lacks a file-coordinate prefix.
        java.util.List<String> contextPaths = (fromRollback && ctx.lastRollback() != null)
                ? ctx.lastRollback().restoredPaths()
                : java.util.List.of();
        blockerSubgoalInjector.injectBuildFixSubgoalIfAbsent(effective, contextPaths,
                ctx.sameFailureStreak(), ctx.sameFailureStreakKey());
    }

    /**
     * T2-EF: drive the recurring-defect redirection off the dominance the
     * {@link RecurringDefectTracker} accumulates from the RESIDUAL probe channel — independent of
     * {@link #effectiveBuildOutcome} (the post-merge/rollback channel T1-EC made dead). The
     * 2026-06-14 run proved the need: detection fired 116× ({@code Member} missing, share 1.0,
     * spanning 7 subgoals) but the forcing wired into {@link #ensureBuildFixSubgoalExists} fired 0×
     * because {@code effectiveBuildOutcome} was null all run (all 25 failures were rejected
     * pre-merge, so the build never went COMPILE_ERROR post-merge and {@code lastRollback} stayed
     * unset). Called every cycle by the engine, alongside {@code ensureBuildFixSubgoalExists}.
     *
     * <p>Skips when the per-subgoal streak (≥2) is already escalating the same class, and when a
     * build-fix subgoal is already in flight — so the redirect (and its event) fires once per
     * dominant defect, not every cycle the dominance holds.
     */
    public void ensureRecurringDefectRedirection(CycleContextData ctx) {
        if (ctx == null || recurringDefectTracker == null) return;
        if (ctx.sameFailureStreak() >= 2) return; // per-subgoal divergence already handles it
        if (blockerSubgoalInjector.hasActiveBuildFixSubgoal()) return; // a fix is already in flight
        var dominantOpt = recurringDefectTracker.dominantDefect();
        if (dominantOpt.isEmpty()) return;
        RecurringDefectTracker.DominantDefect d = dominantOpt.get();
        if (d.missingToken() == null) return; // need a symbol to redirect toward

        log.info("T2-EF: recurring defect dominates the run — {} (share={}, subgoals={}, hadGreenFix={})",
                d.key(), String.format(java.util.Locale.ROOT, "%.2f", d.share()), d.subgoalSpan(), d.hadGreenFix());
        blockerSubgoalInjector.injectRecurringDefectRedirection(
                d.errorKind(), d.missingToken(), d.subgoalSpan(), d.hadGreenFix());
        if (behaviourLogger != null) {
            try {
                behaviourLogger.recurringDefectDominant(ctx.cycleName(), d.errorKind(),
                        d.missingToken(), d.share(), d.subgoalSpan(), d.hadGreenFix());
            } catch (Exception e) {
                log.debug("T2-EF: recurringDefectDominant emit failed: {}", e.getMessage());
            }
        }
    }

    private boolean isCurrentSubgoalBuildFix(CycleContextData ctx) {
        String subGoalId = ctx != null ? ctx.currentSubGoalId() : null;
        if (subGoalId == null || subGoalId.isBlank()) return false;
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (current == null || current.getMetadata() == null) return false;
        return com.walpro.autobuilder.core.build.BlockerTypes.BUILD_FIX
                .equals(current.getMetadata().get(BlockerTypes.METADATA_KEY));
    }
}
