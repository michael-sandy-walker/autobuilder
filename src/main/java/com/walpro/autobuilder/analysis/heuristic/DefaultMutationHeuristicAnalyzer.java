package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.analysis.diff.DiffReport;
import com.walpro.autobuilder.analysis.diff.DiffService;
import com.walpro.autobuilder.analysis.diff.DiffType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultMutationHeuristicAnalyzer implements MutationHeuristicAnalyzer {

    private final HeuristicScoringEngine scoringEngine = new HeuristicScoringEngine();
    private final DiffService diffService;

    @Override
    public List<HeuristicFinding> analyze(Map<String, String> oldFiles, Map<String, String> newFiles, Set<String> deletedFiles) {
        DiffReport diffReport = diffService.compareInMemory(oldFiles, newFiles, deletedFiles);
        List<HeuristicFinding> findings = new ArrayList<>();

        if (diffReport.getDeletedFiles().size() > 3) {
            findings.add(new HeuristicFinding(
                    "More than 3 files deleted",
                    HeuristicFinding.Severity.WARNING,
                    "high-deletion-risk",
                    null));
        }

        for (String deletedFile : diffReport.getDeletedFiles()) {
            if (deletedFile.contains("Main") || deletedFile.endsWith("Application.java")) {
                findings.add(new HeuristicFinding(
                        "Core entry point deletion",
                        HeuristicFinding.Severity.CRITICAL,
                        "critical-delete",
                        deletedFile));
            }
        }

        for (Map.Entry<String, String> entry : newFiles.entrySet()) {
            String path = entry.getKey();
            String newContent = entry.getValue();
            String oldContent = oldFiles.get(path);
            if (oldContent != null) {
                double similarity = computeLineSimilarity(oldContent, newContent);
                if (similarity == 1.0) {
                    findings.add(new HeuristicFinding(
                            "File is unchanged from previous cycle",
                            HeuristicFinding.Severity.INFO,
                            "redundant-output",
                            path));
                } else if (similarity > 0.98) {
                    findings.add(new HeuristicFinding(
                            "File is nearly identical (%.1f%% similarity)".formatted(similarity * 100),
                            HeuristicFinding.Severity.INFO,
                            "redundant-output",
                            path));
                }
            }
        }

        // Add more static findings here if needed

        return findings;
    }

    @Override
    public Map<String, Double> scorePerFile(Map<String, String> oldFiles,
                                            Map<String, String> newFiles,
                                            Set<String> deletedFiles,
                                            List<HeuristicFinding> findings) {

        DiffReport diffReport = diffService.compareInMemory(oldFiles, newFiles, deletedFiles);
        Map<String, Double> perFile = new HashMap<>();

        for (String path : newFiles.keySet()) {
            double score = 0.75;

            // Apply heuristic penalties/rewards
            for (HeuristicFinding finding : findings) {
                if (path.equals(finding.path())) {
                    score += heuristicImpact(finding);
                }
            }

            // Diff type
            DiffType type = diffReport.getDiffType(path);

            // Diff magnitude — skip for new files: all their lines are "added" by definition,
            // so penalising by size conflates content size with churn.
            int linesAdded = diffReport.getLinesAdded(path);
            int linesDeleted = diffReport.getLinesDeleted(path);
            int totalChanged = linesAdded + linesDeleted;

            if (type != DiffType.ADDED) {
                if (totalChanged > 50) score -= 0.15;
                else if (totalChanged > 20) score -= 0.05;
                else if (totalChanged <= 5) score += 0.05;
            }
            switch (type) {
                case ADDED -> score += 0.05;
                case DELETED -> score -= 0.15;
                case MODIFIED -> { /* neutral */ }
                default -> { /* ignore */ }
            }

            perFile.put(path, clamp(score, 0.0, 1.0));
        }

        return perFile;
    }

    @Override
    public double scoreGlobal(Map<String, String> oldFiles,
                              Map<String, String> newFiles,
                              Set<String> deletedFiles,
                              List<HeuristicFinding> findings) {
        DiffReport diffReport = diffService.compareInMemory(oldFiles, newFiles, deletedFiles);
        return scoringEngine.score(diffReport, findings);
    }

    private double heuristicImpact(HeuristicFinding finding) {
        return switch (finding.severity()) {
            case CRITICAL -> -0.25;
            case WARNING -> -0.10;
            case INFO -> -0.02;
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private double computeLineSimilarity(String a, String b) {
        List<String> linesA = List.of(a.split("\n"));
        List<String> linesB = List.of(b.split("\n"));

        int common = 0;
        int max = Math.max(linesA.size(), linesB.size());
        int min = Math.min(linesA.size(), linesB.size());

        for (int i = 0; i < min; i++) {
            if (linesA.get(i).trim().equals(linesB.get(i).trim())) {
                common++;
            }
        }
        return max == 0 ? 1.0 : (double) common / max;
    }
}
