package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.model.MergeTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ContextLineageTracker {

    public CycleContextData enrichLineage(CycleContextData baseContext, CycleContextData previousContext) {
        CycleContextData enriched = enrichLineageCore(baseContext, previousContext);
        // T1-DU: emit BUILD_BROKEN whenever the codebase is effectively broken — either the
        // carried buildOutcome is COMPILE_ERROR, OR the previous cycle was rolled back
        // (T1-DZ: T1-DW restores buildOutcome to the previous OK value on rollback, so the
        // rollback evidence is the only cross-cycle signal that the codebase is broken in
        // the bootstrap-no-pom case). decayPerCycle=0.0 — must persist until COMPILE_OK is
        // observed or rollback evidence ages out (single-cycle TTL via lastRollback).
        com.walpro.autobuilder.core.build.BuildOutcome outcome = enriched.buildOutcome();
        boolean buildBroken =
                (outcome != null && outcome.result() == com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR)
                        || enriched.lastRollback() != null;
        if (buildBroken) {
            String source = (outcome != null && outcome.result() == com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR)
                    ? "buildOutcome"
                    : "lastRollback";
            enriched = enriched.withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, source));
        } else {
            enriched = enriched.withoutSignal(BasicSignals.BUILD_BROKEN);
        }
        return enriched;
    }

    private CycleContextData enrichLineageCore(CycleContextData baseContext, CycleContextData previousContext) {
        return baseContext
                // If last cycle merged, clear both fingerprints so pendingFingerprint and
                // notYetEvaluated both resolve to false in the next cycle. Leaving
                // lastEvolutionFingerprint non-null while clearing lastEvaluatedEvolutionFingerprint
                // causes !equals(X, null) = true, which manufactures a spurious EVALUATE cycle
                // (hasCandidate=true, notYetEvaluated=true) even though there is nothing to merge.
                .withLastEvolutionFingerprint(
                        (previousContext != null && previousContext.merged()) || subgoalChanged(baseContext, previousContext)
                                ? null
                                : (previousContext != null ? previousContext.lastEvolutionFingerprint() : null)
                )
                .withLastEvaluatedEvolutionFingerprint(
                        (previousContext != null && previousContext.merged()) || subgoalChanged(baseContext, previousContext)
                                ? null
                                : (previousContext != null ? previousContext.lastEvaluatedEvolutionFingerprint() : null)
                )
                .withLastRecombinationFingerprint(previousContext != null ? previousContext.lastRecombinationFingerprint() : null)
                .withRecentEvolutionFingerprints(previousContext != null ? previousContext.recentEvolutionFingerprints() : List.of())
                .withPreviousAcceptedCycleName(previousContext != null ? previousContext.previousAcceptedCycleName() : null)
                .withParentCycleName(previousContext != null ? previousContext.cycleName() : null)
                .withRecombinationSourceCycleName(
                        previousContext != null && previousContext.hasSignal(BasicSignals.DIVERGENCE_REQUEST)
                                ? (previousContext.recombinationSourceCycleName() != null
                                        ? previousContext.recombinationSourceCycleName()
                                        : previousContext.cycleName())
                                : null
                )
                // T1-BO: reset lastEvolutionOutcome on subgoal change so Rule C
                // (consecutive-no-evolution) counts only within the same subgoal's lifetime.
                .withLastEvolutionOutcome(
                        subgoalChanged(baseContext, previousContext) ? null
                        : (previousContext != null ? previousContext.lastEvolutionOutcome() : null)
                )
                .withLastEvaluationOutcome(previousContext != null ? previousContext.lastEvaluationOutcome() : null)
                .withLastPlannerOutcome(previousContext != null ? previousContext.lastPlannerOutcome() : null)
                .withScoreHistoryByFile(previousContext != null ? previousContext.scoreHistoryByFile() : null)
                .withRecentDeltas(previousContext != null ? previousContext.recentDeltas() : null)
                .withLastAcceptedMutationFileCount(previousContext != null ? previousContext.lastAcceptedMutationFileCount() : 0)
                .withPlateauCycles(previousContext != null ? previousContext.plateauCycles() : 0)
                // T2-AT: reset streaks + warnings when the subgoal changes; otherwise carry forward.
                .withCriticalHeuristicStreaks(resolveStreaks(baseContext, previousContext))
                .withPersistentHeuristicWarnings(resolveWarnings(baseContext, previousContext))
                // T3-BR: carry the evaluator-repeat-retry counter forward only when the subgoal
                // is unchanged AND the previous evolution fingerprint is preserved (i.e. no merge,
                // no subgoal change, no fingerprint reset). The fingerprint reset above already
                // sets lastEvolutionFingerprint to null on those events; mirror that condition here.
                .withEvaluatorRepeatRetries(resolveEvaluatorRepeatRetries(baseContext, previousContext))
                // T4-AN-1: carry the build outcome forward unconditionally — it reflects the
                // codebase's compilation state, which persists across cycles regardless of
                // subgoal changes. MergeAgent overwrites it whenever a new build runs.
                .withBuildOutcome(previousContext != null ? previousContext.buildOutcome() : null)
                // T1-DQ: carry the build-fix attempt counter forward; reset on subgoal change or COMPILE_OK.
                .withConsecutiveBuildFixAttempts(resolveConsecutiveBuildFixAttempts(baseContext, previousContext))
                // T1-DX: carry the rollback evidence forward exactly one cycle so the next
                // cycle's planner / evolution prompts render a --- LAST CYCLE ROLLED BACK ---
                // block. Cleared on the cycle after that — single-cycle TTL prevents the LLM
                // from treating stale rollback info as the present state.
                .withLastRollback(resolveLastRollback(previousContext))
                // T1-DX: rolledBackThisCycle is per-cycle state — never carried forward.
                .withRolledBackThisCycle(false);
    }

    private static int resolveEvaluatorRepeatRetries(CycleContextData baseContext, CycleContextData previousContext) {
        if (previousContext == null) return 0;
        if (subgoalChanged(baseContext, previousContext)) return 0;
        if (previousContext.merged()) return 0;
        return previousContext.evaluatorRepeatRetries();
    }

    private static boolean subgoalChanged(CycleContextData baseContext, CycleContextData previousContext) {
        return previousContext != null
                && !Objects.equals(previousContext.currentSubGoalId(), baseContext.currentSubGoalId());
    }

    private static Map<String, Integer> resolveStreaks(CycleContextData baseContext, CycleContextData previousContext) {
        if (previousContext == null || subgoalChanged(baseContext, previousContext)) {
            return new HashMap<>();
        }
        Map<String, Integer> prev = previousContext.criticalHeuristicStreaks();
        return prev != null ? new HashMap<>(prev) : new HashMap<>();
    }

    private static List<String> resolveWarnings(CycleContextData baseContext, CycleContextData previousContext) {
        if (previousContext == null || subgoalChanged(baseContext, previousContext)) {
            return List.of();
        }
        List<String> prev = previousContext.persistentHeuristicWarnings();
        return prev != null ? prev : List.of();
    }

    /**
     * T1-DX: carry rollback evidence forward exactly one cycle. The previous cycle's
     * {@code rolledBackThisCycle=true} is the only signal we keep — that means the very
     * next cycle (this one) sees the evidence and renders it in prompts. Two cycles after
     * the rollback, {@code previousContext.rolledBackThisCycle()} is false (carry-forward
     * already cleared it), so we drop the evidence.
     */
    private static com.walpro.autobuilder.core.merge.RolledBackEvidence resolveLastRollback(CycleContextData previousContext) {
        if (previousContext == null) return null;
        if (!previousContext.rolledBackThisCycle()) return null;
        return previousContext.lastRollback();
    }

    private static int resolveConsecutiveBuildFixAttempts(CycleContextData baseContext, CycleContextData previousContext) {
        if (previousContext == null) return 0;
        if (subgoalChanged(baseContext, previousContext)) return 0;
        com.walpro.autobuilder.core.build.BuildOutcome prev = previousContext.buildOutcome();
        if (prev != null && prev.result() == com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK) return 0;
        return previousContext.consecutiveBuildFixAttempts();
    }

    public MergeTrace emptyMergeTrace() {
        return new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>());
    }
}
