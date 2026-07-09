package com.walpro.autobuilder.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.engine.SignalPerturbationLab.LabWorld;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalKindClassifier;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.model.GoalProgressState;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-J Phase 3a: the frozen-state library.
 *
 * Two fidelity classes, per the design note:
 * - Canonical states: hand-built, full-fidelity contexts spanning the run phases
 *   (bootstrap, pending evaluation, stagnation, repetition, plateau, throttle,
 *   build-broken, halt, endgame). Mode/gate/eligibility sweeps are valid on these.
 * - Log-derived states: real signal maps harvested from recorded corpora
 *   ({@code /lab/log_derived_states.json}, strengths exact). Signal AGE and DECAY are not
 *   recorded in snapshots: age defaults to 1, decay to the per-type modal emission constant —
 *   so ranking sweeps are strength-faithful but recency-approximate, and each carries the
 *   RECORDED production ranking so the instrument's agreement rate is measurable.
 */
public final class LabStateLibrary {

    private LabStateLibrary() {}

    public record LabState(String name, CycleContextData ctx, LabWorld world, List<String> recordedRanking) {}

    // ---------------------------------------------------------------- canonical

    public static List<LabState> canonicalStates() {
        List<LabState> out = new ArrayList<>();

        // 1. Bootstrap: fresh start, root not yet decomposed.
        {
            GoalEntity root = new GoalEntity("root-1", "root goal", "planner", GoalType.GOAL, null);
            LabWorld world = new LabWorld(root, root, false, false, false,
                    GoalKindClassifier.GoalKind.EXECUTABLE, false, Set.of());
            CycleContextData ctx = CycleContextData.builder().build()
                    .withCurrentGoalId("root-1")
                    .withCurrentSubGoalId("root-1")
                    .withCurrentSubGoalDescription("root goal")
                    .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.10, "SystemBootstrapper"));
            out.add(new LabState("canon: bootstrap", ctx, world, null));
        }

        // 2. Pending evaluation: evolution just wrote a diff; the canonical mid-cycle state.
        {
            CycleContextData ctx = midRunBase()
                    .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.10, "EvolutionAgent"))
                    .withSignal(Signal.of(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED, 1.0, 0.10, "EvolutionAgent"))
                    .withLastEvolutionFingerprint("fp-2")
                    .withLastEvaluatedEvolutionFingerprint("fp-1")
                    .withEvolutionRanThisCycle(true)
                    .withProjectDiff(new ProjectDiffResult(null, Map.of("A.java", "+x"), Set.of()));
            out.add(new LabState("canon: pending-evaluation", ctx, LabWorld.defaults(), null));
        }

        // 3./4. Stagnation, without and with an active divergence request.
        {
            CycleContextData stagnating = midRunBase()
                    .withEvaluationOutcome(new EvaluationOutcome(0.5, null, GoalProgressState.STAGNATING, null, null))
                    .withSignal(Signal.of(BasicSignals.STAGNATION, 1.0, 0.0, "EvaluationAgent"));
            out.add(new LabState("canon: stagnating", stagnating, LabWorld.defaults(), null));
            out.add(new LabState("canon: stagnating-diverging",
                    stagnating.withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "AutobuilderService")),
                    LabWorld.defaults(), null));
        }

        // 5. Sustained repetition (reinforced across >=2 cycles -> proposer breakout rule).
        out.add(new LabState("canon: sustained-repetition",
                midRunBase().withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 2.0, 0.0, "RepetitionDetector")),
                LabWorld.defaults(), null));

        // 6./7. Score plateau: first detection (merge boost) and sustained (replan escalation).
        out.add(new LabState("canon: plateau-first",
                midRunBase().withSignal(Signal.of(BasicSignals.SCORE_PLATEAU, 1.0, 0.10, "AutobuilderService")),
                LabWorld.defaults().withVariants(), null));
        out.add(new LabState("canon: plateau-sustained",
                midRunBase().withSignal(Signal.of(BasicSignals.SCORE_PLATEAU, 3.0, 0.10, "AutobuilderService")),
                LabWorld.defaults().withVariants(), null));

        // 8. Planner throttled (goal-backlog-cap site constants), weak REPLAN_REQUESTED below
        //    the 1.0 override threshold — planner should be blocked.
        {
            CycleContextData ctx = midRunBase()
                    .withSignal(Signal.of(BasicSignals.PLANNING_THROTTLED, 0.9, 0.10, "goal-backlog-cap"))
                    .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 0.5, 0.10, "EvaluationAgent"));
            out.add(new LabState("canon: planner-throttled", ctx, LabWorld.defaults(), null));
            out.add(new LabState("canon: planner-throttle-overridden",
                    ctx.withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.2, 0.10, "EvaluationAgent")),
                    LabWorld.defaults(), null));
        }

        // 9. Build broken: gate active, only build-fix roles eligible.
        out.add(new LabState("canon: build-broken",
                midRunBase().withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "BuildLineageTracker")),
                LabWorld.defaults().withBuildBlock(Set.of("analysis", "evolution", "merge")), null));

        // 10. Halted: engine must end before deliberation/ranking.
        out.add(new LabState("canon: halted",
                midRunBase().withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "GoalExhaustionDetector")),
                LabWorld.defaults(), null));

        // 11. Missing-symbol grounding: calibration candidate #1 at its emission values.
        out.add(new LabState("canon: missing-symbol-fresh",
                midRunBase().withSignal(Signal.of(BasicSignals.MISSING_SYMBOL, 1.0, 0.25, "planner-grounding")),
                LabWorld.defaults(), null));

        // 12./13. Subgoal exhausted: recoverable (executable siblings remain) vs replan-needed.
        {
            GoalEntity remaining = new GoalEntity("sg-2", "next executable subgoal", "planner", GoalType.SUBGOAL, "root-1");
            remaining.setKind(GoalKindClassifier.GoalKind.EXECUTABLE);
            CycleContextData exhausted = midRunBase()
                    .withSignal(Signal.of(BasicSignals.SUBGOAL_EXHAUSTED, 0.8, 0.05, "GoalExhaustionDetector"));
            out.add(new LabState("canon: subgoal-exhausted-executable-remaining",
                    exhausted.withRemainingGoals(List.of(remaining)), LabWorld.defaults(), null));
            out.add(new LabState("canon: subgoal-exhausted-none-remaining",
                    exhausted.withRemainingGoals(List.of()), LabWorld.defaults(), null));
        }

        return out;
    }

    /** Typical steady mid-run signal backdrop: all five role signals at plausible strengths/ages. */
    private static CycleContextData midRunBase() {
        return CycleContextData.builder().build()
                .withCycleName("lab-cycle")
                .withParentCycleName("lab-cycle-prev")
                .withCurrentGoalId("root-1")
                .withCurrentSubGoalId("sg-1")
                .withCurrentSubGoalDescription("implement the current subgoal")
                .withLastEvolutionFingerprint("fp-1")
                .withLastEvaluatedEvolutionFingerprint("fp-1")
                .withRemainingGoals(List.of())
                .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.2, 0.10, "cycle-start"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.5, 0.10, "cycle-start"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 0.8, 0.10, "cycle-start"))
                .withSignal(Signal.of(BasicSignals.ROLE_MERGE, 0.5, 0.10, "evaluation-accepted"))
                .withSignal(Signal.of(BasicSignals.ROLE_ANALYSIS, 1.0, 0.10, "cycle-start"));
    }

    // ---------------------------------------------------------------- log-derived

    /** Modal decayPerCycle per type from the Phase-1 emission-site table; 0.10 is the corpus mode. */
    private static double modalDecay(String type) {
        return switch (type) {
            case "MISSING_SYMBOL" -> 0.25;
            case "COMPILE_DIAGNOSTIC_FED_BACK" -> 0.20;
            case "PLANNING_THROTTLED" -> 0.15;
            case "GOAL_EXHAUSTED", "SYSTEM_PAUSED", "BUILD_BROKEN", "EVOLUTION_NOOP", "STAGNATION" -> 0.0;
            default -> 0.10;
        };
    }

    public static List<LabState> logDerivedStates() {
        try (InputStream in = LabStateLibrary.class.getResourceAsStream("/lab/log_derived_states.json")) {
            JsonNode arr = new ObjectMapper().readTree(in);
            List<LabState> out = new ArrayList<>();
            for (JsonNode node : arr) {
                Map<SignalType, Signal> signals = new LinkedHashMap<>();
                node.get("signals").properties().forEach(e -> {
                    SignalType type = SignalType.of(e.getKey());
                    double strength = e.getValue().asDouble();
                    signals.put(type, new Signal(type, strength, modalDecay(e.getKey()), 1, "log", Map.of()));
                });
                CycleContextData ctx = midRunBase().withSignals(signals);
                List<String> recorded = new ArrayList<>();
                node.get("rankedRoles").forEach(r -> recorded.add(r.asText()));
                out.add(new LabState(node.get("name").asText(), ctx, LabWorld.defaults(), recorded));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("cannot load /lab/log_derived_states.json", e);
        }
    }

    public static List<LabState> all() {
        List<LabState> out = new ArrayList<>(canonicalStates());
        out.addAll(logDerivedStates());
        return out;
    }
}
