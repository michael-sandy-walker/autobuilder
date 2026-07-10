package com.walpro.autobuilder.agent.interpretation.model;

import java.util.List;

import com.walpro.autobuilder.core.lifecycle.ChannelRequest;

public record InterpretationOutcome(
        String status,
        String summary,
        String directives,
        // T4-BZ-3: the LLM-as-emitter channel — the three kinds the interpretation prompt may
        // emit (REPLAN_REQUESTED / LOW_CONFIDENCE / DIVERGENCE_REQUEST) are all request-family;
        // the parser enqueues typed requests instead of stored signals (I7, shape unchanged).
        List<ChannelRequest> proposedRequests
) {
    public static InterpretationOutcome noop(String summary) {
        return new InterpretationOutcome("NOOP", summary, "", List.of());
    }
}
