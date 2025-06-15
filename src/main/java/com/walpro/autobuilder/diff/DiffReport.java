package com.walpro.autobuilder.diff;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record DiffReport(List<DiffEntry> entries) {

    public List<String> getDeletedFiles() {
        return entries.stream()
                .filter(e -> e.type() == DiffType.DELETED)
                .map(DiffEntry::path)
                .collect(Collectors.toList());
    }

    public List<String> getAddedFiles() {
        return entries.stream()
                .filter(e -> e.type() == DiffType.ADDED)
                .map(DiffEntry::path)
                .collect(Collectors.toList());
    }

    public List<String> getModifiedFiles() {
        return entries.stream()
                .filter(e -> e.type() == DiffType.MODIFIED)
                .map(DiffEntry::path)
                .collect(Collectors.toList());
    }

    public Set<String> getUniqueFilesTouched() {
        return entries.stream()
                .map(DiffEntry::path)
                .collect(Collectors.toSet());
    }

    public List<String> getAllAffectedFiles() {
        return entries.stream()
                .map(DiffEntry::path)
                .distinct()
                .collect(Collectors.toList());
    }
}
