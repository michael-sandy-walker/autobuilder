package com.walpro.autobuilder.model;

import java.util.List;
import java.util.Map;

import lombok.With;

@With
public record MergeTrace(
    List<String> mergedCycles,
    Map<String, String> preMergeContent,
    Map<String, String> postMergeContent
) {}
