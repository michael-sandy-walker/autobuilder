package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.engine.FrozenCycleObserver.Observation;
import com.walpro.autobuilder.core.engine.FrozenCycleObserver.StubbedServices;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-J lightweight outcome probe (user steer 2026-07-10: hundreds of combinations through a
 * MUCH lighter harness, probing for different OUTCOMES — not trajectory divergence vs a
 * baseline through 20-cycle replays).
 *
 * One probe = one combination of signal values -> ONE call to FrozenCycleObserver.observe()
 * (single frozen cycle, milliseconds) -> one outcome tuple:
 *     mode / executor (first eligible in rank order) / top-ranked role / halted.
 * Thousands of probes per state map the reachable outcome space and its frequencies; the
 * analysis matrix shows, per outcome, which factor levels are over-represented relative to
 * their base rate (enrichment = P(level | outcome) / P(level)).
 *
 * Varied per signal: strength and age — the two same-cycle levers (decayPerCycle is measured
 * same-cycle-inert; a single-cycle probe of it would be noise). Probing decay effects needs
 * the multi-cycle replay, which is the heavyweight instrument this probe deliberately is not.
 */
class SignalOutcomeProbeTest {

    private static final int PROBES = Integer.getInteger("signal.probe.count", 2000);
    private static final long SEED = 7;

    private static final double[] STRENGTH_LEVELS = {0.05, 0.5, 1.0, 2.0, 3.0};
    private static final int[] AGE_LEVELS = {0, 1, 5, 15};

    private static Map<String, FrozenStateFixtures.FrozenState> states() {
        Map<String, FrozenStateFixtures.FrozenState> out = new LinkedHashMap<>();
        for (String name : List.of("canon: missing-symbol-fresh", "canon: pending-evaluation",
                "canon: planner-throttled", "canon: stagnating")) {
            out.put(name.replace("canon: ", ""), FrozenStateFixtures.canonicalStates().stream()
                    .filter(s -> s.name().equals(name)).findFirst().orElseThrow());
        }
        out.put("log-01-boundary", FrozenStateFixtures.logDerivedStates().stream()
                .filter(s -> s.name().startsWith("log-01")).findFirst().orElseThrow());
        return out;
    }

    @Test
    void outcomeProbe_mapsReachableOutcomesAcrossTheValueSpace() throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# T4-J outcome probe — ").append(PROBES)
          .append(" single-cycle probes per state over (strength x age) combinations\n\n");
        md.append("Outcome = mode / executor / topRanked / halted from one frozen-cycle observation. ");
        md.append("Enrichment: how much a factor level is over-represented among probes with that ");
        md.append("outcome vs its base rate; only levels with enrichment >= 1.5 and outcome share >= 2% shown.\n");

        StringBuilder csv = new StringBuilder("state,probe,factorAssignment,mode,executor,topRanked,halted\n");

        for (Map.Entry<String, FrozenStateFixtures.FrozenState> e : states().entrySet()) {
            String stateName = e.getKey();
            CycleContextData base = e.getValue().ctx();
            StubbedServices world = e.getValue().world();
            List<SignalType> factors = presentCarrierTypes(base);

            Random rnd = new Random(SEED);
            Map<String, Integer> outcomeCounts = new TreeMap<>();
            // outcome -> factor-level -> count (for enrichment); plus overall level counts
            Map<String, Map<String, Integer>> levelsByOutcome = new TreeMap<>();
            Map<String, Integer> levelCounts = new TreeMap<>();

            for (int probe = 1; probe <= PROBES; probe++) {
                CycleContextData varied = base;
                List<String> assignment = new ArrayList<>();
                for (SignalType type : factors) {
                    double s = STRENGTH_LEVELS[rnd.nextInt(STRENGTH_LEVELS.length)];
                    int age = AGE_LEVELS[rnd.nextInt(AGE_LEVELS.length)];
                    varied = FrozenCycleObserver.Perturbation.strength(type, s).apply().apply(varied);
                    varied = FrozenCycleObserver.Perturbation.age(type, age).apply().apply(varied);
                    assignment.add(type.name() + "=s" + s + "/a" + age);
                }
                Observation obs = FrozenCycleObserver.observe(varied, world);
                String outcome = obs.mode() + " / exec=" + obs.firstEligibleRole()
                        + " / top=" + obs.topRankedRole() + (obs.haltedBeforeRanking() ? " / HALTED" : "");

                outcomeCounts.merge(outcome, 1, Integer::sum);
                Map<String, Integer> lv = levelsByOutcome.computeIfAbsent(outcome, k -> new TreeMap<>());
                for (String a : assignment) {
                    lv.merge(a, 1, Integer::sum);
                    levelCounts.merge(a, 1, Integer::sum);
                }
                csv.append(stateName).append(',').append(probe).append(',')
                   .append(String.join(";", assignment)).append(',')
                   .append(obs.mode()).append(',').append(obs.firstEligibleRole()).append(',')
                   .append(obs.topRankedRole()).append(',').append(obs.haltedBeforeRanking()).append('\n');
            }

            md.append("\n## State: ").append(stateName).append(" — ")
              .append(outcomeCounts.size()).append(" distinct outcomes\n\n");
            md.append("| outcome | probes | share | most-enriched factor levels |\n|---|--:|--:|---|\n");
            outcomeCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(oc -> {
                        double share = oc.getValue() / (double) PROBES;
                        List<String> enriched = new ArrayList<>();
                        if (share >= 0.02) {
                            Map<String, Integer> lv = levelsByOutcome.get(oc.getKey());
                            lv.entrySet().stream()
                                    .map(le -> Map.entry(le.getKey(),
                                            (le.getValue() / (double) oc.getValue())
                                                    / (levelCounts.get(le.getKey()) / (double) PROBES)))
                                    .filter(en -> en.getValue() >= 1.5)
                                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                                    .limit(4)
                                    .forEach(en -> enriched.add(
                                            en.getKey() + " x" + String.format("%.1f", en.getValue())));
                        }
                        md.append("| ").append(oc.getKey()).append(" | ").append(oc.getValue())
                          .append(" | ").append(String.format("%.1f%%", share * 100))
                          .append(" | ").append(enriched.isEmpty() ? "—" : String.join(", ", enriched))
                          .append(" |\n");
                    });
        }

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "signal-outcome-probe.csv"), csv.toString());
        Files.writeString(Path.of("target", "signal-outcome-probe.md"), md.toString());
        System.out.println("[probe] " + PROBES + " probes x " + states().size()
                + " states -> target/signal-outcome-probe.md (+ .csv)");
        assertThat(Path.of("target", "signal-outcome-probe.md")).exists();
    }

    /** The perturbable carrier types present in a state: role windows + open requests. */
    private static java.util.List<SignalType> presentCarrierTypes(CycleContextData ctx) {
        java.util.List<SignalType> out = new java.util.ArrayList<>();
        ctx.windows().active().keySet().forEach(role -> out.add(BasicSignals.roleSignalFor(role)));
        out.addAll(ctx.requests().open().keySet());
        out.sort(java.util.Comparator.comparing(SignalType::name));
        return out;
    }
}
