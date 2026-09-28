package com.walpro.autobuilder.core.health;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-CI phase 3: the run's shape so far, as the eleven features {@code scripts/run_profile.py} computes
 * over a finished run's behaviour stream -- here accumulated event by event while the run is live.
 *
 * <p>Every feature is a per-cycle rate or a share, so a run at cycle 12 compares honestly with the
 * finished healthy runs of the envelope. The definitions are the script's, one for one; a parity test
 * holds them together, because the envelope the loop is compared against was frozen by the script.
 *
 * <p>Pure and not thread-safe on its own; {@link RunHealth} serialises access.
 */
public final class RunHealthProfile {

    public static final List<String> FEATURES = List.of("merge_rate", "reject_rate", "inject_rate",
            "build_fix_rate", "skip_rate", "proposal_rate", "planner_only_share", "max_inject_refires",
            "inject_refire_share", "merge_drought_share", "tail_share");

    private final Map<String, Integer> counts = new HashMap<>();
    private final Map<String, Set<String>> rolesPerCycle = new LinkedHashMap<>();
    private final Set<String> mergedCycles = new HashSet<>();
    private final Map<String, Integer> injections = new HashMap<>();
    private final List<Integer> mergeIndexes = new ArrayList<>();
    private int events;

    /** One behaviour event, in emission order. */
    public void observe(String event, String cycleId, Map<String, ?> payload) {
        int index = events++;
        if (event == null) return;
        counts.merge(event, 1, Integer::sum);
        Map<String, ?> p = payload == null ? Map.of() : payload;
        if (cycleId != null) {
            switch (event) {
                case "agent_selected" -> rolesPerCycle.computeIfAbsent(cycleId, k -> new HashSet<>())
                        .add(String.valueOf(p.get("role")));
                case "mutation_proposed" -> rolesPerCycle.computeIfAbsent(cycleId, k -> new HashSet<>()).add("_proposed");
                case "mutation_merged" -> mergedCycles.add(cycleId);
                default -> { }
            }
        }
        if (event.equals("mutation_injected")) {
            injections.merge(p.get("rule") + "\u0000" + p.get("missingToken"), 1, Integer::sum);
        }
        if (event.equals("mutation_merged")) mergeIndexes.add(index);
    }

    public int cycles() {
        return Math.max(1, counts.getOrDefault("cycle_start", 0));
    }

    /** The feature vector; empty before the first event. */
    public Map<String, Double> features() {
        Map<String, Double> f = new LinkedHashMap<>();
        if (events == 0) return f;
        double cycles = cycles();
        f.put("cycles", cycles);
        f.put("merge_rate", count("mutation_merged") / cycles);
        f.put("reject_rate", count("mutation_rejected") / cycles);
        f.put("inject_rate", count("mutation_injected") / cycles);
        f.put("build_fix_rate", count("build_fix_attempt") / cycles);
        f.put("skip_rate", count("agent_skipped") / cycles);
        f.put("proposal_rate", count("mutation_proposed") / cycles);

        long plannerOnly = rolesPerCycle.entrySet().stream()
                .filter(e -> e.getValue().contains("planner") && !e.getValue().contains("_proposed")
                        && !mergedCycles.contains(e.getKey()))
                .count();
        f.put("planner_only_share", plannerOnly / (double) Math.max(1, rolesPerCycle.size()));

        int maxRefires = injections.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int injected = injections.values().stream().mapToInt(Integer::intValue).sum();
        int refires = injections.values().stream().mapToInt(v -> v - 1).sum();
        f.put("max_inject_refires", (double) maxRefires);
        f.put("inject_refire_share", injected == 0 ? 0.0 : refires / (double) Math.max(1, injected));

        if (mergeIndexes.isEmpty()) {
            f.put("merge_drought_share", 1.0);
            f.put("tail_share", 1.0);
        } else {
            int prev = 0;
            int longest = 0;
            List<Integer> ends = new ArrayList<>(mergeIndexes);
            ends.add(events - 1);
            for (int i : ends) {
                longest = Math.max(longest, i - prev);
                prev = i;
            }
            f.put("merge_drought_share", longest / (double) Math.max(1, events));
            f.put("tail_share", (events - 1 - mergeIndexes.get(mergeIndexes.size() - 1)) / (double) Math.max(1, events));
        }
        return f;
    }

    private double count(String event) {
        return counts.getOrDefault(event, 0);
    }
}
