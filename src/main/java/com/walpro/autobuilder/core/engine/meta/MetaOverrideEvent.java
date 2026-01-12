package com.walpro.autobuilder.core.engine.meta;

import java.util.List;
import java.util.Map;

public record MetaOverrideEvent(

        String cycleName,

        String reasonCode,              // free-form, emergent
        MetaOverrideCategory category,  // optional, coarse

        String mode,

        String currentGoalId,
        String currentSubGoalId,

        int requestedStepCount,
        int executedStepCount,

        List<Map<String, Object>> skippedSteps,

        Map<String, Double> signalSnapshot,

        String ts
) {
}
