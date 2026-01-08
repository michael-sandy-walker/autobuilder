package com.walpro.autobuilder.protocol.meta;

public record MetaDeliberationDecision(
        boolean stop,
        Mode mode,
        Need need,
        String targetRole,
        String request,
        String rationale,
        String rawResponse
) {
    public enum Mode { CONTINUE, PAUSE, NOOP }
    public enum Need { NONE, MISSING_INFO, REDO_LAST, UNSTICK, NEXT_STEP }
}
