package com.walpro.autobuilder.analysis.exhaustion;

import java.util.Map;

public record ExhaustionReport(
        boolean exhausted,
        double confidence,
        String rationale,
        Map<String, Object> observations
) {
    public static ExhaustionReport none(String rationale) {
        return new ExhaustionReport(false, 0.0, rationale, Map.of());
    }

    public static ExhaustionReport of(double confidence, String rationale, Map<String, Object> observations) {
        double c = Math.max(0.0, Math.min(1.0, confidence));
        try {
            return new ExhaustionReport(true, c, rationale, observations == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(observations)));
        } catch (NullPointerException e) {
            return new ExhaustionReport(true, c, rationale, Map.of());
        }
    }
}
