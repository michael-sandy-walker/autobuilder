package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * T4-AK: cross-goal file overlay — shows which subgoals have merged mutations
 * to the same file, surfacing redundant or conflicting evolutions side by side.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileOverlayDto(
        String fileName,
        List<String> goalIds
) {}
