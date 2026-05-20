package com.walpro.autobuilder.core.deliberation;

import com.walpro.autobuilder.core.lifecycle.Signal;

import java.util.List;
import java.util.Map;

public record DeliberationResult(
        DeliberationMode mode,
        double confidence,
        String rationale,
        List<String> considerations,
        List<String> evidence,
        Map<String, Object> meta,
        List<Signal> proposedSignals
) {
    public static DeliberationResult noop(String rationale) {
        return new DeliberationResult(
                DeliberationMode.UNDIRECTED,
                0.0,
                rationale,
                List.of(),
                List.of(),
                Map.of(),
                List.of()
        );
    }

    public DeliberationResult withSignal(Signal s) {
        if (s == null) return this;
        var next = new java.util.ArrayList<Signal>(proposedSignals != null ? proposedSignals : List.of());
        next.add(s);
        return new DeliberationResult(mode, confidence, rationale, considerations, evidence, meta, java.util.List.copyOf(next));
    }
}
