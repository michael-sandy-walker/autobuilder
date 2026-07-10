package com.walpro.autobuilder.analysis.problem;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.HeuristicFinding;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultProblemDetectorTest {

    private final DefaultProblemDetector detector = new DefaultProblemDetector();

    private static CycleContextData baseCtx() {
        return CycleContextData.builder()
                .cycleName("c1")
                .currentGoalId("g")
                .currentSubGoalId("s")
                
                .problemContext(ProblemContext.empty())
                .build();
    }

    @Test
    void detectAndUpdate_addsSignalProblem_whenStrongSignalPresent() {
        CycleContextData ctx = baseCtx()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.STAGNATION, 0.80, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(0.80, 0.0)));

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.5,
                MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING,
                "fb",
                "rec"
        );

        ProblemContext out = detector.detectAndUpdate(ctx, outcome, List.of());

        assertThat(out.open()).anyMatch(p -> p != null && p.keywords().contains("signal:STAGNATION"));
    }

    @Test
    void detectAndUpdate_autoResolves_evalRejectedProblem_whenOutcomeAccepted() {
        ProblemEntry openEvalRejected = new ProblemEntry(
                "problem::g::s::eval:REJECTED::",
                ProblemStatus.OPEN,
                ProblemSeverity.WARNING,
                "Evaluation: REJECTED",
                "x",
                Set.of("eval:REJECTED", "source:evaluation"),
                "g",
                "s",
                "c0",
                "c0",
                null,
                null,
                List.of(),
                List.of()
        );

        CycleContextData ctx = baseCtx()
                .withProblemContext(new ProblemContext(List.of(openEvalRejected), List.of()));

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.8,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                "ok",
                "rec"
        );

        ProblemContext out = detector.detectAndUpdate(ctx, outcome, List.of());

        assertThat(out.open()).noneMatch(p -> p != null && p.keywords().contains("eval:REJECTED"));
        assertThat(out.resolved()).anyMatch(p -> p != null && p.keywords().contains("eval:REJECTED"));
    }

    @Test
    void detectAndUpdate_autoResolves_signalProblem_whenSignalWeak() {
        ProblemEntry openStagnationSignal = new ProblemEntry(
                "problem::g::s::signal:STAGNATION::",
                ProblemStatus.OPEN,
                ProblemSeverity.WARNING,
                "Signal: STAGNATION",
                "x",
                Set.of("signal:STAGNATION", "source:signal"),
                "g",
                "s",
                "c0",
                "c0",
                null,
                null,
                List.of(),
                List.of()
        );

        // weak/absent request -> should resolve (no open STAGNATION on the channel)
        CycleContextData ctx = baseCtx()
                .withProblemContext(new ProblemContext(List.of(openStagnationSignal), List.of()));

        EvaluationOutcome outcome = new EvaluationOutcome(
                0.2,
                MutationAcceptanceState.REJECTED,
                GoalProgressState.STAGNATING,
                "fb",
                "rec"
        );

        ProblemContext out = detector.detectAndUpdate(ctx, outcome, List.of());

        assertThat(out.open()).noneMatch(p -> p != null && p.keywords().contains("signal:STAGNATION"));
        assertThat(out.resolved()).anyMatch(p -> p != null && p.keywords().contains("signal:STAGNATION"));
    }
}