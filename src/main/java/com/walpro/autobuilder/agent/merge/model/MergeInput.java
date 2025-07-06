package com.walpro.autobuilder.agent.merge.model;

import java.util.List;

public record MergeInput(
        String path,
        String baseContent,
        List<FileVariant> variants
) {}