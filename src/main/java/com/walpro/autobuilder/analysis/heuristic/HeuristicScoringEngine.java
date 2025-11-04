package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.analysis.diff.DiffEntry;
import com.walpro.autobuilder.analysis.diff.DiffReport;
import com.walpro.autobuilder.analysis.diff.DiffType;
import com.walpro.autobuilder.analysis.heuristic.HeuristicFinding.Severity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class HeuristicScoringEngine {

    public double score(DiffReport diffReport, List<HeuristicFinding> findings) {
        double score = 1.0;

        List<DiffEntry> entries = diffReport.entries().stream()
                .filter(entry -> !isNoise(entry.path()))
                .collect(Collectors.toList());

        int total = entries.size();

        Map<DiffType, List<DiffEntry>> byType = entries.stream()
                .collect(Collectors.groupingBy(DiffEntry::type));

        int deleted = byType.getOrDefault(DiffType.DELETED, List.of()).size();
        int added = byType.getOrDefault(DiffType.ADDED, List.of()).size();
        int modified = byType.getOrDefault(DiffType.MODIFIED, List.of()).size();

        Set<String> touchedFiles = entries.stream()
                .map(DiffEntry::path)
                .collect(Collectors.toSet());

        // High deletion ratio
        if (total > 0) {
            double deletionRatio = (double) deleted / total;
            if (deletionRatio > 0.5) {
            	String description = "More than 50% of changed files were deleted";
            	if (!isDuplicateFindingsDescription(findings, description)) {
	                findings.add(new HeuristicFinding("More than 50% of changed files were deleted", Severity.CRITICAL, "deletion-ratio", null));
	                score -= 0.3;
            	}
            } else if (deletionRatio > 0.25) {
            	String description = "More than 25% of changes were deletions";
            	if (!isDuplicateFindingsDescription(findings, description)) {
	                findings.add(new HeuristicFinding("More than 25% of changes were deletions", Severity.WARNING, "deletion-ratio", null));
	                score -= 0.15;
            	}
            }
        }

        // Addition-to-deletion ratio
        if (deleted > 0 && added > deleted * 2) {
        	String description = "Many more files added than deleted";
        	if (!isDuplicateFindingsDescription(findings, description)) {
        		findings.add(new HeuristicFinding(description, Severity.INFO, "add-delete-ratio", null));
                score += 0.05;
        	}
        }

        // Penalize risky deletions
        for (DiffEntry entry : byType.getOrDefault(DiffType.DELETED, List.of())) {
            if (entry.path().endsWith(".java") || entry.path().endsWith(".xml")) {
                findings.add(new HeuristicFinding("Deleted important file: " + entry.path(), Severity.CRITICAL, "risky-deletion", entry.path()));
                score -= 0.1;
            }
        }

        // Reward for minor edits
        if (deleted == 0 && added == 0 && modified <= 3) {
        	String description = "Only a few files modified, no adds/deletes";
        	if (!isDuplicateFindingsDescription(findings, description)) {
        		findings.add(new HeuristicFinding("Only a few files modified, no adds/deletes", Severity.INFO, "minor-edits", null));
                score += 0.1;
        	}
        }

        // Penalize single-file focus
        if (touchedFiles.size() == 1 && total > 1) {
        	String description = "All changes concentrated in a single file";
        	if (!isDuplicateFindingsDescription(findings, description)) {
        		findings.add(new HeuristicFinding("All changes concentrated in a single file", Severity.WARNING, "single-file-focus", touchedFiles.iterator().next()));
        		score -= 0.1;
        	}
        }

        return Math.min(1.0, Math.max(0.0, score));
    }

    private boolean isNoise(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".reply.txt") || lower.contains("/logs/") || lower.contains("/responses/") || lower.endsWith(".jsonl");
    }
    
    private boolean isDuplicateFindingsDescription(List<HeuristicFinding> findings, String description) {
    	return findings != null && findings.stream().anyMatch(finding -> finding.description().equals(description));
    }
}
