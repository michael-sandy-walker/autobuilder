package com.walpro.autobuilder.protocol.meta;

public record MetaActionStep(
        int index,
        Need need,
        String targetRole,
        String request,
        String stopIf,
        String stopCycleIf
) {
    public enum Need { NONE, MISSING_INFO, REDO_LAST, UNSTICK, NEXT_STEP }
}
