package com.walpro.autobuilder.analysis.problem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ProblemContext(
        List<ProblemEntry> open,
        List<ProblemEntry> resolved
) {
    public static ProblemContext empty() {
        return new ProblemContext(List.of(), List.of());
    }

    public boolean hasBlockingProblemsFor(String goalId, String subGoalId) {
        if (open == null || open.isEmpty()) return false;
        for (ProblemEntry p : open) {
            if (p != null && p.isBlocking() && p.matchesScope(goalId, subGoalId)) return true;
        }
        return false;
    }

    public ProblemContext upsertOpen(ProblemEntry entry) {
        Objects.requireNonNull(entry, "entry");
        List<ProblemEntry> nextOpen = new ArrayList<>(open == null ? List.of() : open);

        for (int i = 0; i < nextOpen.size(); i++) {
            if (Objects.equals(nextOpen.get(i).id(), entry.id())) {
                nextOpen.set(i, entry);
                return new ProblemContext(List.copyOf(nextOpen), resolved == null ? List.of() : List.copyOf(resolved));
            }
        }

        nextOpen.add(entry);
        return new ProblemContext(List.copyOf(nextOpen), resolved == null ? List.of() : List.copyOf(resolved));
    }
}
