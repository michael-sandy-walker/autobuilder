package com.walpro.autobuilder.protocol.meta;

public record MetaControlDecision(
        NextRole nextRole,
        boolean stop,
        String message,
        String rawResponse
) {
    public enum NextRole {
        PLANNER, EVOLUTION, EVALUATION, MERGE
    }
}
