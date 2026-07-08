package com.walpro.autobuilder.core.deliberation;

import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.protocol.meta.MetaActionStep;

import java.util.List;
import java.util.Map;

public record DeliberationResult(
        DeliberationMode mode,
        double confidence,
        String rationale,
        List<String> considerations,
        List<String> evidence,
        Map<String, Object> meta,
        List<Signal> proposedSignals,
        List<MetaActionStep> metaSteps
) {
    public DeliberationResult {
        // T4-AI-1: typed meta steps ride the result directly — never null. The pipe-string
        // form in meta ("metaControl.steps") is telemetry-only (graph/behaviour log); the
        // engine consumes THIS list, so the lossy string round-trip is gone.
        metaSteps = metaSteps == null ? List.of() : List.copyOf(metaSteps);
    }

    /** Pre-T4-AI-1 shape: no meta steps. Kept so rule-based/noop producers stay unchanged. */
    public DeliberationResult(DeliberationMode mode,
                              double confidence,
                              String rationale,
                              List<String> considerations,
                              List<String> evidence,
                              Map<String, Object> meta,
                              List<Signal> proposedSignals) {
        this(mode, confidence, rationale, considerations, evidence, meta, proposedSignals, List.of());
    }

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
        return new DeliberationResult(mode, confidence, rationale, considerations, evidence, meta,
                java.util.List.copyOf(next), metaSteps);
    }
}
