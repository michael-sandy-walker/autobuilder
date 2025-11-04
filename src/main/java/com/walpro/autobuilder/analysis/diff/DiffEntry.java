package com.walpro.autobuilder.analysis.diff;

public record DiffEntry(DiffType type, String path, String oldContent, String newContent, int linesAdded, int linesDeleted) {}