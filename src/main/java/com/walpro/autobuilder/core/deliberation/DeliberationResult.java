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
        List<String> observations,
        Map<String, Object> meta,
        // T4-BZ-3: request-family proposals ride the typed channel carrier.
        List<ChannelRequest> proposedRequests,
        // T4-BZ-4: role-participation grants ride the window carrier. proposedSignals was
        // deleted with the stored map in T4-BZ-5 — every proposal has a typed carrier.
        List<ParticipationWindow> proposedWindows
) {
    /** Compat constructor: no proposals. */
    public DeliberationResult(DeliberationMode mode, double confidence, String rationale,
                              List<String> considerations, List<String> observations,
                              Map<String, Object> meta) {
        this(mode, confidence, rationale, considerations, observations, meta, List.of(), List.of());
    }

    /** Compat constructor: requests only. */
    public DeliberationResult(DeliberationMode mode, double confidence, String rationale,
                              List<String> considerations, List<String> observations,
                              Map<String, Object> meta, List<ChannelRequest> proposedRequests) {
        this(mode, confidence, rationale, considerations, observations, meta, proposedRequests, List.of());
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

}
