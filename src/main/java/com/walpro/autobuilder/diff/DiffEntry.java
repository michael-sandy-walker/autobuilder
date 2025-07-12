package com.walpro.autobuilder.diff;

public record DiffEntry(DiffType type, String path, String oldContent, String newContent, int linesAdded, int linesDeleted) {}