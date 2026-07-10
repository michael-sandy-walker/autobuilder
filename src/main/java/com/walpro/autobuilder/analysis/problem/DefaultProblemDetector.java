package com.walpro.autobuilder.analysis.problem;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.HeuristicFinding;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
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
        next = addSignalProblems(next, ctx, goalId, subGoalId, cycleId, now);
        // T4-BZ-2: GOAL_EXHAUSTED is a derived fact — raise its BLOCKER problem (T1-AK) here.
        if (DerivedFacts.goalExhausted(ctx)) {
            next = addDerivedGoalExhaustedProblem(next, ctx, goalId, subGoalId, cycleId, now);
        }

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

        // Resolve signal-derived problems when the request is no longer open (consumed/expired)
        // or was issued weak — T4-BZ-3: requests don't decay, so "weakened" now means the
        // addressed mechanism consumed it or the validity horizon passed (urgency reads 0.0).
        next = resolveSignalIfWeak(next, ctx, BasicSignals.STAGNATION);
        next = resolveSignalIfWeak(next, ctx, BasicSignals.REPETITION_DETECTED);
        next = resolveSignalIfWeak(next, ctx, BasicSignals.LOW_CONFIDENCE);
        next = resolveSignalIfWeak(next, ctx, BasicSignals.SUBGOAL_EXHAUSTED);
        // T4-BZ-2: GOAL_EXHAUSTED is a derived fact — resolve its problem when the fact is false.
        if (!DerivedFacts.goalExhausted(ctx)) {
            String kw = "signal:" + BasicSignals.GOAL_EXHAUSTED.name();
            next = next.resolveIf(
                    p -> p != null && p.keywords().contains(kw),
                    "Auto-resolved: signal weakened/absent (" + kw + ")."
            );
        }

        // T2-BT: auto-resolve problems scoped to a subgoal that is no longer active.
        // Keeps root-goal-scoped problems (targetSubGoalId null or blank) intact.
        String activeSubGoalId = ctx.currentSubGoalId();
        if (activeSubGoalId != null && !activeSubGoalId.isBlank()) {
            next = next.resolveIf(
                    p -> p != null
                            && p.targetSubGoalId() != null
                            && !p.targetSubGoalId().isBlank()
                            && !p.targetSubGoalId().equals(activeSubGoalId),
                    "Auto-resolved: problem scoped to inactive subgoal (stale scope)."
            );
        }

        return next;
    }

    private ProblemContext resolveSignalIfWeak(ProblemContext ctx,
                                               CycleContextData ctxData,
                                               SignalType type) {
        if (ctx == null) return null;

        double strength = ctxData != null ? ctxData.requestUrgency(type) : 0.0;

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
                                             CycleContextData ctx,
                                             String goalId,
                                             String subGoalId,
                                             String cycleId,
                                             Instant now) {
        if (ctx == null) return base;

        ProblemContext next = base;

        SignalType[] candidates = new SignalType[] {
                BasicSignals.STAGNATION,
                BasicSignals.REPETITION_DETECTED,
                BasicSignals.LOW_CONFIDENCE,
                BasicSignals.SUBGOAL_EXHAUSTED
        };

        for (SignalType t : candidates) {
            // T4-BZ-3: request-family kinds — open-request urgency (stored twin via the bridge).
            double strength = ctx.requestUrgency(t);
            if (strength < SIGNAL_PROBLEM_THRESHOLD) continue;

            // SUBGOAL_EXHAUSTED = recoverable = WARNING (GOAL_EXHAUSTED is derived; see below).
            String type = "signal:" + t.name();
            String id = stableProblemId(goalId, subGoalId, type, "");

            Set<String> keywords = new LinkedHashSet<>();
            keywords.add(type);
            keywords.add("source:signal");

            ProblemEntry entry = new ProblemEntry(
                    id,
                    ProblemStatus.OPEN,
                    ProblemSeverity.WARNING,
                    "Signal: " + t.name(),
                    "Detected strong signal " + t.name() + " strength=" + strength,
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

    /** T4-BZ-2: GOAL_EXHAUSTED = root exhaustion = always BLOCKER (T1-AK), from the derived fact. */
    private ProblemContext addDerivedGoalExhaustedProblem(ProblemContext base, CycleContextData ctx,
            String goalId, String subGoalId, String cycleId, Instant now) {
        String type = "signal:" + BasicSignals.GOAL_EXHAUSTED.name();
        String reason = ctx.goalExhaustedReason() != null ? ctx.goalExhaustedReason() : "root-terminal";
        ProblemEntry entry = new ProblemEntry(
                stableProblemId(goalId, subGoalId, type, ""),
                ProblemStatus.OPEN,
                ProblemSeverity.BLOCKER,
                "Signal: " + BasicSignals.GOAL_EXHAUSTED.name(),
                "Halt reason: " + reason,
                Set.of(type, "source:signal"),
                goalId,
                subGoalId,
                cycleId,
                cycleId,
                now,
                now,
                List.of(),
                List.of()
        );
        return base.upsertOpen(entry);
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