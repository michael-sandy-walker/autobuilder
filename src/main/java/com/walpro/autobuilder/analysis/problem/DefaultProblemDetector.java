package com.walpro.autobuilder.analysis.problem;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.HeuristicFinding;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class DefaultProblemDetector implements ProblemDetector {

    @Override
    public ProblemContext detectAndUpdate(CycleContextData ctx, EvaluationOutcome outcome, List<HeuristicFinding> findings) {
        ProblemContext base = ctx.problemContext() != null ? ctx.problemContext() : ProblemContext.empty();
        if (findings == null || findings.isEmpty()) return base;

        String goalId = ctx.currentGoalId();
        String subGoalId = ctx.currentSubGoalId();
        String cycleId = ctx.cycleName(); // use cycleName as id for now (we already do this broadly)

        ProblemContext next = base;

        for (HeuristicFinding f : findings) {
            if (f == null) continue;

            ProblemSeverity sev = mapSeverity(f.severity());
            if (sev == null) continue;

            // Only escalate to “problem” when it’s meaningfully relevant:
            // - CRITICAL => BLOCKER
            // - WARNING  => WARNING
            // - INFO     => ignore as problem (still stays as finding)
            if (sev == ProblemSeverity.INFO) continue;

            String type = safe(f.type());
            String id = stableProblemId(goalId, subGoalId, type, safe(f.path()));

            Set<String> keywords = new LinkedHashSet<>();
            if (!type.isBlank()) keywords.add(type);
            if (!safe(f.path()).isBlank()) keywords.add("path:" + f.path());

            ProblemEntry entry = new ProblemEntry(
                    id,
                    ProblemStatus.OPEN,
                    sev,
                    "Heuristic: " + (type.isBlank() ? "unspecified" : type),
                    f.description(),
                    Set.copyOf(keywords),
                    goalId,
                    subGoalId,
                    cycleId,
                    cycleId,
                    Instant.now(),
                    Instant.now(),
                    List.of(),
                    List.of()
            );

            next = next.upsertOpen(entry);
        }

        return next;
    }

    private ProblemSeverity mapSeverity(HeuristicFinding.Severity s) {
        if (s == null) return null;
        return switch (s) {
            case CRITICAL -> ProblemSeverity.BLOCKER;
            case WARNING -> ProblemSeverity.WARNING;
            case INFO -> ProblemSeverity.INFO;
        };
    }

    private String stableProblemId(String goalId, String subGoalId, String type, String path) {
        // deterministic id so repeated cycles update the same problem
        return String.join("::",
                "problem",
                safe(goalId),
                safe(subGoalId),
                safe(type),
                safe(path)
        );
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
