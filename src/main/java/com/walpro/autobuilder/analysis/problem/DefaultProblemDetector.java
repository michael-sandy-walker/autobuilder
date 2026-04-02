package com.walpro.autobuilder.analysis.problem;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.HeuristicFinding;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DefaultProblemDetector implements ProblemDetector {

    private static final double SIGNAL_PROBLEM_THRESHOLD = 0.70;
    private static final double SIGNAL_RESOLVE_THRESHOLD = 0.20;

    @Override
    public ProblemContext detectAndUpdate(CycleContextData ctx, EvaluationOutcome outcome, List<HeuristicFinding> findings) {
        ProblemContext base = ctx.problemContext() != null ? ctx.problemContext() : ProblemContext.empty();

        String goalId = ctx.currentGoalId();
        String subGoalId = ctx.currentSubGoalId();
        String cycleId = ctx.cycleName();
        Instant now = Instant.now();

        ProblemContext next = base;

        // A) Heuristic-derived problems
        if (findings != null && !findings.isEmpty()) {
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
                String id = stableProblemId(goalId, subGoalId, "heuristic:" + type, safe(f.path()));

                Set<String> keywords = new LinkedHashSet<>();
                if (!type.isBlank()) keywords.add(type);
                if (!safe(f.path()).isBlank()) keywords.add("path:" + f.path());
                keywords.add("source:heuristic");

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
                        now,
                        now,
                        List.of(),
                        List.of()
                );

                next = next.upsertOpen(entry);
            }
        }

        // B) Signal-derived problems
        next = addSignalProblems(next, ctx.signals(), goalId, subGoalId, cycleId, now);

        // C) Outcome-derived problems
        next = addOutcomeProblems(next, outcome, ctx, goalId, subGoalId, cycleId, now);

        // D) Auto-resolve sweep (new)
        next = autoResolve(next, ctx, outcome);

        // Optional: keep bounded for prompt stability
        next = next.compact(24, 24);

        return next;
    }

    private ProblemContext autoResolve(ProblemContext base, CycleContextData ctx, EvaluationOutcome outcome) {
        if (base == null) return null;
        if (ctx == null) return base;

        MutationAcceptanceState ms = outcome != null ? outcome.mutationState() : ctx.mutationState();
        GoalProgressState gp = outcome != null ? outcome.goalProgress() : ctx.goalProgress();

        ProblemContext next = base;

        // Resolve evaluation-derived problems when they no longer apply
        if (ms == MutationAcceptanceState.ACCEPTED) {
            next = next.resolveIf(
                    p -> p != null && p.keywords().contains("eval:REJECTED"),
                    "Auto-resolved: evaluation accepted."
            );
        }

        if (gp != GoalProgressState.STAGNATING) {
            next = next.resolveIf(
                    p -> p != null && p.keywords().contains("eval:STAGNATING"),
                    "Auto-resolved: no longer stagnating."
            );
        }

        // Resolve signal-derived problems when signal is weak/absent
        next = resolveSignalIfWeak(next, ctx.signals(), BasicSignals.STAGNATION);
        next = resolveSignalIfWeak(next, ctx.signals(), BasicSignals.REPETITION_DETECTED);
        next = resolveSignalIfWeak(next, ctx.signals(), BasicSignals.LOW_CONFIDENCE);
        next = resolveSignalIfWeak(next, ctx.signals(), BasicSignals.SUBGOAL_EXHAUSTED);
        next = resolveSignalIfWeak(next, ctx.signals(), BasicSignals.GOAL_EXHAUSTED);

        return next;
    }

    private ProblemContext resolveSignalIfWeak(ProblemContext ctx,
                                               Map<SignalType, Signal> signals,
                                               SignalType type) {
        if (ctx == null) return null;

        double strength = strength(signals, type);

        if (strength >= SIGNAL_RESOLVE_THRESHOLD) {
            return ctx;
        }

        String kw = "signal:" + type.name();

        return ctx.resolveIf(
                p -> p != null && p.keywords().contains(kw),
                "Auto-resolved: signal weakened/absent (" + kw + ")."
        );
    }

    private static double strength(Map<SignalType, Signal> signals, SignalType type) {
        if (signals == null || signals.isEmpty() || type == null) return 0.0;

        Signal s = signals.get(type);
        if (s != null) return s.strength();

        // fallback: compare by string to survive type mismatches
        for (Map.Entry<SignalType, Signal> e : signals.entrySet()) {
            if (e.getKey() != null && type.name().equalsIgnoreCase(e.getKey().name()) && e.getValue() != null) {
                return e.getValue().strength();
            }
        }

        return 0.0;
    }

    private ProblemContext addSignalProblems(ProblemContext base,
                                             Map<SignalType, Signal> signals,
                                             String goalId,
                                             String subGoalId,
                                             String cycleId,
                                             Instant now) {
        if (signals == null || signals.isEmpty()) return base;

        ProblemContext next = base;

        SignalType[] candidates = new SignalType[] {
                BasicSignals.STAGNATION,
                BasicSignals.REPETITION_DETECTED,
                BasicSignals.LOW_CONFIDENCE,
                BasicSignals.SUBGOAL_EXHAUSTED,
                BasicSignals.GOAL_EXHAUSTED
        };

        for (SignalType t : candidates) {
            Signal s = signals.get(t);
            if (s == null) continue;
            if (s.strength() < SIGNAL_PROBLEM_THRESHOLD) continue;

            // GOAL_EXHAUSTED = root exhaustion = always BLOCKER (T1-AK).
            // SUBGOAL_EXHAUSTED = recoverable = WARNING.
            ProblemSeverity sev = (t == BasicSignals.GOAL_EXHAUSTED)
                    ? ProblemSeverity.BLOCKER
                    : ProblemSeverity.WARNING;

            String type = "signal:" + t.name();
            String id = stableProblemId(goalId, subGoalId, type, "");

            Set<String> keywords = new LinkedHashSet<>();
            keywords.add(type);
            keywords.add("source:signal");

            ProblemEntry entry = new ProblemEntry(
                    id,
                    ProblemStatus.OPEN,
                    sev,
                    "Signal: " + t.name(),
                    "Detected strong signal " + t.name() + " strength=" + s.strength(),
                    Set.copyOf(keywords),
                    goalId,
                    subGoalId,
                    cycleId,
                    cycleId,
                    now,
                    now,
                    List.of(),
                    List.of()
            );

            next = next.upsertOpen(entry);
        }

        return next;
    }

    private ProblemContext addOutcomeProblems(ProblemContext base,
                                              EvaluationOutcome outcome,
                                              CycleContextData ctx,
                                              String goalId,
                                              String subGoalId,
                                              String cycleId,
                                              Instant now) {
        if (outcome == null && ctx == null) return base;

        MutationAcceptanceState ms = outcome != null ? outcome.mutationState() : ctx.mutationState();
        GoalProgressState gp = outcome != null ? outcome.goalProgress() : ctx.goalProgress();

        ProblemContext next = base;

        if (ms == MutationAcceptanceState.REJECTED) {
            String id = stableProblemId(goalId, subGoalId, "eval:REJECTED", "");
            Set<String> keywords = Set.of("eval:REJECTED", "source:evaluation");

            ProblemEntry entry = new ProblemEntry(
                    id,
                    ProblemStatus.OPEN,
                    ProblemSeverity.WARNING,
                    "Evaluation: REJECTED",
                    safeOneLine(outcome != null ? outcome.feedbackMessage() : ctx.evaluationFeedback()),
                    keywords,
                    goalId,
                    subGoalId,
                    cycleId,
                    cycleId,
                    now,
                    now,
                    List.of(),
                    List.of()
            );

            next = next.upsertOpen(entry);
        }

        if (gp == GoalProgressState.STAGNATING) {
            String id = stableProblemId(goalId, subGoalId, "eval:STAGNATING", "");
            Set<String> keywords = Set.of("eval:STAGNATING", "source:evaluation");

            ProblemEntry entry = new ProblemEntry(
                    id,
                    ProblemStatus.OPEN,
                    ProblemSeverity.WARNING,
                    "Evaluation: STAGNATING",
                    safeOneLine(outcome != null ? outcome.recommendation() : ctx.recommendation()),
                    keywords,
                    goalId,
                    subGoalId,
                    cycleId,
                    cycleId,
                    now,
                    now,
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

    private String safeOneLine(String s) {
        if (s == null || s.isBlank()) return "";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }
}