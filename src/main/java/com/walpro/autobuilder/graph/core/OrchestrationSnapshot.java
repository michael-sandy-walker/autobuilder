package com.walpro.autobuilder.graph.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record OrchestrationSnapshot(
        String goalId,
        String subGoalId,

        // attempt evidence
        String attemptId,
        String attemptCycleId,
        Integer changedFilesCount,
        Integer deletedFilesCount,
        String fingerprint,

        // evolution evidence
        ActionEvidence lastEvolution,

        // evaluation evidence
        ActionEvidence lastEvaluation,
        Double evalScoreNow,
        Double evalScoreBefore,
        Double evalDelta,
        String evalContentNow,

        // merge evidence
        ActionEvidence lastMerge,
        Boolean mergedViaGraph,

        // scope assessment evidence
        ActionEvidence lastScopeAssessment,

        // recent action ledger
        List<ActionEvidence> recentActions
) {
    public record ActionEvidence(
            String id,
            String type,
            String status,
            String cycleId,
            String attemptId,
            String ts,
            Map<String, Object> payload
    ) {}

    public static OrchestrationSnapshot empty(String goalId, String subGoalId, String attemptId) {
        return new OrchestrationSnapshot(
                goalId, subGoalId,
                attemptId, null, null, null, null,
                null,
                null, null, null, null, null,
                null, null,
                null,
                List.of()
        );
    }

    @SuppressWarnings("unchecked")
    public static OrchestrationSnapshot fromRow(String goalId, String subGoalId, Map<String, Object> row) {
        String attemptId = (String) row.get("attemptId");
        String attemptCycleId = (String) row.get("attemptCycleId");
        Integer changedFilesCount = toInt(row.get("changedFilesCount"));
        Integer deletedFilesCount = toInt(row.get("deletedFilesCount"));
        String fingerprint = (String) row.get("fingerprint");

        ActionEvidence evol = null;
        if (row.get("evoActionId") != null) {
            evol = new ActionEvidence(
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

        ActionEvidence eval = null;
        if (row.get("evalActionId") != null) {
            eval = new ActionEvidence(
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

        ActionEvidence merge = null;
        if (row.get("mergeActionId") != null) {
            merge = new ActionEvidence(
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

        ActionEvidence scope = null;
        if (row.get("scopeActionId") != null) {
            Map<String, Object> scopePayload = parseJsonMap(row.get("scopePayload"));
            scope = new ActionEvidence(
                    (String) row.get("scopeActionId"),
                    "scope",
                    (String) row.get("scopeStatus"),
                    null,
                    attemptId,
                    (String) row.get("scopeTs"),
                    scopePayload
            );
        }

        List<ActionEvidence> recents = new ArrayList<>();
        Object ra = row.get("recentActions");
        if (ra instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> mm = (Map<String, Object>) m;
                recents.add(new ActionEvidence(
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
                recents
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
