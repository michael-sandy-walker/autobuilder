package com.walpro.autobuilder.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.engine.FrozenCycleObserver.StubbedServices;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.ParticipationWindow;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalReadout;
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
 *
 * T4-BZ-5: states are EXPRESSED ON THE CARRIERS (windows / request channel / derived and
 * toggle context fields) — the stored signal map is gone. Fixture code still describes each
 * state as (type, strength, decay, age, source) tuples — the recorded corpora's vocabulary —
 * and {@link #express} converts by family exactly as the migration did: ROLE_* → window
 * (boost/age/decay-equivalent, validity = decay-implied lifetime remaining), request kinds →
 * channel entry (urgency/age/horizon), EVOLUTION_OCCURRED/EVOLUTION_FINGERPRINT_CHANGED →
 * their fact fields, BUILD_BROKEN → a COMPILE_ERROR build outcome, GOAL_EXHAUSTED /
 * SYSTEM_PAUSED → their reason fields. COMPILE_DIAGNOSTIC_FED_BACK (zero consumers, T4-J
 * Phase 1) drops.
 */
public final class FrozenStateFixtures {

    private FrozenStateFixtures() {}

    public record FrozenState(String name, CycleContextData ctx, StubbedServices world,
                           List<String> recordedRanking, String recordedMode, String recordedFirstSelected) {

        public FrozenState(String name, CycleContextData ctx, StubbedServices world, List<String> recordedRanking) {
            this(name, ctx, world, recordedRanking, null, null);
        }
    }

    /** A recorded signal described in the corpus vocabulary, expressed onto its carrier. */
    static CycleContextData express(CycleContextData ctx, Signal s) {
        SignalType type = s.type();
        String role = BasicSignals.roleNameFor(type);
        if (role != null) {
            Integer remaining = ChannelRequest.horizonFromDecay(s.strength(), s.decayPerCycle());
            return ctx.withWindows(ctx.windows().grant(new ParticipationWindow(
                    role, s.strength(), s.source(), s.age(),
                    remaining == null ? null : s.age() + remaining, s.decayPerCycle())));
        }
        if (BasicSignals.REQUEST_CHANNEL_KINDS.contains(type)) {
            Integer remaining = ChannelRequest.horizonFromDecay(s.strength(), s.decayPerCycle());
            return ctx.withRequests(ctx.requests().issue(new ChannelRequest(
                    type, s.strength(), s.source(), null, s.age(),
                    remaining == null ? null : s.age() + remaining, s.meta() != null ? s.meta() : Map.of())));
        }
        if (BasicSignals.EVOLUTION_OCCURRED.equals(type)) return ctx.withUnmergedEvolutionWork(true);
        if (BasicSignals.EVOLUTION_FINGERPRINT_CHANGED.equals(type)) return ctx.withEvolutionFingerprintChangedThisCycle(true);
        if (BasicSignals.BUILD_BROKEN.equals(type)) {
            return ctx.withBuildOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR, "fixture", 0L, "mvn", null));
        }
        if (BasicSignals.GOAL_EXHAUSTED.equals(type)) return ctx.withGoalExhaustedReason(s.source());
        if (BasicSignals.SYSTEM_PAUSED.equals(type)) return ctx.withPausedReason(s.source());
        if (SignalReadout.familyOf(type) == SignalReadout.Family.REQUEST) {
            // Unknown / legacy names read as requests (operator poke = ask) — but the
            // consumer-less diagnostic type simply drops (zero consumers, T4-J Phase 1).
            return ctx;
        }
        return ctx;
    }

    private static CycleContextData express(CycleContextData ctx, Signal... described) {
        for (Signal s : described) ctx = express(ctx, s);
        return ctx;
    }

    // ---------------------------------------------------------------- canonical

    public static List<FrozenState> canonicalStates() {
        List<FrozenState> out = new ArrayList<>();

        // 1. Bootstrap: fresh start, root not yet decomposed.
        {
            GoalEntity root = new GoalEntity("root-1", "root goal", "planner", GoalType.GOAL, null);
            StubbedServices world = new StubbedServices(root, root, false, false, false, false,
                    GoalKindClassifier.GoalKind.EXECUTABLE, false, Set.of());
            CycleContextData ctx = express(CycleContextData.builder().build()
                    .withCurrentGoalId("root-1")
                    .withCurrentSubGoalId("root-1")
                    .withCurrentSubGoalDescription("root goal"),
                    Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.10, "SystemBootstrapper"));
            out.add(new FrozenState("canon: bootstrap", ctx, world, null));
        }

        // 2. Pending evaluation: evolution just wrote a diff; the canonical mid-cycle state.
        {
            CycleContextData ctx = midRunBase()
                    .withUnmergedEvolutionWork(true)
                    .withEvolutionFingerprintChangedThisCycle(true)
                    .withLastEvolutionFingerprint("fp-2")
                    .withLastEvaluatedEvolutionFingerprint("fp-1")
                    .withEvolutionRanThisCycle(true)
                    .withProjectDiff(new ProjectDiffResult(null, Map.of("A.java", "+x"), Set.of()));
            out.add(new FrozenState("canon: pending-evaluation", ctx, StubbedServices.defaults(), null));
        }

        // 3./4. Stagnation, without and with an active divergence request.
        {
            CycleContextData stagnating = express(midRunBase()
                    .withEvaluationOutcome(new EvaluationOutcome(0.5, null, GoalProgressState.STAGNATING, null, null)),
                    Signal.of(BasicSignals.STAGNATION, 1.0, 0.0, "EvaluationAgent"));
            out.add(new FrozenState("canon: stagnating", stagnating, StubbedServices.defaults(), null));
            out.add(new FrozenState("canon: stagnating-diverging",
                    express(stagnating, Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "AutobuilderService")),
                    StubbedServices.defaults(), null));
        }

        // 5. Sustained repetition (reinforced across >=2 cycles -> proposer breakout rule).
        out.add(new FrozenState("canon: sustained-repetition",
                express(midRunBase(), Signal.of(BasicSignals.REPETITION_DETECTED, 2.0, 0.0, "RepetitionDetector")),
                StubbedServices.defaults(), null));

        // 6./7. Score plateau: first detection (merge boost) and sustained (replan escalation).
        out.add(new FrozenState("canon: plateau-first",
                express(midRunBase(), Signal.of(BasicSignals.SCORE_PLATEAU, 1.0, 0.10, "AutobuilderService")),
                StubbedServices.defaults().withVariants(), null));
        out.add(new FrozenState("canon: plateau-sustained",
                express(midRunBase(), Signal.of(BasicSignals.SCORE_PLATEAU, 3.0, 0.10, "AutobuilderService")),
                StubbedServices.defaults().withVariants(), null));

        // 8. Planner throttled (goal-backlog-cap site constants), weak REPLAN_REQUESTED below
        //    the 1.0 override threshold — planner should be blocked.
        {
            CycleContextData ctx = express(midRunBase(),
                    Signal.of(BasicSignals.PLANNING_THROTTLED, 0.9, 0.10, "goal-backlog-cap"),
                    Signal.of(BasicSignals.REPLAN_REQUESTED, 0.5, 0.10, "EvaluationAgent"));
            out.add(new FrozenState("canon: planner-throttled", ctx, StubbedServices.defaults(), null));
            // The override re-issues REPLAN_REQUESTED 1.2: the channel RAISES 0.5 + 1.2 = 1.7 —
            // exactly the stored map's reinforce merge the original fixture produced.
            out.add(new FrozenState("canon: planner-throttle-overridden",
                    express(ctx, Signal.of(BasicSignals.REPLAN_REQUESTED, 1.2, 0.10, "EvaluationAgent")),
                    StubbedServices.defaults(), null));
        }

        // 9. Build broken: gate active, only build-fix roles eligible.
        out.add(new FrozenState("canon: build-broken",
                express(midRunBase(), Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, "BuildLineageTracker")),
                StubbedServices.defaults().withBuildBlock(Set.of("analysis", "evolution", "merge")), null));

        // 10. Halted: engine must end before deliberation/ranking.
        out.add(new FrozenState("canon: halted",
                express(midRunBase(), Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "GoalExhaustionDetector")),
                StubbedServices.defaults(), null));

        // 11. Missing-symbol grounding: calibration candidate #1 at its emission values.
        out.add(new FrozenState("canon: missing-symbol-fresh",
                express(midRunBase(), Signal.of(BasicSignals.MISSING_SYMBOL, 1.0, 0.25, "planner-grounding")),
                StubbedServices.defaults(), null));

        // 12./13. Subgoal exhausted: recoverable (executable siblings remain) vs replan-needed.
        {
            GoalEntity remaining = new GoalEntity("sg-2", "next executable subgoal", "planner", GoalType.SUBGOAL, "root-1");
            remaining.setKind(GoalKindClassifier.GoalKind.EXECUTABLE);
            CycleContextData exhausted = express(midRunBase(),
                    Signal.of(BasicSignals.SUBGOAL_EXHAUSTED, 0.8, 0.05, "GoalExhaustionDetector"));
            out.add(new FrozenState("canon: subgoal-exhausted-executable-remaining",
                    exhausted.withRemainingGoals(List.of(remaining)), StubbedServices.defaults(), null));
            out.add(new FrozenState("canon: subgoal-exhausted-none-remaining",
                    exhausted.withRemainingGoals(List.of()), StubbedServices.defaults(), null));
        }

        return out;
    }

    /** Typical steady mid-run participation backdrop: all five role windows at plausible boosts/ages. */
    private static CycleContextData midRunBase() {
        return express(CycleContextData.builder().build()
                .withCycleName("fixture-cycle")
                .withParentCycleName("fixture-cycle-prev")
                .withCurrentGoalId("root-1")
                .withCurrentSubGoalId("sg-1")
                .withCurrentSubGoalDescription("implement the current subgoal")
                .withLastEvolutionFingerprint("fp-1")
                .withLastEvaluatedEvolutionFingerprint("fp-1")
                .withRemainingGoals(List.of()),
                Signal.of(BasicSignals.ROLE_PLANNER, 1.2, 0.10, "cycle-start"),
                Signal.of(BasicSignals.ROLE_EVOLUTION, 1.5, 0.10, "cycle-start"),
                Signal.of(BasicSignals.ROLE_EVALUATION, 0.8, 0.10, "cycle-start"),
                Signal.of(BasicSignals.ROLE_MERGE, 0.5, 0.10, "evaluation-accepted"),
                Signal.of(BasicSignals.ROLE_ANALYSIS, 1.0, 0.10, "cycle-start"));
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
                // The recorded map, in the corpus vocabulary — expressed onto carriers below.
                // midRunBase's backdrop windows are replaced by the recorded roles (grant would
                // MERGE; recorded states carry their own exact role strengths), so start from a
                // bare context and re-apply the base's non-signal fields.
                CycleContextData ctx = CycleContextData.builder().build()
                        .withCycleName("fixture-cycle")
                        .withParentCycleName("fixture-cycle-prev")
                        .withCurrentGoalId("root-1")
                        .withCurrentSubGoalId("sg-1")
                        .withCurrentSubGoalDescription("implement the current subgoal")
                        .withLastEvolutionFingerprint("fp-1")
                        .withLastEvaluatedEvolutionFingerprint("fp-1")
                        .withRemainingGoals(List.of());
                Map<String, Double> recordedSignals = new LinkedHashMap<>();
                node.get("signals").properties().forEach(e ->
                        recordedSignals.put(e.getKey(), e.getValue().asDouble()));
                for (Map.Entry<String, Double> e : recordedSignals.entrySet()) {
                    SignalType type = SignalType.of(e.getKey());
                    ctx = express(ctx, new Signal(type, e.getValue(), modalDecay(e.getKey()), 1, "log", Map.of()));
                }
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
