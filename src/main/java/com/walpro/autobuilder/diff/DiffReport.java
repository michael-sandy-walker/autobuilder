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
    
    public int getLinesAdded(String path) {
        return entries.stream()
            .filter(e -> e.path().equals(path))
            .mapToInt(DiffEntry::linesAdded)
            .sum();
    }

    public int getLinesDeleted(String path) {
        return entries.stream()
            .filter(e -> e.path().equals(path))
            .mapToInt(DiffEntry::linesDeleted)
            .sum();
    }

    public DiffType getDiffType(String path) {
        return entries.stream()
            .filter(e -> e.path().equals(path))
            .map(DiffEntry::type)
            .findFirst()
            .orElse(DiffType.MODIFIED);
    }

    public static DiffReport empty() {
        return new DiffReport(List.of());
    }

    public String summary() {
        // Return a useful, human-readable summary here
        return "Modified: " + getModifiedFiles().size() +
                ", Added: " + getAddedFiles().size() +
                ", Deleted: " + getDeletedFiles().size();
    }
  
}
