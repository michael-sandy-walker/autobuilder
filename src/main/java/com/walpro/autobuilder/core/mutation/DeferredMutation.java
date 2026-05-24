package com.walpro.autobuilder.core.mutation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * T4-BE: persistent record of a mutation that the compile probe rejected, where the rejection
 * was fully explained by extractable {@link MutationBlocker}s. Persisted to the orchestration
 * graph; mutation file bytes are *not* duplicated — {@code mutationDir} references the
 * existing {@code firestarter/mutations/<cycleId>/} tree preserved by T5-BA.
 *
 * @param id                    stable ledger id (UUID)
 * @param attemptId             originating cycle's attempt id
 * @param cycleId               originating cycle id (also the mutation dir name)
 * @param intentSubgoalId       subgoal active when the mutation was proposed; may be null
 * @param intentGoalId          root goal at deferral time
 * @param llmScore              LLM evaluation score if available (probe rejection short-circuits
 *                              the LLM call → typically null); used in restage ranking
 * @param heuristicScore        deterministic heuristic score from {@code ScoringService}
 * @param deferredAtCycleIndex  monotonic cycle counter at deferral; used for TTL eviction
 * @param mutationDir           relative path to the mutation tree under {@code file.base-path}
 * @param state                 current lifecycle state
 * @param touchedFiles          path → contentHash captured at deferral time for drift detection
 * @param blockers              snapshot of blockers (re-derivable from graph; kept here for
 *                              convenience in restage decisions)
 */
public record DeferredMutation(
        String id,
        String attemptId,
        String cycleId,
        String intentSubgoalId,
        String intentGoalId,
        Double llmScore,
        Double heuristicScore,
        long deferredAtCycleIndex,
        Instant deferredAt,
        String mutationDir,
        DeferredMutationState state,
        Map<String, String> touchedFiles,
        List<MutationBlocker> blockers
) {
    /** Ranking score for restage prioritisation. Higher = pick first. */
    public double rank(long cycleClearedAt) {
        // Recency boost: candidates whose blockers just cleared score higher than long-pending ones.
        double recency = 1.0 / (1.0 + Math.max(0, cycleClearedAt - deferredAtCycleIndex));
        return combinedScore() * recency;
    }

    /**
     * Combined score gate for completion-bias re-focus (independent of recency). A null score
     * means "no signal from that source" — treated as neutral (multiplier 1.0) rather than 0.5
     * so the present signal is fully decisive. The common case for the deferral path is
     * {@code llmScore=null} (compile probe short-circuited the LLM call), with the heuristic
     * score (computed by {@code ScoringService} before the probe ran) carrying the verdict.
     */
    public double combinedScore() {
        if (llmScore == null && heuristicScore == null) return 0.5;
        if (llmScore == null) return heuristicScore;
        if (heuristicScore == null) return llmScore;
        return llmScore * heuristicScore;
    }

    public DeferredMutation withState(DeferredMutationState newState) {
        return new DeferredMutation(id, attemptId, cycleId, intentSubgoalId, intentGoalId,
                llmScore, heuristicScore, deferredAtCycleIndex, deferredAt, mutationDir,
                newState, touchedFiles, blockers);
    }
}
