package com.walpro.autobuilder.protocol.meta;

import com.walpro.autobuilder.core.deliberation.DeliberationMode;

import java.util.List;

public record MetaDeliberationPlan(
        boolean stop,
        DeliberationMode mode,
        String rationale,
        List<MetaActionStep> steps,
        String rawResponse
) {
    public enum Mode { CONTINUE, PAUSE, NOOP }

    public boolean hasSteps() {
        return steps != null && !steps.isEmpty();
    }
}
