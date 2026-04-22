package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * T4-AK: node in the goal explorer tree view. Recursive structure — each node carries
 * its children under {@link #subGoals}. Shallow metadata only; rich per-node data
 * (criteria history, merged files) lives on {@link GoalNodeDetailDto}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoalTreeNodeDto(
        String id,
        String description,
        String type,
        String status,
        String role,
        Instant created,
        Instant completed,
        Instant givenUp,
        int mergeCount,
        int cumulativeStagnatingCycles,
        Integer planningRunId,
        int criteriaCount,
        int criteriaMetCount,
        boolean isCurrent,
        List<GoalTreeNodeDto> subGoals
) {}
