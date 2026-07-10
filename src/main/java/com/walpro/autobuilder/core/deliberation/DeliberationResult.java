package com.walpro.autobuilder.core.deliberation;

import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.ParticipationWindow;

import java.util.List;
import java.util.Map;

public record DeliberationResult(
        DeliberationMode mode,
        double confidence,
        String rationale,
        List<String> considerations,
        List<String> evidence,
        Map<String, Object> meta,
        // T4-BZ-3: request-family proposals ride the typed channel carrier.
        List<ChannelRequest> proposedRequests,
        // T4-BZ-4: role-participation grants ride the window carrier. proposedSignals was
        // deleted with the stored map in T4-BZ-5 — every proposal has a typed carrier.
        List<ParticipationWindow> proposedWindows
) {
    /** Compat constructor: no proposals. */
    public DeliberationResult(DeliberationMode mode, double confidence, String rationale,
                              List<String> considerations, List<String> evidence,
                              Map<String, Object> meta) {
        this(mode, confidence, rationale, considerations, evidence, meta, List.of(), List.of());
    }

    /** Compat constructor: requests only. */
    public DeliberationResult(DeliberationMode mode, double confidence, String rationale,
                              List<String> considerations, List<String> evidence,
                              Map<String, Object> meta, List<ChannelRequest> proposedRequests) {
        this(mode, confidence, rationale, considerations, evidence, meta, proposedRequests, List.of());
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

    public DeliberationResult withRequest(ChannelRequest r) {
        if (r == null) return this;
        var next = new java.util.ArrayList<ChannelRequest>(proposedRequests != null ? proposedRequests : List.of());
        next.add(r);
        return new DeliberationResult(mode, confidence, rationale, considerations, evidence, meta,
                java.util.List.copyOf(next), proposedWindows);
    }

    public DeliberationResult withWindow(ParticipationWindow w) {
        if (w == null) return this;
        var next = new java.util.ArrayList<ParticipationWindow>(proposedWindows != null ? proposedWindows : List.of());
        next.add(w);
        return new DeliberationResult(mode, confidence, rationale, considerations, evidence, meta,
                proposedRequests, java.util.List.copyOf(next));
    }
}
