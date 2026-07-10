package com.walpro.autobuilder.core.deliberation;

import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
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
        List<Signal> proposedSignals,
        // T4-BZ-3: request-family proposals ride the typed channel carrier; proposedSignals
        // keeps carrying the ROLE_* window grants until T4-BZ-4 replaces those too.
        List<ChannelRequest> proposedRequests
) {
    /** Compat constructor (pre-T4-BZ-3 shape): no proposed requests. */
    public DeliberationResult(DeliberationMode mode, double confidence, String rationale,
                              List<String> considerations, List<String> evidence,
                              Map<String, Object> meta, List<Signal> proposedSignals) {
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
                List.of(),
                List.of()
        );
    }

    public DeliberationResult withSignal(Signal s) {
        if (s == null) return this;
        var next = new java.util.ArrayList<Signal>(proposedSignals != null ? proposedSignals : List.of());
        next.add(s);
        return new DeliberationResult(mode, confidence, rationale, considerations, evidence, meta,
                java.util.List.copyOf(next), proposedRequests);
    }

    public DeliberationResult withRequest(ChannelRequest r) {
        if (r == null) return this;
        var next = new java.util.ArrayList<ChannelRequest>(proposedRequests != null ? proposedRequests : List.of());
        next.add(r);
        return new DeliberationResult(mode, confidence, rationale, considerations, evidence, meta,
                proposedSignals, java.util.List.copyOf(next));
    }
}
