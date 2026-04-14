package com.walpro.autobuilder.agent.interpretation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ArtifactEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultInterpretationAgent implements InterpretationAgent {

    private static final double SIGNAL_ELEVATION_THRESHOLD = 0.2;
    // T2-AU: minimum prior interpretation cycles before pattern-based signals are allowed
    private static final int MIN_HISTORY_DEPTH_FOR_PATTERN_SIGNALS = 3;

    private final AgentGraphService agentGraphService;
    private final InterpretationLLMAdapter llmAdapter;

    @Override
    public InterpretationOutcome interpret(CycleContextData context) {
        if (context == null || context.currentGoalId() == null || context.currentGoalId().isBlank()) {
            return InterpretationOutcome.noop("No goal set; skipping interpretation.");
        }

        if (!shouldInterpret(context)) {
            log.debug("InterpretationAgent skipped — no notable cycle activity (no evolution, no merge, no elevated signals).");
            return InterpretationOutcome.noop("No notable cycle activity; interpretation skipped.");
        }

        List<ArtifactEntity> recent = agentGraphService.findRecentArtifactsForGoal(
                context.currentGoalId(),
                List.of(
                        "planner_outcome",
                        "evaluation_payload",
                        "merge_payload",
                        "deliberation_summary",
                        "interpretation_summary"
                ),
                12
        );

        InterpretationOutcome outcome = llmAdapter.interpret(context, recent);
        return suppressPatternSignalsIfSparseHistory(outcome, recent);
    }

    // T2-AU: strip REPLAN_REQUESTED / LOW_CONFIDENCE when fewer than MIN_HISTORY_DEPTH_FOR_PATTERN_SIGNALS
    // prior interpretation cycles are present — not enough data to identify repeating patterns.
    private InterpretationOutcome suppressPatternSignalsIfSparseHistory(
            InterpretationOutcome outcome, List<ArtifactEntity> recent) {
        long historyDepth = recent.stream()
                .filter(a -> "interpretation_summary".equals(a.type()))
                .count();
        if (historyDepth >= MIN_HISTORY_DEPTH_FOR_PATTERN_SIGNALS) {
            return outcome;
        }
        if (outcome.proposedSignals() == null || outcome.proposedSignals().isEmpty()) {
            return outcome;
        }
        List<Signal> filtered = outcome.proposedSignals().stream()
                .filter(s -> !BasicSignals.REPLAN_REQUESTED.equals(s.type())
                        && !BasicSignals.LOW_CONFIDENCE.equals(s.type()))
                .toList();
        if (filtered.size() == outcome.proposedSignals().size()) {
            return outcome;
        }
        log.debug("sparse history ({} interpretation cycles < {}) — stripped pattern-based signals (REPLAN_REQUESTED/LOW_CONFIDENCE)",
                historyDepth, MIN_HISTORY_DEPTH_FOR_PATTERN_SIGNALS);
        return new InterpretationOutcome(outcome.status(), outcome.summary(), outcome.directives(), filtered);
    }

    private boolean shouldInterpret(CycleContextData ctx) {
        // Always interpret when the cycle produced evolution or a merge — something real happened.
        if (ctx.evolutionOutcome() != null || ctx.merged()) {
            return true;
        }
        // Interpret when steering signals are elevated — the loop may need guidance.
        if (ctx.signals() != null) {
            for (Signal s : ctx.signals().values()) {
                SignalType t = s.type();
                if ((BasicSignals.STAGNATION.equals(t)
                        || BasicSignals.LOW_CONFIDENCE.equals(t)
                        || BasicSignals.REPETITION_DETECTED.equals(t)
                        || BasicSignals.REPLAN_REQUESTED.equals(t))
                        && s.strength() >= SIGNAL_ELEVATION_THRESHOLD) {
                    return true;
                }
            }
        }
        return false;
    }
}
