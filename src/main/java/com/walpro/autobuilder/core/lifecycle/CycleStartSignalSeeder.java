package com.walpro.autobuilder.core.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class CycleStartSignalSeeder {

    // Strength tuning: enough to rank, not “permanent”
    private static final double STRONG = 0.80;
    private static final double MEDIUM = 0.50;
    private static final double WEAK = 0.20;

    // Decay: seeds should fade quickly if not reinforced by progress
    private static final double FAST_DECAY = 0.25;
    private static final double NORMAL_DECAY = 0.15;

    private static final String SOURCE = "cycle-start";

    public CycleContextData seedIfEmpty(CycleContextData ctx) {
        if (ctx == null) return null;

        Map<SignalType, Signal> signals = ctx.signals();
        if (signals == null) {
            signals = new LinkedHashMap<>();
            ctx = ctx.withSignals(signals);
        }

        if (!signals.isEmpty()) {
            return ctx; // already seeded/has carry-over signals
        }

        boolean hasGoal =
                ctx.currentSubGoalId() != null
                        || ctx.currentGoalId() != null
                        || (ctx.currentGoalDescription() != null && !ctx.currentGoalDescription().isBlank());

        // IMPORTANT: Use your real SignalType constants/instances here.
        // The below assumes you have canonical SignalType objects for these roles.
        if (!hasGoal) {
            // Start-from-nothing: planning must happen first
            put(signals, BasicSignals.ROLE_PLANNER, STRONG, FAST_DECAY);
            put(signals, BasicSignals.ROLE_EVOLUTION, WEAK, NORMAL_DECAY);
            put(signals, BasicSignals.ROLE_EVALUATION, WEAK, NORMAL_DECAY);
            put(signals, BasicSignals.ROLE_MERGE, WEAK, NORMAL_DECAY);
        } else {
            // Goal exists: default forward motion is evolve -> evaluate
            put(signals, BasicSignals.ROLE_EVOLUTION, STRONG, FAST_DECAY);
            put(signals, BasicSignals.ROLE_EVALUATION, MEDIUM, NORMAL_DECAY);
            put(signals, BasicSignals.ROLE_PLANNER, WEAK, NORMAL_DECAY);
            put(signals, BasicSignals.ROLE_MERGE, WEAK, NORMAL_DECAY);
        }

        return ctx;
    }

    private static void put(Map<SignalType, Signal> signals, SignalType type, double strength, double decay) {
        signals.put(type, Signal.of(type, strength, decay, SOURCE));
    }
}
