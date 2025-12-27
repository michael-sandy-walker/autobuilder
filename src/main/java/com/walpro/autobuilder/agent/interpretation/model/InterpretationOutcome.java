package com.walpro.autobuilder.agent.interpretation.model;

import java.util.List;

import com.walpro.autobuilder.core.lifecycle.Signal;

public record InterpretationOutcome(
        String status,
        String summary,
        String directives,
        List<Signal> proposedSignals
) {
    public static InterpretationOutcome noop(String summary) {
        return new InterpretationOutcome("NOOP", summary, "", List.of());
    }
}
