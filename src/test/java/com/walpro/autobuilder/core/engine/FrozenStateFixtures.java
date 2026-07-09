package com.walpro.autobuilder.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.engine.FrozenCycleObserver.StubbedServices;
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
 *   ({@code /frozen-states/log_derived_states.json}, strengths exact). Signal AGE and DECAY are not
 *   recorded in snapshots: age defaults to 1, decay to the per-type modal emission constant —
 *   so ranking sweeps are strength-faithful but recency-approximate, and each carries the
 *   RECORDED production ranking so the instrument's agreement rate is measurable.
 */
public final class FrozenStateFixtures {

    private FrozenStateFixtures() {}

    public record FrozenState(String name, CycleContextData ctx, StubbedServices world,
                           List<String> recordedRanking, String recordedMode, String recordedFirstSelected) {

        public FrozenState(String name, CycleContextData ctx, StubbedServices world, List<String> recordedRanking) {
            this(name, ctx, world, recordedRanking, null, null);
        }
    }

    // ---------------------------------------------------------------- canonical

    public static List<FrozenState> canonicalStates() {
        List<FrozenState> out = new ArrayList<>();

        // 1. Bootstrap: fresh start, root not yet decomposed.
        {
            GoalEntity root = new GoalEntity("root-1", "root goal", "planner", GoalType.GOAL, null);
            StubbedServices world = new StubbedServices(root, root, false, false, false,
                    GoalKindClassifier.GoalKind.EXECUTABLE, false, Set.of());
            CycleContextData ctx = CycleContextData.builder().build()
                    .withCurrentGoalId("root-1")
                    .withCurrentSubGoalId("root-1")
                    .withCurrentSubGoalDescription("root goal")
                    .withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.10, "SystemBootstrapper"));
            out.add(new FrozenState("canon: bootstrap", ctx, world, null));
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
            out.add(new FrozenState("canon: pending-evaluation", ctx, StubbedServices.defaults(), null));
        }

        // 3./4. Stagnation, without and with an active divergence request.
        {
            CycleContextData stagnating = midRunBase()
                    .withEvaluationOutcome(new EvaluationOutcome(0.5, null, GoalProgressState.STAGNATING, null, null))
                    .withSignal(Signal.of(BasicSignals.STAGNATION, 1.0, 0.0, "EvaluationAgent"));
            out.add(new FrozenState("canon: stagnating", stagnating, StubbedServices.defaults(), null));
            out.add(new FrozenState("canon: stagnating-diverging",
                    stagnating.withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "AutobuilderService")),
                    StubbedServices.defaults(), null));
        }

        // 5. Sustained repetition (reinforced across >=2 cycles -> proposer breakout rule).
        out.add(new FrozenState("canon: sustained-repetition",
                midRunBase().withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 2.0, 0.0, "RepetitionDetector")),
                StubbedServices.defaults(), null));

        // 6./7. Score plateau: first detection (merge boost) and sustained (replan escalation).
        out.add(new FrozenState("canon: plateau-first",
                midRunBase().withSignal(Signal.of(BasicSignals.SCORE_PLATEAU, 1.0, 0.10, "AutobuilderService")),
                StubbedServices.defaults().withVariants(), null));
        out.add(new FrozenState("canon: plateau-sustained",
                midRunBase().withSignal(Signal.of(BasicSignals.SCORE_PLATEAU, 3.0, 0.10, "AutobuilderService")),
                StubbedServices.defaults().withVariants(), null));

        // 8. Planner throttled (goal-backlog-cap site constants), weak REPLAN_REQUESTED below
        //    the 1.0 override threshold — planner should be blocked.
        {
            CycleContextData ctx = midRunBase()
                    .withSignal(Signal.of(BasicSignals.PLANNING_THROTTLED, 0.9, 0.10, "goal-backlog-cap"))
                    .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 0.5, 0.10, "EvaluationAgent"));
            out.add(new FrozenState("canon: planner-throttled", ctx, StubbedServices.defaults(), null));
            out.add(new FrozenState("canon: planner-throttle-overridden",
                    ctx.withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 1.2, 0.10, "EvaluationAgent")),
                    StubbedServices.defaults(), null));
        }

        // 9. Build broken: gate active, only build-fix roles eligible.
        out.add(new FrozenState("canon: build-broken",
                midRunBase().withSignal(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "BuildLineageTracker")),
                StubbedServices.defaults().withBuildBlock(Set.of("analysis", "evolution", "merge")), null));

        // 10. Halted: engine must end before deliberation/ranking.
        out.add(new FrozenState("canon: halted",
                midRunBase().withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "GoalExhaustionDetector")),
                StubbedServices.defaults(), null));

        // 11. Missing-symbol grounding: calibration candidate #1 at its emission values.
        out.add(new FrozenState("canon: missing-symbol-fresh",
                midRunBase().withSignal(Signal.of(BasicSignals.MISSING_SYMBOL, 1.0, 0.25, "planner-grounding")),
                StubbedServices.defaults(), null));

        // 12./13. Subgoal exhausted: recoverable (executable siblings remain) vs replan-needed.
        {
            GoalEntity remaining = new GoalEntity("sg-2", "next executable subgoal", "planner", GoalType.SUBGOAL, "root-1");
            remaining.setKind(GoalKindClassifier.GoalKind.EXECUTABLE);
            CycleContextData exhausted = midRunBase()
                    .withSignal(Signal.of(BasicSignals.SUBGOAL_EXHAUSTED, 0.8, 0.05, "GoalExhaustionDetector"));
            out.add(new FrozenState("canon: subgoal-exhausted-executable-remaining",
                    exhausted.withRemainingGoals(List.of(remaining)), StubbedServices.defaults(), null));
            out.add(new FrozenState("canon: subgoal-exhausted-none-remaining",
                    exhausted.withRemainingGoals(List.of()), StubbedServices.defaults(), null));
        }

        return out;
    }

    /** Typical steady mid-run signal backdrop: all five role signals at plausible strengths/ages. */
    private static CycleContextData midRunBase() {
        return CycleContextData.builder().build()
                .withCycleName("fixture-cycle")
                .withParentCycleName("fixture-cycle-prev")
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

    public static List<FrozenState> logDerivedStates() {
        try (InputStream in = FrozenStateFixtures.class.getResourceAsStream("/frozen-states/log_derived_states.json")) {
            JsonNode arr = new ObjectMapper().readTree(in);
            List<FrozenState> out = new ArrayList<>();
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
                out.add(new FrozenState(node.get("name").asText(), ctx, StubbedServices.defaults(), recorded,
                        node.path("recordedMode").asText(null),
                        node.path("recordedFirstSelected").asText(null)));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("cannot load /frozen-states/log_derived_states.json", e);
        }
    }

    public static List<FrozenState> all() {
        List<FrozenState> out = new ArrayList<>(canonicalStates());
        out.addAll(logDerivedStates());
        return out;
    }
}
