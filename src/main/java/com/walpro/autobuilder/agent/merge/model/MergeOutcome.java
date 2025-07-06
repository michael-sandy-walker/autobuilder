package com.walpro.autobuilder.agent.merge.model;

public record MergeOutcome(
        boolean successful,
        String mergedContent,
        String reasoning // optional: "Merged logging and Swagger annotations, preserved error handling."
) {}
