package com.walpro.autobuilder.heuristic;

import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.diff.DiffService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DefaultMutationHeuristicAnalyzer implements MutationHeuristicAnalyzer {

    private final HeuristicScoringEngine scoringEngine = new HeuristicScoringEngine();

    @Autowired
    private DiffService diffService;

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
        // Add more static findings here if needed

        return findings;
    }

    public double calculateScore(Map<String, String> oldFiles, Map<String, String> newFiles, Set<String> deletedFiles, List<HeuristicFinding> findings) {
        DiffReport diffReport = diffService.compareInMemory(oldFiles, newFiles, deletedFiles);
        return scoringEngine.score(diffReport, findings);
    }
} 
