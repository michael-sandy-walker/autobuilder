package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One mutation cycle that targeted a particular goal. Returned by
 * {@code GET /autobuilder/goals/{goalId}/cycles} so the explorer can render
 * a per-goal list of cycles + the files each cycle proposed (in lieu of the
 * old global "mutation cycles" folder dump).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoalCycleDto(
        String cycleName,
        boolean merged,
        Long timestamp,
        List<String> files
) {}
