package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.semantic.SemanticDiffAnalyzer;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;

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
            return HeuristicOpinion.none(getClass().getSimpleName());
        }

        boolean allTrivial = input.projectDiff().changedFiles().keySet().stream().allMatch(path -> {
            String oldContent = input.previousOutput().get(path);
            String newContent = input.projectDiff().changedFiles().get(path);

		    if (oldContent == null) {
		        log.debug("Missing old content for path {} — treating as significant", path);
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
