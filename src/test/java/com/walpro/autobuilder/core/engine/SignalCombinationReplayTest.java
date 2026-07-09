package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.engine.FrozenCycleObserver.StubbedServices;
import com.walpro.autobuilder.core.engine.SignalTrajectoryReplay.Trajectory;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.goal.GoalKindClassifier;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-J combination campaign (user steer 2026-07-09: one-factor-at-a-time misses interactions —
 * "where are the several hundred offline runs over different combinations of signal values?").
 *
 * Draws N seeded random combinations: EVERY signal in the start state simultaneously gets a
 * strength and a decay drawn from grids. Each combination runs 20 cycles through the replay in
 * three scenarios and is compared against (a) the default trajectory and (b) every constituent
 * single-factor variant — so divergences split into "explained by a main effect" vs
 * "interaction-only" (no single constituent change diverges, the combination does).
 *
 * Outputs: target/signal-combination-matrix.csv (one row per combination x scenario, all factor
 * values + outcome columns) and target/signal-combination-report.md (main-effect table +
 * interaction-only analysis). Honest limit: outcomes here are DECISION trajectories (modes,
 * executed roles, halts); real run quality (merge acceptance, goal closure, artifact quality)
 * needs the live layers — this campaign bounds what the selection layer can and cannot be
 * steered into by value combinations.
 */
class SignalCombinationReplayTest {

    private static final int COMBINATIONS = Integer.getInteger("signal.matrix.combinations", 500);
    private static final long SEED = 42;
    private static final int CYCLES = 20;

    private static final double[] STRENGTH_LEVELS = {0.05, 0.5, 1.0, 2.0, 3.0};
    private static final double[] DECAY_LEVELS = {0.0, 0.05, 0.10, 0.25, 0.5};

    // ---------------------------------------------------------------- scenarios (shared shapes)

    private static UnaryOperator<CycleContextData> evolutionProducingFreshDiff() {
        return ctx -> ctx
                .withEvolutionRanThisCycle(true)
                .withLastEvolutionFingerprint("fp-" + ctx.cycleName())
                .withProjectDiff(new ProjectDiffResult(null, Map.of("A.java", "+" + ctx.cycleName()), Set.of()))
                .withSignal(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.10, "script-evolution"))
                .withSignal(Signal.of(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED, 1.0, 0.10, "script-evolution"))
                .withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 1.0, 0.05, "evolution-wrote-files"));
    }

    private record Scenario(String name, StubbedServices world, Map<String, UnaryOperator<CycleContextData>> scripts) {}

    private static List<Scenario> scenarios() {
        UnaryOperator<CycleContextData> rejectReplan = ctx -> ctx
                .withLastEvaluatedEvolutionFingerprint(ctx.lastEvolutionFingerprint())
                .withMutationState(MutationAcceptanceState.REJECTED)
                .withSignal(Signal.of(BasicSignals.REPLAN_REQUESTED, 0.8, 0.10, "script-evaluation"));
        UnaryOperator<CycleContextData> rejectLowConfidence = ctx -> ctx
                .withLastEvaluatedEvolutionFingerprint(ctx.lastEvolutionFingerprint())
                .withMutationState(MutationAcceptanceState.REJECTED)
                .withSignal(Signal.of(BasicSignals.LOW_CONFIDENCE, 0.6, 0.10, "script-evaluation"));
        UnaryOperator<CycleContextData> accept = ctx -> ctx
                .withLastEvaluatedEvolutionFingerprint(ctx.lastEvolutionFingerprint())
                .withMutationState(MutationAcceptanceState.ACCEPTED)
                .withSignal(Signal.of(BasicSignals.ROLE_MERGE, 1.0, 1.0, "evaluation-accepted"));
        UnaryOperator<CycleContextData> merge = ctx -> ctx.withMerged(true);

        Map<String, UnaryOperator<CycleContextData>> rejection = new LinkedHashMap<>();
        rejection.put("evolution", evolutionProducingFreshDiff());
        rejection.put("evaluation", rejectReplan);

        Map<String, UnaryOperator<CycleContextData>> cognitive = new LinkedHashMap<>();
        cognitive.put("evolution", evolutionProducingFreshDiff());
        cognitive.put("evaluation", rejectLowConfidence);

        Map<String, UnaryOperator<CycleContextData>> steady = new LinkedHashMap<>();
        steady.put("evolution", evolutionProducingFreshDiff());
        steady.put("evaluation", accept);
        steady.put("merge", merge);

        return List.of(
                new Scenario("rejection", StubbedServices.defaults(), rejection),
                new Scenario("cognitive",
                        StubbedServices.defaults().withKind(GoalKindClassifier.GoalKind.COGNITIVE), cognitive),
                new Scenario("steady", StubbedServices.defaults().withVariantsFollowingAcceptance(), steady));
    }

    /**
     * Start states for the campaign: canonical states spanning distinct operating shapes plus
     * the closest-margin recorded boundary state. Halted/bootstrap states are excluded (the
     * replay ends immediately / has no role signals to vary).
     */
    private static Map<String, CycleContextData> startStates() {
        Map<String, CycleContextData> out = new LinkedHashMap<>();
        for (String name : List.of("canon: missing-symbol-fresh", "canon: pending-evaluation",
                "canon: planner-throttled", "canon: stagnating")) {
            out.put(name.replace("canon: ", ""), FrozenStateFixtures.canonicalStates().stream()
                    .filter(s -> s.name().equals(name)).findFirst().orElseThrow().ctx());
        }
        out.put("log-01-boundary", FrozenStateFixtures.logDerivedStates().stream()
                .filter(s -> s.name().startsWith("log-01")).findFirst().orElseThrow().ctx());
        return out;
    }

    // ---------------------------------------------------------------- the campaign

    @Test
    void combinationCampaign_matrixAndInteractionAnalysis() throws Exception {
        StringBuilder csvAll = new StringBuilder();
        StringBuilder mdAll = new StringBuilder();
        mdAll.append("# T4-J combination campaign — ").append(COMBINATIONS)
             .append(" seeded combinations x ").append(scenarios().size())
             .append(" scenarios x ").append(CYCLES).append(" cycles x ")
             .append(startStates().size()).append(" start states\n\n");
        mdAll.append("Full matrix: target/signal-combination-matrix.csv. Divergence = first cycle ")
             .append("whose mode/executed-roles/halt differ from the all-defaults trajectory of the ")
             .append("same state+scenario.\n");
        boolean first = true;
        for (Map.Entry<String, CycleContextData> entry : startStates().entrySet()) {
            runCampaignForState(entry.getKey(), entry.getValue(), csvAll, mdAll, first);
            first = false;
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "signal-combination-matrix.csv"), csvAll.toString());
        Files.writeString(Path.of("target", "signal-combination-report.md"), mdAll.toString());
        System.out.println("[matrix] reports: target/signal-combination-matrix.csv, target/signal-combination-report.md");
        assertThat(Path.of("target", "signal-combination-matrix.csv")).exists();
    }

    private void runCampaignForState(String stateName, CycleContextData base,
                                     StringBuilder csvAll, StringBuilder mdAll, boolean writeHeader)
            throws Exception {
        List<SignalType> factors = base.signals().keySet().stream()
                .sorted(java.util.Comparator.comparing(SignalType::name)).toList();
        List<Scenario> scenarios = scenarios();

        // Default and single-factor reference trajectories per scenario.
        Map<String, Trajectory> defaults = new LinkedHashMap<>();
        // key: scenario|signal|dim|level -> diverges vs default?
        Map<String, Boolean> singleFactorDiverges = new LinkedHashMap<>();
        for (Scenario sc : scenarios) {
            Trajectory def = SignalTrajectoryReplay.replay(base, sc.world(), sc.scripts(), CYCLES);
            defaults.put(sc.name(), def);
            for (SignalType type : factors) {
                for (double v : STRENGTH_LEVELS) {
                    Trajectory t = SignalTrajectoryReplay.replay(
                            FrozenCycleObserver.Perturbation.strength(type, v).apply().apply(base),
                            sc.world(), sc.scripts(), CYCLES);
                    singleFactorDiverges.put(sc.name() + "|" + type.name() + "|s|" + v,
                            t.firstDecisionDivergence(def) != -1);
                }
                for (double v : DECAY_LEVELS) {
                    Trajectory t = SignalTrajectoryReplay.replay(
                            FrozenCycleObserver.Perturbation.decay(type, v).apply().apply(base),
                            sc.world(), sc.scripts(), CYCLES);
                    singleFactorDiverges.put(sc.name() + "|" + type.name() + "|d|" + v,
                            t.firstDecisionDivergence(def) != -1);
                }
            }
        }

        // The seeded random combinations.
        Random rnd = new Random(SEED);
        StringBuilder csv = new StringBuilder();
        if (writeHeader) csv.append("state,combo,scenario,factorAssignment,firstDivergenceCycle,haltCycle,executedRoleCounts\n");

        // per (scenario, factor, dim, level): [diverging, total] among combinations
        Map<String, int[]> mainEffect = new TreeMap<>();
        Map<String, Integer> divergedPerScenario = new TreeMap<>();
        Map<String, Integer> interactionOnlyPerScenario = new TreeMap<>();
        List<String> interactionExamples = new ArrayList<>();

        for (int combo = 1; combo <= COMBINATIONS; combo++) {
            Map<SignalType, double[]> assignment = new LinkedHashMap<>(); // [strength, decay]
            CycleContextData varied = base;
            for (SignalType type : factors) {
                double s = STRENGTH_LEVELS[rnd.nextInt(STRENGTH_LEVELS.length)];
                double d = DECAY_LEVELS[rnd.nextInt(DECAY_LEVELS.length)];
                assignment.put(type, new double[]{s, d});
                Signal cur = varied.getSignal(type);
                varied = varied.withSignals(replace(varied, type,
                        new Signal(type, s, d, cur.age(), cur.source(), cur.meta())));
            }

            for (Scenario sc : scenarios) {
                Trajectory t = SignalTrajectoryReplay.replay(varied, sc.world(), sc.scripts(), CYCLES);
                Trajectory def = defaults.get(sc.name());
                int divergence = t.firstDecisionDivergence(def);
                boolean diverged = divergence != -1;
                if (diverged) divergedPerScenario.merge(sc.name(), 1, Integer::sum);

                Map<String, Integer> roleCounts = new TreeMap<>();
                int haltCycle = -1;
                for (SignalTrajectoryReplay.CyclePoint pt : t.points()) {
                    pt.executedRoles().forEach(r -> roleCounts.merge(r, 1, Integer::sum));
                    if (pt.halted() && haltCycle == -1) haltCycle = pt.cycle();
                }

                csv.append(stateName).append(',').append(combo).append(',').append(sc.name())
                   .append(',').append(render(assignment).replace(',', ';'))
                   .append(',').append(divergence).append(',').append(haltCycle)
                   .append(',').append(roleCounts.toString().replace(",", ";")).append('\n');

                boolean anyConstituentDiverges = false;
                for (SignalType f : factors) {
                    double[] a = assignment.get(f);
                    if (singleFactorDiverges.getOrDefault(sc.name() + "|" + f.name() + "|s|" + a[0], false)
                            || singleFactorDiverges.getOrDefault(sc.name() + "|" + f.name() + "|d|" + a[1], false)) {
                        anyConstituentDiverges = true;
                        break;
                    }
                }
                if (diverged && !anyConstituentDiverges) {
                    interactionOnlyPerScenario.merge(sc.name(), 1, Integer::sum);
                    if (interactionExamples.size() < 12) {
                        interactionExamples.add(sc.name() + " combo " + combo + " diverges at c"
                                + divergence + " though no constituent single change does: " + render(assignment));
                    }
                }
                for (SignalType f : factors) {
                    double[] a = assignment.get(f);
                    int[] sCell = mainEffect.computeIfAbsent(
                            sc.name() + "|" + f.name() + "|strength=" + a[0], k -> new int[2]);
                    sCell[1]++;
                    if (diverged) sCell[0]++;
                    int[] dCell = mainEffect.computeIfAbsent(
                            sc.name() + "|" + f.name() + "|decay=" + a[1], k -> new int[2]);
                    dCell[1]++;
                    if (diverged) dCell[0]++;
                }
            }
        }

        StringBuilder md = new StringBuilder();
        md.append("\n# State: ").append(stateName).append("\n\n");
        md.append("## Divergence rates\n\n| scenario | diverged / combinations | interaction-only |\n|---|--:|--:|\n");
        for (Scenario sc : scenarios) {
            md.append("| ").append(sc.name()).append(" | ")
              .append(divergedPerScenario.getOrDefault(sc.name(), 0)).append(" / ").append(COMBINATIONS)
              .append(" | ").append(interactionOnlyPerScenario.getOrDefault(sc.name(), 0)).append(" |\n");
        }
        md.append("\n## Interaction-only examples (diverge though every constituent single change is inert)\n\n");
        if (interactionExamples.isEmpty()) md.append("(none found)\n");
        interactionExamples.forEach(e -> md.append("- ").append(e).append("\n"));
        md.append("\n## Main effects: divergence fraction per factor level (only levels deviating ≥0.15 from the scenario mean)\n\n");
        md.append("| scenario | factor level | diverged/total |\n|---|---|--:|\n");
        for (Scenario sc : scenarios) {
            double mean = divergedPerScenario.getOrDefault(sc.name(), 0) / (double) COMBINATIONS;
            mainEffect.forEach((key, cell) -> {
                if (!key.startsWith(sc.name() + "|") || cell[1] == 0) return;
                double frac = cell[0] / (double) cell[1];
                if (Math.abs(frac - mean) >= 0.15) {
                    md.append("| ").append(sc.name()).append(" | ")
                      .append(key.substring(sc.name().length() + 1)).append(" | ")
                      .append(cell[0]).append('/').append(cell[1]).append(" |\n");
                }
            });
        }

        csvAll.append(csv);
        mdAll.append(md);
        System.out.println("[matrix] state=" + stateName + " diverged=" + divergedPerScenario
                + " interactionOnly=" + interactionOnlyPerScenario);
    }

    private static Map<SignalType, Signal> replace(CycleContextData ctx, SignalType type, Signal s) {
        Map<SignalType, Signal> updated = new LinkedHashMap<>(ctx.signals());
        updated.put(type, s);
        return updated;
    }

    private static String render(Map<SignalType, double[]> assignment) {
        StringBuilder sb = new StringBuilder();
        assignment.forEach((t, a) -> sb.append(t.name()).append("=s").append(a[0])
                .append("/d").append(a[1]).append(' '));
        return sb.toString().trim();
    }
}
