package com.walpro.autobuilder.analysis.problem;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record ProblemEntry(
        String id,
        ProblemStatus status,
        ProblemSeverity severity,

        String title,
        String description,

        Set<String> keywords,          // for pattern lookup
        String targetGoalId,
        String targetSubGoalId,

        String firstSeenCycleId,
        String lastSeenCycleId,
        Instant firstSeenAt,
        Instant lastSeenAt,

        List<String> evidenceActionIds, // optional: store actionIds/cycleIds later
        List<String> evidenceNotes
) {
    public boolean isBlocking() {
        return status == ProblemStatus.OPEN && severity == ProblemSeverity.BLOCKER;
    }

    public boolean matchesScope(String goalId, String subGoalId) {
        if (goalId == null || goalId.isBlank()) return false;
        if (!goalId.equals(targetGoalId)) return false;

        // If problem is scoped to subgoal, require match. If not scoped, it blocks goal-level completion.
        if (targetSubGoalId == null || targetSubGoalId.isBlank()) return true;
        return targetSubGoalId.equals(subGoalId);
    }
}
