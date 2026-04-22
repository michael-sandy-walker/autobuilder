package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * T4-AM: flat per-node payload for the live 3D graph stream. A smaller alternative to
 * {@link GoalTreeNodeDto} — omits the recursive child list and instead uses {@code parentId}
 * for client-side linking, which is cheaper to re-encode every cycle.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoalStateSnapshotDto(
        String id,
        String parentId,
        String description,
        String type,
        String status,
        String role,
        int mergeCount,
        int cumulativeStagnatingCycles,
        Integer planningRunId,
        int criteriaCount,
        int criteriaMetCount,
        boolean isCurrent
) {}
