package com.walpro.autobuilder.agent.merge.model;

import com.walpro.autobuilder.model.CycleContextData;

import java.util.List;

public record MergeInput(
        String path,
        String baseContent,
        List<FileVariant> variants
) {
    public static MergeInput from(CycleContextData ctx) {
        return new MergeInput(
                ctx.mergedPath(),
                ctx.mergedBaseContent(),
                ctx.mergedVariants()
        );
    }
}
