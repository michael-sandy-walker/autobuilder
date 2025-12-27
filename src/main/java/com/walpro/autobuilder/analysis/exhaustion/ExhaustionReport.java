package com.walpro.autobuilder.analysis.exhaustion;

import java.util.Map;

public record ExhaustionReport(
        boolean exhausted,
        double confidence,
        String rationale,
        Map<String, Object> evidence
) {
    public static ExhaustionReport none(String rationale) {
        return new ExhaustionReport(false, 0.0, rationale, Map.of());
    }

    public static ExhaustionReport of(double confidence, String rationale, Map<String, Object> evidence) {
        double c = Math.max(0.0, Math.min(1.0, confidence));
        try {
            return new ExhaustionReport(true, c, rationale, evidence == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(evidence)));
        } catch (NullPointerException e) {
            return new ExhaustionReport(true, c, rationale, Map.of());
        }
    }
}
