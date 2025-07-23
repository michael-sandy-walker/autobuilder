package com.walpro.autobuilder.agent.evaluation.heuristic;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analyzer.SemanticDiffAnalyzer;
import com.walpro.autobuilder.lifecycle.CycleContextData;
import com.walpro.autobuilder.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class StructurallyInsignificantChangeHeuristic implements EvaluationHeuristic {

    private final SemanticDiffAnalyzer semanticDiffAnalyzer;
    private final FileService fileService;

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input.projectDiff().changedFiles().isEmpty()) {
            return HeuristicOpinion.none("StructurallyInsignificantChangeHeuristic");
        }

        boolean allTrivial = input.projectDiff().changedFiles().keySet().stream().allMatch(path -> {
            String oldContent = input.previousOutput().get(path);
            String newContent = null;
			try {
				newContent = fileService.readFile(path);
			} catch (IOException e) {
		        log.debug("Cannot read new content for path {} — treating as significant", path);
		        return false; // treat unreadable file as potentially significant (but not here)
			}

		    if (oldContent == null) {
		        log.warn("Missing old content for path {} — treating as significant", path);
		        return false;
		    }

            FileMutation old = new FileMutation(path, oldContent);
            FileMutation now = new FileMutation(path, newContent);
            return !semanticDiffAnalyzer.isStructurallySignificant(old, now);
        });

        double score = outcome.score();
        if (allTrivial && score >= 0.95) {
            String reason = "High score (%.2f) despite only structurally insignificant changes.".formatted(score);
            return HeuristicOpinion.ofWithScore(
                    "StructurallyInsignificantChangeHeuristic",
                    0.75,
                    outcome.mutationState(), // don't override
                    outcome.goalProgress(),
                    reason,
                    Math.min(score, 0.6) // bring score down slightly
            );
        }

        return HeuristicOpinion.none("StructurallyInsignificantChangeHeuristic");
    }
}
