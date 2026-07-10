package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CycleLoggingService {

    public void logCycleSummary(CycleContextData ctx) {
        if (ctx == null) {
            log.info("CYCLE summary: ctx=null");
            return;
        }

        DeliberationResult dr = ctx.deliberationResult();

        String mode = dr != null && dr.mode() != null ? dr.mode().name() : "NONE";
        String conf = dr != null ? String.format("%.2f", dr.confidence()) : "n/a";

        boolean paused = com.walpro.autobuilder.core.lifecycle.DerivedFacts.systemPaused(ctx);
        boolean exhausted = DerivedFacts.goalExhausted(ctx);
        boolean repetition = ctx.hasStrongRequest(BasicSignals.REPETITION_DETECTED, 0.50);
        boolean merged = ctx.merged();
        boolean promoted = ctx.subgoalPromoted();

        String topSignals = topSignals(com.walpro.autobuilder.core.lifecycle.SignalReadout.of(ctx).raw(), 5);

        log.info(
                "CYCLE {} goal={} sub={} prog={} mut={} result={} mode={} conf={} flags=[paused={},exhausted={},repetition={},merged={},promoted={}] signals={}",
                ctx.cycleName(),
                safe(ctx.currentGoalId()),
                safe(ctx.currentSubGoalId()),
                ctx.goalProgress() != null ? ctx.goalProgress().name() : "null",
                ctx.mutationState() != null ? ctx.mutationState().name() : "null",
                ctx.cycleResultState() != null ? ctx.cycleResultState().name() : "null",
                mode,
                conf,
                paused,
                exhausted,
                repetition,
                merged,
                promoted,
                topSignals
        );
    }

    private static String topSignals(Map<SignalType, Signal> signals, int limit) {
        if (signals == null || signals.isEmpty()) return "{}";
        return signals.values().stream()
                .sorted(Comparator.comparingDouble(Signal::strength).reversed())
                .limit(Math.max(1, limit))
                .map(s -> s.type().name() + "=" + String.format("%.2f", s.strength()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}
