package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * T4-AK: per-criterion status for a subgoal.
 * Combines goal-metadata pass/fail state (cumulative) with graph-derived history
 * (last evaluator pass, consecutive fail streak).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CriterionStatusDto(
        int index,
        String text,
        boolean cumulativePassed,
        int failStreak,
        boolean neverAssessed,
        Boolean lastPassed,
        boolean everPassed,
        int consecutiveFailCount
) {}
