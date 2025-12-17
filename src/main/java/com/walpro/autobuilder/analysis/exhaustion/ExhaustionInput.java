package com.walpro.autobuilder.analysis.exhaustion;

import java.util.List;

public record ExhaustionInput(
        String goalId,
        List<Double> lastScores,
        List<String> lastGoalProgressStates,
        List<Boolean> lastAccepted,
        List<Boolean> fingerprintRepeatedFlags,
        int lastNDiffChangedFiles,
        int lastNDiffChangedMethods
) {}
