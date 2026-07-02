package com.walpro.autobuilder.protocol.meta;

import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;

public final class OrchestrationLedgerRenderer {

    private OrchestrationLedgerRenderer() {}

    public static String render(OrchestrationSnapshot s) {
        if (s == null) return "(no snapshot available)";

        StringBuilder b = new StringBuilder();

        if (s.attemptId() != null) {
            b.append("Attempt: ").append(s.attemptId());
            if (s.attemptCycleId() != null) {
                b.append(" (cycle=").append(s.attemptCycleId()).append(")");
            }
            if (s.changedFilesCount() != null || s.deletedFilesCount() != null) {
                b.append(" changes=");
                b.append(nullToDash(s.changedFilesCount()));
                b.append(" deleted=");
                b.append(nullToDash(s.deletedFilesCount()));
            }
            b.append("\n");
        } else {
            b.append("Attempt: (none)\n");
        }

        if (s.lastEvolution() != null) {
            b.append("Last evolution: ")
                    .append(nullToDash(s.lastEvolution().status()))
                    .append("\n");
        } else {
            b.append("Last evolution: (none)\n");
        }

        if (s.lastEvaluation() != null) {
            var e = s.lastEvaluation();
            b.append("Last evaluation: ").append(nullToDash(e.status()));

            if (s.evalScoreNow() != null) b.append(" score=").append(round(s.evalScoreNow()));
            if (s.evalScoreBefore() != null) b.append(" before=").append(round(s.evalScoreBefore()));
            if (s.evalDelta() != null) b.append(" delta=").append(round(s.evalDelta()));
            b.append("\n");

            if (e.payload() != null) {
                Object gp = e.payload().get("goalProgress");
                Object ms = e.payload().get("mutationState");
                if (gp != null) b.append(" progress=").append(gp);
                if (ms != null) b.append(" state=").append(ms);
            }

            b.append("\n");
        } else {
            b.append("Last evaluation: (none)\n");
        }

        if (s.lastMerge() != null) {
            b.append("Last merge action: ")
                    .append(nullToDash(s.lastMerge().status()))
                    .append("\n");
        } else if (Boolean.TRUE.equals(s.mergedViaGraph())) {
            b.append("Merge record: present (merge graph)\n");
        } else {
            b.append("Merge record: (none)\n");
        }

        if (s.recentActions() != null && !s.recentActions().isEmpty()) {
            b.append("Recent actions:\n");
            for (var a : s.recentActions()) {
                b.append("- ")
                        .append(a.type())
                        .append(" ")
                        .append(nullToDash(a.status()));
                if (a.attemptId() != null) {
                    b.append(" attempt=").append(a.attemptId());
                }
                if (a.cycleId() != null) {
                    b.append(" cycle=").append(a.cycleId());
                }
                b.append("\n");
            }
        } else {
            b.append("Recent actions: (none)\n");
        }

        return b.toString().trim();
    }

    private static String nullToDash(Object o) {
        return o == null ? "-" : o.toString();
    }

    private static String round(Double d) {
        return String.format("%.3f", d);
    }
}
