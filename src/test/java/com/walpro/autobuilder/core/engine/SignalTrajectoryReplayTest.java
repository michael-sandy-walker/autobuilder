package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.engine.FrozenCycleObserver.Perturbation;
import com.walpro.autobuilder.core.engine.FrozenCycleObserver.StubbedServices;
import com.walpro.autobuilder.core.engine.FrozenStateFixtures.FrozenState;
import com.walpro.autobuilder.core.engine.SignalTrajectoryReplay.Trajectory;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-J Phase 3d scenarios. The scripted role effects mirror the production emission sites
 * they stand in for (cited inline); each scenario is a deliberately simple trajectory shape,
 * not an agent emulation.
 */
class SignalTrajectoryReplayTest {

    // ---------------------------------------------------------------- scripted role effects

    /** EvolutionAgent: fresh mutation each cycle — diff, new fingerprint, EVOLUTION_OCCURRED +
     *  EVOLUTION_FINGERPRINT_CHANGED, and the ROLE_EVALUATION hand-off (EvolutionAgent:209). */
    private static UnaryOperator<CycleContextData> evolutionProducingFreshDiff() {
        return ctx -> ctx
                .withEvolutionRanThisCycle(true)
                .withLastEvolutionFingerprint("fp-" + ctx.cycleName())
                .withProjectDiff(new ProjectDiffResult(null, Map.of("A.java", "+" + ctx.cycleName()), Set.of()))
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.10, "script-evolution"))
                .withSignal(Signal.of(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED, 1.0, 0.10, "script-evolution"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 1.0, 0.05, "evolution-wrote-files"));
    }

    /** EvaluationAgent accept path: banks the fingerprint and emits the one-shot ROLE_MERGE
     *  (EvaluationAgent:427, strength 1.0 decay 1.0 — the "consume-now" site). */
    private static UnaryOperator<CycleContextData> evaluationAccepting() {
        return ctx -> ctx
                .withLastEvaluatedEvolutionFingerprint(ctx.lastEvolutionFingerprint())
                .withMutationState(MutationAcceptanceState.ACCEPTED)
                .withSignal(Signal.of(BasicSignals.ROLE_MERGE, 1.0, 1.0, "evaluation-accepted"));
    }

    /** EvaluationAgent reject path with a replan request (the T3-BR/T4-BX shape). */
    private static UnaryOperator<CycleContextData> evaluationRejectingWithReplan() {
        return ctx -> ctx
                .withLastEvaluatedEvolutionFingerprint(ctx.lastEvolutionFingerprint())
                .withMutationState(MutationAcceptanceState.REJECTED)
                .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 0.8, 0.10, "script-evaluation"));
    }

    private static UnaryOperator<CycleContextData> mergeApplying() {
        return ctx -> ctx.withMerged(true);
    }

    private static Map<String, UnaryOperator<CycleContextData>> scripts(
            UnaryOperator<CycleContextData> evolution, UnaryOperator<CycleContextData> evaluation,
            UnaryOperator<CycleContextData> merge) {
        Map<String, UnaryOperator<CycleContextData>> out = new LinkedHashMap<>();
        out.put("evolution", evolution);
        out.put("evaluation", evaluation);
        if (merge != null) out.put("merge", merge);
        return out;
    }

    private static CycleContextData startState() {
        return FrozenStateFixtures.canonicalStates().stream()
                .filter(s -> s.name().equals("canon: missing-symbol-fresh"))
                .findFirst().orElseThrow().ctx();
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    void steadyProgress_evolvesEvaluatesMergesEveryCycle() {
        Trajectory t = SignalTrajectoryReplay.replay(
                startState(), StubbedServices.defaults().withVariantsFollowingAcceptance(),
                scripts(evolutionProducingFreshDiff(), evaluationAccepting(), mergeApplying()), 8);

        assertThat(t.points()).hasSize(8);
        for (SignalTrajectoryReplay.CyclePoint p : t.points()) {
            assertThat(p.executedRoles()).as("cycle " + p.cycle())
                    .containsSubsequence("evolution", "evaluation", "merge");
            assertThat(p.mode()).isEqualTo("UNDIRECTED");
        }
    }

    @Test
    void rejectionLoop_missingSymbolDies_whenItsDecaySaysSo() {
        // No merges ever happen; MISSING_SYMBOL gets no reinforcement, so its decay constant
        // alone decides its lifetime: 1.0 at decay 0.25 -> dead at the cycle-5 tick
        // (age-0 tick is decay-free), at decay 0.10 -> dead at the cycle-11 tick.
        Trajectory atQuarter = SignalTrajectoryReplay.replay(
                startState(), StubbedServices.defaults(),
                scripts(evolutionProducingFreshDiff(), evaluationRejectingWithReplan(), null), 15);

        CycleContextData slowDecay = Perturbation.decay(BasicSignals.MISSING_SYMBOL, 0.10)
                .apply().apply(startState());
        Trajectory atTenth = SignalTrajectoryReplay.replay(
                slowDecay, StubbedServices.defaults(),
                scripts(evolutionProducingFreshDiff(), evaluationRejectingWithReplan(), null), 15);

        assertThat(atQuarter.deathCycle(BasicSignals.MISSING_SYMBOL)).isEqualTo(5);
        assertThat(atTenth.deathCycle(BasicSignals.MISSING_SYMBOL)).isEqualTo(11);
    }

    /** EvaluationAgent reject path emitting the analysis trigger (low-score LOW_CONFIDENCE). */
    private static UnaryOperator<CycleContextData> evaluationRejectingWithLowConfidence() {
        return ctx -> ctx
                .withLastEvaluatedEvolutionFingerprint(ctx.lastEvolutionFingerprint())
                .withMutationState(MutationAcceptanceState.REJECTED)
                .withSignal(Signal.of(BasicSignals.LOW_CONFIDENCE, 0.6, 0.10, "script-evaluation"));
    }

    /**
     * Measured Phase 3d result, pinned: MISSING_SYMBOL's decayPerCycle (0.25 vs 0.10) shifts
     * its death cycle by ~6 cycles yet flips NOT ONE decision over 15 cycles in any of three
     * scenarios — rejection loop on the canonical grounding state, rejection loop on the
     * recorded log-01 boundary state (top-2 margin 0.0001), and a cognitive-analysis loop where
     * analysis eligibility does not depend on a re-emitted REPLAN_REQUESTED. Mechanism: the
     * signal boosts planner+analysis EQUALLY (cannot reorder the pair), and those roles'
     * participation windows are bounded by their own ROLE-signal lifecycle
     * (attenuation/decay), which is identical across variants and collapses eligibility before
     * the diagnostic signal's tail can matter. If this pin ever fails, a decay-sensitive
     * decision channel has appeared — exactly what Q7 calibration would then need to know.
     */
    @Test
    void missingSymbolDecayVariants_shiftDeathCycles_butFlipNoDecisions_inAllThreeScenarios() {
        Map<String, UnaryOperator<CycleContextData>> rejection =
                scripts(evolutionProducingFreshDiff(), evaluationRejectingWithReplan(), null);
        Map<String, UnaryOperator<CycleContextData>> cognitive =
                scripts(evolutionProducingFreshDiff(), evaluationRejectingWithLowConfidence(), null);
        StubbedServices cognitiveWorld = StubbedServices.defaults()
                .withKind(com.walpro.autobuilder.goal.GoalKindClassifier.GoalKind.COGNITIVE);

        record Scenario(String name, CycleContextData start, StubbedServices world,
                        Map<String, UnaryOperator<CycleContextData>> scripts) {}
        for (Scenario s : java.util.List.of(
                new Scenario("canonical/rejection", startState(), StubbedServices.defaults(), rejection),
                new Scenario("boundary log-01/rejection", boundaryState(), StubbedServices.defaults(), rejection),
                new Scenario("canonical/cognitive", startState(), cognitiveWorld, cognitive))) {
            Trajectory atQuarter = SignalTrajectoryReplay.replay(s.start(), s.world(), s.scripts(), 15);
            Trajectory atTenth = SignalTrajectoryReplay.replay(
                    Perturbation.decay(BasicSignals.MISSING_SYMBOL, 0.10).apply().apply(s.start()),
                    s.world(), s.scripts(), 15);

            assertThat(atTenth.deathCycle(BasicSignals.MISSING_SYMBOL))
                    .as(s.name() + ": slower decay must extend the lifetime")
                    .isGreaterThan(atQuarter.deathCycle(BasicSignals.MISSING_SYMBOL));
            assertThat(atQuarter.firstDecisionDivergence(atTenth))
                    .as(s.name() + ": measured decision-inertness of the decay constant")
                    .isEqualTo(-1);
        }
    }

    private static CycleContextData boundaryState() {
        return FrozenStateFixtures.logDerivedStates().stream()
                .filter(s -> s.name().startsWith("log-01"))
                .findFirst().orElseThrow().ctx();
    }

    @Test
    void haltedStart_recordsOneHaltedPointAndStops() {
        CycleContextData halted = startState().withSignal(
                Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "test"));
        Trajectory t = SignalTrajectoryReplay.replay(
                halted, StubbedServices.defaults(), Map.of(), 10);
        assertThat(t.points()).hasSize(1);
        assertThat(t.points().get(0).halted()).isTrue();
        assertThat(t.points().get(0).executedRoles()).isEmpty();
    }

    // ---------------------------------------------------------------- report

    @Test
    void generateTrajectoryReport() throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# T4-J Phase 3d — multi-cycle signal trajectories\n\n");
        md.append("Scripted-scenario replays (see SignalTrajectoryReplayTest for the scripts). ");
        md.append("Decay grids show where a decay constant changes decisions across cycles.\n");

        md.append("\n## steady progress (evolve -> accept -> merge each cycle)\n\n```\n");
        md.append(SignalTrajectoryReplay.render(SignalTrajectoryReplay.replay(
                startState(), StubbedServices.defaults().withVariantsFollowingAcceptance(),
                scripts(evolutionProducingFreshDiff(), evaluationAccepting(), mergeApplying()), 10)));
        md.append("```\n");

        Map<String, UnaryOperator<CycleContextData>> rejection =
                scripts(evolutionProducingFreshDiff(), evaluationRejectingWithReplan(), null);
        Trajectory baseline = SignalTrajectoryReplay.replay(
                startState(), StubbedServices.defaults(), rejection, 20);
        md.append("\n## rejection loop (evolve -> reject+replan, no merges), MISSING_SYMBOL decay=0.25\n\n```\n");
        md.append(SignalTrajectoryReplay.render(baseline));
        md.append("```\n");

        Map<String, UnaryOperator<CycleContextData>> cognitive =
                scripts(evolutionProducingFreshDiff(), evaluationRejectingWithLowConfidence(), null);
        StubbedServices cognitiveWorld = StubbedServices.defaults()
                .withKind(com.walpro.autobuilder.goal.GoalKindClassifier.GoalKind.COGNITIVE);
        md.append("\n## cognitive-analysis loop (evolve -> reject+LOW_CONFIDENCE, COGNITIVE subgoal)\n\n```\n");
        md.append(SignalTrajectoryReplay.render(SignalTrajectoryReplay.replay(
                startState(), cognitiveWorld, cognitive, 15)));
        md.append("```\n");

        md.append("\n## MISSING_SYMBOL decay grid (death cycle | first decision divergence vs decay=0.25)\n\n");
        md.append("-1 divergence = the decay value changes NO decision in 20 cycles — the measured result.\n\n");
        md.append("| decay | rejection loop | cognitive loop |\n|--:|---|---|\n");
        Trajectory cognitiveBaseline = SignalTrajectoryReplay.replay(
                startState(), cognitiveWorld, cognitive, 20);
        for (double decay : new double[]{0.05, 0.10, 0.25, 0.50}) {
            CycleContextData varied = Perturbation.decay(BasicSignals.MISSING_SYMBOL, decay)
                    .apply().apply(startState());
            Trajectory r = SignalTrajectoryReplay.replay(varied, StubbedServices.defaults(), rejection, 20);
            Trajectory c = SignalTrajectoryReplay.replay(varied, cognitiveWorld, cognitive, 20);
            md.append("| ").append(decay)
              .append(" | ").append(r.deathCycle(BasicSignals.MISSING_SYMBOL))
              .append(" \\| ").append(r.firstDecisionDivergence(baseline))
              .append(" | ").append(c.deathCycle(BasicSignals.MISSING_SYMBOL))
              .append(" \\| ").append(c.firstDecisionDivergence(cognitiveBaseline)).append(" |\n");
        }

        Path out = Path.of("target", "signal-trajectory-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println("[replay] report written to " + out.toAbsolutePath());
        assertThat(out).exists();
    }
}
