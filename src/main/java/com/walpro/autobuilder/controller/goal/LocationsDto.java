package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Absolute filesystem locations the graph browser surfaces so the user can open the
 * target project, the per-cycle mutation folders, or the goal-state JSON directly in
 * their IDE. All paths are server-side absolute; the frontend may render them as
 * {@code idea://open?file=…} links for IntelliJ or simply as copy-to-clipboard text.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocationsDto(
        String basePath,
        String mutationsDir,
        String metaDir,
        String goalStackFile,
        String logsDir,
        List<String> mutationCycles
) {}
