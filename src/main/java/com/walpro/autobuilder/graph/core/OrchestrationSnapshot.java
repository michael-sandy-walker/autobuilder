package com.walpro.autobuilder.graph.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public record OrchestrationSnapshot(
        String goalId,
        String subGoalId,

        // attempt observations
        String attemptId,
        String attemptCycleId,
        Integer changedFilesCount,
        Integer deletedFilesCount,
        String fingerprint,

        // evolution observations
        ActionRecord lastEvolution,

        // evaluation observations
        ActionRecord lastEvaluation,
        Double evalScoreNow,
        Double evalScoreBefore,
        Double evalDelta,
        String evalContentNow,

        // merge observations
        ActionRecord lastMerge,
        Boolean mergedViaGraph,

        // scope assessment observations
        ActionRecord lastScopeAssessment,

        // recent action ledger
        List<ActionRecord> recentActions,

        // recurring CRITICAL heuristic patterns across recent evaluations (T2-AW)
        List<HeuristicWarning> recurringCriticalHeuristics,

        // T3-BJ: per-criterion progress for the active subgoal (streaks, ever-passed)
        List<CriterionProgress> criteriaProgress,

        // T3-BP: resolution recorded when the subgoal transitioned to COMPLETED or GIVEN_UP
        String subGoalResolution,

        // T4-BE: deferred-mutation ledger summary (counts + ready candidates with rank info)
        DeferredMutationsSummary deferredMutations
) {
    public record ActionRecord(
            String id,
            String type,
            String status,
            String cycleId,
            String attemptId,
            String ts,
            Map<String, Object> payload
    ) {}

    /**
     * A heuristic that fired at CRITICAL confidence on ≥2 of the last N evaluation artifacts
     * for the current subgoal. Used by {@link OrchestrationSnapshotFormatter} to surface
     * persistent patterns to the meta-deliberation agent.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeuristicWarning(String name, int criticalCount, String lastRationale) {}

    /**
     * T3-BJ: Per-criterion progress for the active subgoal, derived from
     * {@code CriterionResult} nodes (T3-BB) stored by {@link com.walpro.autobuilder.graph.evaluation.EvaluationActionRegistrar}.
     *
     * <ul>
     *   <li>{@code neverAssessed} — no CriterionResult node exists for this criterion yet</li>
     *   <li>{@code lastPassed} — the most recent cycle's assessment was ✓</li>
     *   <li>{@code everPassed} — at least one cycle ever returned ✓ for this criterion</li>
     *   <li>{@code consecutiveFailCount} — how many consecutive cycles from the most recent have returned ✗</li>
     * </ul>
     */
    public record CriterionProgress(
            int criterionIndex,
            String criterionText,
            boolean neverAssessed,
            boolean lastPassed,
            boolean everPassed,
            int consecutiveFailCount
    ) {}

    /**
     * T4-BE: thin view of the deferred-mutation ledger surfaced into the orchestration
     * snapshot so the meta-deliberation agent can reason about pending re-stages without
     * scraping behaviour logs.
     */
    public record DeferredMutationsSummary(
            int openCount,
            int readyCount,
            List<ReadyCandidate> readyCandidates
    ) {
        public static DeferredMutationsSummary empty() {
            return new DeferredMutationsSummary(0, 0, List.of());
        }
    }

    public record ReadyCandidate(
            String id,
            String intentSubgoalId,
            String intentSubgoalDescription,
            double combinedScore,
            int touchedFileCount
    ) {}

    public OrchestrationSnapshot withRecurringCriticalHeuristics(List<HeuristicWarning> warnings) {
        return new OrchestrationSnapshot(
                goalId, subGoalId,
                attemptId, attemptCycleId, changedFilesCount, deletedFilesCount, fingerprint,
                lastEvolution,
                lastEvaluation, evalScoreNow, evalScoreBefore, evalDelta, evalContentNow,
                lastMerge, mergedViaGraph,
                lastScopeAssessment,
                recentActions,
                warnings != null ? warnings : List.of(),
                criteriaProgress != null ? criteriaProgress : List.of(),
                subGoalResolution,
                deferredMutations
        );
    }

    public OrchestrationSnapshot withCriteriaProgress(List<CriterionProgress> progress) {
        return new OrchestrationSnapshot(
                goalId, subGoalId,
                attemptId, attemptCycleId, changedFilesCount, deletedFilesCount, fingerprint,
                lastEvolution,
                lastEvaluation, evalScoreNow, evalScoreBefore, evalDelta, evalContentNow,
                lastMerge, mergedViaGraph,
                lastScopeAssessment,
                recentActions,
                recurringCriticalHeuristics != null ? recurringCriticalHeuristics : List.of(),
                progress != null ? progress : List.of(),
                subGoalResolution,
                deferredMutations
        );
    }

    public OrchestrationSnapshot withSubGoalResolution(String resolution) {
        return new OrchestrationSnapshot(
                goalId, subGoalId,
                attemptId, attemptCycleId, changedFilesCount, deletedFilesCount, fingerprint,
                lastEvolution,
                lastEvaluation, evalScoreNow, evalScoreBefore, evalDelta, evalContentNow,
                lastMerge, mergedViaGraph,
                lastScopeAssessment,
                recentActions,
                recurringCriticalHeuristics != null ? recurringCriticalHeuristics : List.of(),
                criteriaProgress != null ? criteriaProgress : List.of(),
                resolution,
                deferredMutations
        );
    }

    public OrchestrationSnapshot withDeferredMutations(DeferredMutationsSummary summary) {
        return new OrchestrationSnapshot(
                goalId, subGoalId,
                attemptId, attemptCycleId, changedFilesCount, deletedFilesCount, fingerprint,
                lastEvolution,
                lastEvaluation, evalScoreNow, evalScoreBefore, evalDelta, evalContentNow,
                lastMerge, mergedViaGraph,
                lastScopeAssessment,
                recentActions,
                recurringCriticalHeuristics != null ? recurringCriticalHeuristics : List.of(),
                criteriaProgress != null ? criteriaProgress : List.of(),
                subGoalResolution,
                summary != null ? summary : DeferredMutationsSummary.empty()
        );
    }

    public static OrchestrationSnapshot empty(String goalId, String subGoalId, String attemptId) {
        return new OrchestrationSnapshot(
                goalId, subGoalId,
                attemptId, null, null, null, null,
                null,
                null, null, null, null, null,
                null, null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                DeferredMutationsSummary.empty()
        );
    }

    @SuppressWarnings("unchecked")
    public static OrchestrationSnapshot fromRow(String goalId, String subGoalId, Map<String, Object> row) {
        String attemptId = (String) row.get("attemptId");
        String attemptCycleId = (String) row.get("attemptCycleId");
        Integer changedFilesCount = toInt(row.get("changedFilesCount"));
        Integer deletedFilesCount = toInt(row.get("deletedFilesCount"));
        String fingerprint = (String) row.get("fingerprint");

        ActionRecord evol = null;
        if (row.get("evoActionId") != null) {
            evol = new ActionRecord(
                    (String) row.get("evoActionId"),
                    "evolution",
                    (String) row.get("evoStatus"),
                    null,
                    attemptId,
                    (String) row.get("evoTs"),
                    null
            );
        }

        Map<String, Object> evalPayload = parseJsonMap(row.get("evalContentNow"));

        ActionRecord eval = null;
        if (row.get("evalActionId") != null) {
            eval = new ActionRecord(
                    (String) row.get("evalActionId"),
                    "evaluation",
                    (String) row.get("evalStatus"),
                    null,
                    attemptId,
                    (String) row.get("evalTs"),
                    evalPayload
            );
        }

        Double evalScoreNow = toDouble(row.get("evalScoreNow"));
        Double evalScoreBefore = toDouble(row.get("evalScoreBefore"));
        Double evalDelta = toDouble(row.get("evalDelta"));
        String evalContentNow = (String) row.get("evalContentNow");

        ActionRecord merge = null;
        if (row.get("mergeActionId") != null) {
            merge = new ActionRecord(
                    (String) row.get("mergeActionId"),
                    "merge",
                    (String) row.get("mergeStatus"),
                    null,
                    attemptId,
                    (String) row.get("mergeTs"),
                    null
            );
        }

        Boolean mergedViaGraph = (Boolean) row.get("mergedViaGraph");

        ActionRecord scope = null;
        if (row.get("scopeActionId") != null) {
            Map<String, Object> scopePayload = parseJsonMap(row.get("scopePayload"));
            scope = new ActionRecord(
                    (String) row.get("scopeActionId"),
                    "scope",
                    (String) row.get("scopeStatus"),
                    null,
                    attemptId,
                    (String) row.get("scopeTs"),
                    scopePayload
            );
        }

        List<ActionRecord> recents = new ArrayList<>();
        Object ra = row.get("recentActions");
        if (ra instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> mm = (Map<String, Object>) m;
                recents.add(new ActionRecord(
                        Objects.toString(mm.get("id"), null),
                        Objects.toString(mm.get("type"), null),
                        Objects.toString(mm.get("status"), null),
                        Objects.toString(mm.get("cycleId"), null),
                        Objects.toString(mm.get("attemptId"), null),
                        Objects.toString(mm.get("ts"), null),
                        null
                ));
            }
        }

        return new OrchestrationSnapshot(
                goalId, subGoalId,
                attemptId,
                attemptCycleId,
                changedFilesCount,
                deletedFilesCount,
                fingerprint,
                evol,
                eval,
                evalScoreNow,
                evalScoreBefore,
                evalDelta,
                evalContentNow,
                merge,
                mergedViaGraph,
                scope,
                recents,
                List.of(), // recurringCriticalHeuristics: populated via withRecurringCriticalHeuristics
                List.of(), // criteriaProgress: populated via withCriteriaProgress
                null,      // subGoalResolution: populated via withSubGoalResolution
                DeferredMutationsSummary.empty() // T4-BE: populated via withDeferredMutations
        );
    }

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception ignored) { return null; }
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception ignored) { return null; }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonMap(Object v) {
        if (v == null) return null;
        String s = v.toString();
        if (s.isBlank() || s.charAt(0) != '{') return null;
        try {
            return OM.readValue(s, Map.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
