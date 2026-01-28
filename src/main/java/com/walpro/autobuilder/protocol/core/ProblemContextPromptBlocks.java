package com.walpro.autobuilder.protocol.core;

import com.walpro.autobuilder.analysis.problem.ProblemContext;
import com.walpro.autobuilder.analysis.problem.ProblemEntry;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ProblemContextPromptBlocks {

    private ProblemContextPromptBlocks() {}

    public static String asPromptBlock(ProblemContext pc, String goalId, String subGoalId) {
        if (pc == null) {
            return """
                --- PROBLEM CONTEXT ---
                (none)
                """;
        }

        List<ProblemEntry> open = pc.open() == null ? List.of() : pc.open();
        if (open.isEmpty()) {
            return """
                --- PROBLEM CONTEXT ---
                Open: (none)
                """;
        }

        List<ProblemEntry> scoped = open.stream()
                .filter(p -> p != null && p.matchesScope(goalId, subGoalId))
                .sorted(Comparator.comparing((ProblemEntry p) -> p.severity() == null ? "" : p.severity().name())
                        .reversed())
                .limit(12)
                .toList();

        if (scoped.isEmpty()) {
            // still show global open problems (useful for meta), but keep it short
            scoped = open.stream()
                    .filter(p -> p != null)
                    .limit(6)
                    .toList();
        }

        String keywords = scoped.stream()
                .flatMap(p -> safeSet(p.keywords()).stream())
                .distinct()
                .limit(24)
                .collect(Collectors.joining(", "));

        long blockers = scoped.stream().filter(p -> p != null && p.isBlocking()).count();

        String entries = scoped.stream()
                .map(ProblemContextPromptBlocks::formatEntry)
                .collect(Collectors.joining("\n"));

        return """
            --- PROBLEM CONTEXT ---
            OpenProblemsInScope: %d
            BlockingInScope: %d
            Keywords: %s

            Open:
            %s
            """.formatted(
                scoped.size(),
                blockers,
                keywords.isBlank() ? "(none)" : keywords,
                entries.isBlank() ? "(none)" : entries
        );
    }

    private static String formatEntry(ProblemEntry p) {
        String sev = p.severity() == null ? "(n/a)" : p.severity().name();
        String status = p.status() == null ? "(n/a)" : p.status().name();
        String kw = safeSet(p.keywords()).stream().limit(10).collect(Collectors.joining(", "));
        if (kw.isBlank()) kw = "(none)";

        return "- [%s|%s] %s :: %s :: keywords=[%s] :: scope(goal=%s sub=%s)".formatted(
                status,
                sev,
                safe(p.id()),
                safeOneLine(p.title()),
                kw,
                safe(p.targetGoalId()),
                safe(p.targetSubGoalId())
        );
    }

    private static Set<String> safeSet(Set<String> s) {
        return s == null ? Set.of() : s;
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "(none)" : s.trim();
    }

    private static String safeOneLine(String s) {
        if (s == null || s.isBlank()) return "(none)";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }
}
