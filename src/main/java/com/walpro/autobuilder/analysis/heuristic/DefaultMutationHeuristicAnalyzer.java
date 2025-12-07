package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.analysis.diff.DiffReport;
import com.walpro.autobuilder.analysis.diff.DiffService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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
     	      findings.add(new HeuristicFinding("Core entry point deletion", HeuristicFinding.Severity.CRITICAL, "critical-delete", deletedFile));
     	   }
     	}
    	
        for (Map.Entry<String, String> entry : newFiles.entrySet()) {
            String path = entry.getKey();
            String newContent = entry.getValue();
            String oldContent = oldFiles.get(path);
            if (oldContent != null) {
                double similarity = computeLineSimilarity(oldContent, newContent);
                if (similarity == 1.0) {
                    findings.add(new HeuristicFinding("File is unchanged from previous cycle", HeuristicFinding.Severity.INFO, "redundant-output", path));
                } else if (similarity > 0.98) {
                    findings.add(new HeuristicFinding("File is nearly identical (%.1f%% similarity)".formatted(similarity * 100), HeuristicFinding.Severity.INFO, "redundant-output", path));
                }
            }
        }
    	
        // Add more static findings here if needed

        return findings;
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

    public double calculateScore(Map<String, String> oldFiles, Map<String, String> newFiles, Set<String> deletedFiles, List<HeuristicFinding> findings) {
        DiffReport diffReport = diffService.compareInMemory(oldFiles, newFiles, deletedFiles);
        return scoringEngine.score(diffReport, findings);
    }
} 
