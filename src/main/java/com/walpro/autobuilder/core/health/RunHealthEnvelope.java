package com.walpro.autobuilder.core.health;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-CI phase 3: "healthy" as the population of labelled healthy runs says it is -- the frozen feature
 * vectors {@code run_profile.py --freeze} writes -- and a run's features read against it.
 *
 * <p>Direction-aware, as the script: for every feature a HIGH value is the unhealthy side, except the
 * two production rates, where LOW is. Only the unhealthy side is reported; a run doing better than the
 * healthy runs is not a finding. Fewer than {@value #MIN_HEALTHY} healthy vectors is no envelope.
 */
public final class RunHealthEnvelope {

    public static final int MIN_HEALTHY = 5;

    /** Before this many cycles a run has no shape to compare (the script's own floor). */
    public static final int MIN_CYCLES = 3;

    private static final Set<String> LOW_IS_BAD = Set.of("merge_rate", "proposal_rate");

    /** One feature outside the envelope, with the envelope's own numbers so a reader can weigh it. */
    public record Deviation(String feature, double value, double p10, double p50, double p90, boolean lowIsBad) {
        public String describe() {
            return String.format(java.util.Locale.ROOT, "%s = %.3f (healthy runs: p10 %.3f, median %.3f, p90 %.3f; %s is the unhealthy side)",
                    feature, value, p10, p50, p90, lowIsBad ? "low" : "high");
        }
    }

    private final List<Map<String, Double>> healthy;

    public RunHealthEnvelope(Collection<Map<String, Double>> healthyVectors) {
        this.healthy = List.copyOf(healthyVectors);
    }

    public int size() {
        return healthy.size();
    }

    /** The features on their unhealthy side; empty when there is no envelope or no shape yet. */
    public List<Deviation> deviations(Map<String, Double> features) {
        List<Deviation> out = new ArrayList<>();
        if (healthy.size() < MIN_HEALTHY || features == null || features.getOrDefault("cycles", 0.0) < MIN_CYCLES) {
            return out;
        }
        for (String feature : RunHealthProfile.FEATURES) {
            Double v = features.get(feature);
            if (v == null) continue;
            double[] vals = healthy.stream().map(h -> h.get(feature)).filter(x -> x != null)
                    .mapToDouble(Double::doubleValue).sorted().toArray();
            if (vals.length < MIN_HEALTHY) continue;
            double p10 = percentile(vals, 0.10), p50 = percentile(vals, 0.50), p90 = percentile(vals, 0.90);
            boolean lowIsBad = LOW_IS_BAD.contains(feature);
            if (lowIsBad ? v < p10 : v > p90) out.add(new Deviation(feature, v, p10, p50, p90, lowIsBad));
        }
        return out;
    }

    /** Linear interpolation between closest ranks, as {@code run_profile.percentile}. */
    static double percentile(double[] sorted, double p) {
        double k = (sorted.length - 1) * p;
        int lo = (int) k;
        int hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (k - lo);
    }
}
