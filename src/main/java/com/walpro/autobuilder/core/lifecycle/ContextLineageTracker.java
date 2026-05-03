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
                .withBuildOutcome(previousContext != null ? previousContext.buildOutcome() : null);
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

    public MergeTrace emptyMergeTrace() {
        return new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>());
    }
}
