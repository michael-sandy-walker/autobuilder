package com.walpro.autobuilder.agent.merge.model;

import java.util.Set;

public record MergeNode(
    String path,
    String mergedInCycle,
    Set<String> contributingCycles,
    String contentHash
) {}
