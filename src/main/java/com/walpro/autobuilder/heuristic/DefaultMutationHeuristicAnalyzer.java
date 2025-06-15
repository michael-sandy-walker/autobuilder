package com.walpro.autobuilder.heuristic;

import com.walpro.autobuilder.diff.DiffEntry;
import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.diff.DiffService;
import com.walpro.autobuilder.diff.DiffType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DefaultMutationHeuristicAnalyzer implements MutationHeuristicAnalyzer {

    private final HeuristicScoringEngine scoringEngine = new HeuristicScoringEngine();

    @Autowired
    private DiffService diffService;

    @Override
    public List<HeuristicFinding> analyze(Map<String, String> oldFiles, Map<String, String> newFiles) {
        DiffReport diffReport = diffService.compareInMemory(oldFiles, newFiles);
        List<HeuristicFinding> findings = new ArrayList<>();

        if (diffReport.getDeletedFiles().size() > 3) {
            findings.add(new HeuristicFinding(
                    "More than 3 files deleted",
                    HeuristicFinding.Severity.WARNING,
                    "deletion",
                    null));
        }

        // Add more static findings here if needed

        return findings;
    }

    public double calculateScore(Map<String, String> oldFiles, Map<String, String> newFiles, List<HeuristicFinding> findings) {
        DiffReport diffReport = diffService.compareInMemory(oldFiles, newFiles);
        return scoringEngine.score(diffReport, findings);
    }
} 
