package com.walpro.autobuilder.core.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;

import com.walpro.autobuilder.core.tuning.TuningParameterStore;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
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

    // T4-J: the seed decay is the BIRTH decay authority for a role signal — Signal.reinforce
    // keeps the minimum decay, so later re-emissions with larger constants are min'd away and
    // the seed value effectively governs the role's decay for the whole run. ROLE_ANALYSIS
    // seeds at 0.0 (never decays; its participation shrinks via attenuation only) — the T4-J
    // replay measured this constant as the most decision-sensitive decay in the system, hence
    // the tuning-store hook on exactly the analysis and planner seeds.
    private static final double ANALYSIS_SEED_DECAY_DEFAULT = 0.0;

    private static final String SOURCE = "cycle-start";

    /** T4-J Phase 2: optional — tests and manual construction keep code defaults. */
    @Setter
    @Autowired(required = false)
    private TuningParameterStore tuningParameterStore;

    public CycleContextData seedIfEmpty(CycleContextData ctx) {
        if (ctx == null) return null;

        Map<SignalType, Signal> signals = ctx.signals();
        if (signals == null) {
            signals = new LinkedHashMap<>();
            ctx = ctx.withSignals(signals);
        }

        // T4-BZ-3: open channel requests count as carry-over exactly like stored signals did
        // before the request family left the map — bootstrap grants only when NOTHING carried.
        if (!signals.isEmpty() || !ctx.requests().isEmpty()) {
            return ctx; // already seeded/has carry-over signals or open requests
        }

        boolean hasGoal =
                ctx.currentSubGoalId() != null
                        || ctx.currentGoalId() != null
                        || (ctx.currentGoalDescription() != null && !ctx.currentGoalDescription().isBlank());

        double analysisDecay = tuned("signal-decay.role-analysis.seed", ANALYSIS_SEED_DECAY_DEFAULT);

        // IMPORTANT: Use real SignalType constants/instances here.
        // The below assumes we have canonical SignalType objects for these roles.
        if (!hasGoal) {
            // Start-from-nothing: planning must happen first
            put(signals, BasicSignals.ROLE_PLANNER, STRONG, tuned("signal-decay.role-planner.seed", FAST_DECAY));
            put(signals, BasicSignals.ROLE_EVOLUTION, WEAK, NORMAL_DECAY);
            put(signals, BasicSignals.ROLE_EVALUATION, WEAK, NORMAL_DECAY);
            put(signals, BasicSignals.ROLE_MERGE, WEAK, NORMAL_DECAY);
            put(signals, BasicSignals.ROLE_ANALYSIS, WEAK, analysisDecay);
        } else {
            // Goal exists: default forward motion is evolve -> evaluate
            put(signals, BasicSignals.ROLE_EVOLUTION, STRONG, FAST_DECAY);
            put(signals, BasicSignals.ROLE_EVALUATION, MEDIUM, NORMAL_DECAY);
            put(signals, BasicSignals.ROLE_PLANNER, WEAK, tuned("signal-decay.role-planner.seed", NORMAL_DECAY));
            put(signals, BasicSignals.ROLE_MERGE, WEAK, NORMAL_DECAY);
            put(signals, BasicSignals.ROLE_ANALYSIS, WEAK, analysisDecay);
        }

        return ctx;
    }

    private double tuned(String name, double codeDefault) {
        return tuningParameterStore != null ? tuningParameterStore.get(name, codeDefault) : codeDefault;
    }

    private static void put(Map<SignalType, Signal> signals, SignalType type, double strength, double decay) {
        signals.put(type, Signal.of(type, strength, decay, SOURCE));
    }
}
