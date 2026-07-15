package com.walpro.autobuilder.graph.core;

import java.util.List;

public final class OrchestrationSnapshotFormatter {

    private OrchestrationSnapshotFormatter() {}

    public static String toLLMBlock(OrchestrationSnapshot snap, int recentLimit) {
        if (snap == null) return "(no snapshot)";

        StringBuilder sb = new StringBuilder();

        sb.append("SubGoalId: ").append(nullSafe(snap.subGoalId())).append("\n");
        if (snap.subGoalResolution() != null && !snap.subGoalResolution().isBlank()) {
            sb.append("SubGoalResolution: ").append(trunc(snap.subGoalResolution(), 200)).append("\n");
        }
        sb.append("AttemptId: ").append(nullSafe(snap.attemptId())).append("\n");
        sb.append("AttemptCycleId: ").append(nullSafe(snap.attemptCycleId())).append("\n");
        sb.append("Fingerprint: ").append(nullSafe(snap.fingerprint())).append("\n");
        sb.append("ChangedFilesCount: ").append(nullSafe(snap.changedFilesCount())).append("\n");
        sb.append("DeletedFilesCount: ").append(nullSafe(snap.deletedFilesCount())).append("\n");

        if (snap.lastEvolution() != null) {
            sb.append("\n--- Last Evolution ---\n");
            sb.append("ActionId: ").append(nullSafe(snap.lastEvolution().id())).append("\n");
            sb.append("Status: ").append(nullSafe(snap.lastEvolution().status())).append("\n");
            sb.append("Ts: ").append(nullSafe(snap.lastEvolution().ts())).append("\n");
        } else {
            sb.append("\n--- Last Evolution ---\n(none)\n");
        }

        if (snap.lastEvaluation() != null) {
            sb.append("\n--- Last Evaluation ---\n");
            sb.append("ActionId: ").append(nullSafe(snap.lastEvaluation().id())).append("\n");
            sb.append("Status: ").append(nullSafe(snap.lastEvaluation().status())).append("\n");
            sb.append("ScoreNow: ").append(nullSafe(snap.evalScoreNow())).append("\n");
            sb.append("ScoreBefore: ").append(nullSafe(snap.evalScoreBefore())).append("\n");
            sb.append("Delta: ").append(nullSafe(snap.evalDelta())).append("\n");
            sb.append("Ts: ").append(nullSafe(snap.lastEvaluation().ts())).append("\n");
            if (snap.evalContentNow() != null && !snap.evalContentNow().isBlank()) {
                sb.append("Content: ").append(trunc(snap.evalContentNow(), 350)).append("\n");
            }
        } else {
            sb.append("\n--- Last Evaluation ---\n(none)\n");
        }

        List<OrchestrationSnapshot.CriterionProgress> criteriaProgress = snap.criteriaProgress();
        if (criteriaProgress != null && !criteriaProgress.isEmpty()) {
            sb.append("\n--- Criteria Progress ---\n");
            for (var cp : criteriaProgress) {
                String icon = cp.neverAssessed() ? "\u2013" : (cp.lastPassed() ? "\u2713" : "\u2717");
                sb.append("[").append(icon).append("] ")
                        .append(trunc(cp.criterionText(), 120))
                        .append(" ");
                if (cp.neverAssessed()) {
                    sb.append("(never assessed)");
                } else if (cp.lastPassed()) {
                    sb.append("(passing)");
                } else if (cp.everPassed()) {
                    sb.append("(failed ").append(cp.consecutiveFailCount()).append(" consecutive cycles)");
                } else {
                    sb.append("(failed ").append(cp.consecutiveFailCount())
                            .append(" consecutive cycles, never passed)");
                }
                sb.append("\n");
            }
        }

        sb.append("\n--- Merge Record ---\n");
        sb.append("MergedViaGraph: ").append(Boolean.TRUE.equals(snap.mergedViaGraph())).append("\n");
        if (snap.lastMerge() != null) {
            sb.append("ActionId: ").append(nullSafe(snap.lastMerge().id())).append("\n");
            sb.append("Status: ").append(nullSafe(snap.lastMerge().status())).append("\n");
            sb.append("Ts: ").append(nullSafe(snap.lastMerge().ts())).append("\n");
        } else {
            sb.append("(no merge action)\n");
        }

        if (snap.lastScopeAssessment() != null) {
            OrchestrationSnapshot.ActionRecord sa = snap.lastScopeAssessment();
            sb.append("\n--- Last Scope Assessment ---\n");
            sb.append("Fit: ").append(nullSafe(sa.status())).append("\n");
            if (sa.payload() != null) {
                sb.append("Confidence: ").append(nullSafe(sa.payload().get("confidence"))).append("\n");
                sb.append("BestMatchSubGoalId: ").append(nullSafe(sa.payload().get("bestMatchingSubGoalId"))).append("\n");
                Object msg = sa.payload().get("message");
                if (msg != null) {
                    sb.append("Message: ").append(trunc(msg.toString(), 200)).append("\n");
                }
            }
            sb.append("Ts: ").append(nullSafe(sa.ts())).append("\n");
        } else {
            sb.append("\n--- Last Scope Assessment ---\n(none)\n");
        }

        List<OrchestrationSnapshot.HeuristicWarning> warnings = snap.recurringCriticalHeuristics();
        if (warnings != null && !warnings.isEmpty()) {
            sb.append("\n--- Recurring Heuristic Warnings ---\n");
            for (var w : warnings) {
                sb.append("- [").append(nullSafe(w.name())).append("] ")
                        .append("critical x").append(w.criticalCount())
                        .append(": ").append(trunc(w.lastRationale(), 200))
                        .append("\n");
            }
        }

        // T4-BE: deferred-mutation ledger summary
        OrchestrationSnapshot.DeferredMutationsSummary defer = snap.deferredMutations();
        if (defer != null && (defer.openCount() > 0 || defer.readyCount() > 0)) {
            sb.append("\n--- Deferred Mutations ---\n");
            sb.append("Open: ").append(defer.openCount())
                    .append("  Ready: ").append(defer.readyCount()).append("\n");
            List<OrchestrationSnapshot.ReadyCandidate> ready = defer.readyCandidates();
            if (ready != null && !ready.isEmpty()) {
                int cap = Math.min(5, ready.size());
                for (int i = 0; i < cap; i++) {
                    var c = ready.get(i);
                    sb.append("- READY id=").append(nullSafe(c.id()))
                            .append(" intent=").append(nullSafe(c.intentSubgoalId()))
                            .append(" score=").append(String.format("%.2f", c.combinedScore()))
                            .append(" files=").append(c.touchedFileCount());
                    if (c.intentSubgoalDescription() != null) {
                        sb.append(" desc=\"").append(trunc(c.intentSubgoalDescription(), 80)).append("\"");
                    }
                    sb.append("\n");
                }
                if (ready.size() > cap) {
                    sb.append("... (").append(ready.size() - cap).append(" more)\n");
                }
            }
        }

        sb.append("\n--- Recent Actions (capped) ---\n");
        List<OrchestrationSnapshot.ActionRecord> recent = snap.recentActions();
        if (recent == null || recent.isEmpty()) {
            sb.append("(none)\n");
        } else {
            int lim = Math.max(1, recentLimit);
            int count = 0;
            for (var a : recent) {
                if (a == null) continue;
                if (count++ >= lim) {
                    sb.append("... (truncated)\n");
                    break;
                }
                sb.append("- ")
                        .append(nullSafe(a.ts()))
                        .append(" type=").append(nullSafe(a.type()))
                        .append(" status=").append(nullSafe(a.status()))
                        .append(" cycle=").append(nullSafe(a.cycleId()))
                        .append(" attempt=").append(nullSafe(a.attemptId()))
                        .append("\n");
            }
        }

        return sb.toString();
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        String t = s.replace("\n", " ").replace("\r", " ").trim();
        if (t.length() <= n) return t;
        return t.substring(0, n) + "...";
    }

    private static String nullSafe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}