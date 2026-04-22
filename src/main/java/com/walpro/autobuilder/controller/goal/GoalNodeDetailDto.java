package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * T4-AK: per-node detail for the goal explorer. Richer than {@link GoalTreeNodeDto};
 * includes the acceptance criteria list, merged files, resolution, and parent chain.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoalNodeDetailDto(
        String id,
        String description,
        String type,
        String status,
        String role,
        String parentId,
        Instant created,
        Instant completed,
        Instant givenUp,
        String resolution,
        int mergeCount,
        int cumulativeStagnatingCycles,
        Integer planningRunId,
        boolean isCurrent,
        List<CriterionStatusDto> criteria,
        List<String> mergedFiles,
        List<String> ancestry,
        String planRationale
) {}
