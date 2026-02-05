package com.walpro.autobuilder.analysis.problem;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.With;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@With
public record ProblemEntry(
        String id,
        ProblemStatus status,
        ProblemSeverity severity,
        String title,
        String description,
        Set<String> keywords,

        // Scope
        String targetGoalId,
        String targetSubGoalId,

        // Cycle stamps (for “first seen / last seen” traceability)
        String firstSeenCycleId,
        String lastSeenCycleId,

        // Timestamps
        Instant createdAt,
        Instant updatedAt,

        // Evidence (optional, but useful for “pattern recognition” and future summarization)
        List<String> evidenceCycles,
        List<String> evidenceNotes
) {

    public ProblemEntry {
        Objects.requireNonNull(id, "id");
        status = status == null ? ProblemStatus.OPEN : status;
        severity = severity == null ? ProblemSeverity.INFO : severity;
        keywords = keywords == null ? Set.of() : Set.copyOf(keywords);
        evidenceCycles = evidenceCycles == null ? List.of() : List.copyOf(evidenceCycles);
        evidenceNotes = evidenceNotes == null ? List.of() : List.copyOf(evidenceNotes);
    }

    @JsonIgnore
    public boolean isBlocking() {
        return severity == ProblemSeverity.BLOCKER;
    }

    /**
     * Scope rules:
     * - If entry has no goal/subgoal scope, it matches everything.
     * - If it has a goal but no subgoal, it matches any subgoal under that goal.
     * - If it has both, it matches exact goal+subgoal.
     */
    @JsonIgnore
    public boolean matchesScope(String goalId, String subGoalId) {
        boolean hasGoal = targetGoalId != null && !targetGoalId.isBlank();
        boolean hasSub = targetSubGoalId != null && !targetSubGoalId.isBlank();

        if (!hasGoal && !hasSub) return true;

        if (hasGoal && !Objects.equals(safe(goalId), safe(targetGoalId))) return false;

        if (!hasSub) return true;

        return Objects.equals(safe(subGoalId), safe(targetSubGoalId));
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
