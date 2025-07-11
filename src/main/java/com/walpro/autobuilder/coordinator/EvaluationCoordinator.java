package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.model.CycleContextData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
@Component
public class EvaluationCoordinator {

    private final EvaluationAgent evaluationAgent;
    private final ScoringService scoringService;
    private final EvaluationInterpreter evaluationInterpreter;

    public CycleContextData evaluate(CycleContextData context) {
        var changed = context.evolutionOutcome().addedOrChangedFiles();
        var deleted = context.evolutionOutcome().deletedFiles();
        Set<String> deletedPaths = Set.copyOf(deleted);

        Map<String, String> oldFiles = context.previousOutput();
        Map<String, String> newFiles = changed.stream()
                .collect(Collectors.toMap(f -> f.path(), f -> f.content()));

        var scoring = scoringService.computeScores(context.cycleName(), oldFiles, newFiles, deletedPaths);

        EvaluationInput input = new EvaluationInput(
                scoring.globalScore(),
                scoring.lastGlobalScore(),
                changed.stream().map(f -> f.path()).collect(Collectors.toSet()),
                deletedPaths,
                context.currentGoalDescription(),
                oldFiles,
                scoring.findings(),
                scoring.scoreHistory(),
                scoring.scoreDeltas()
        );

        EvaluationOutcome outcome = evaluationAgent.evaluate(input);
        evaluationAgent.evolveGraph(input, outcome);

        EvaluationInterpreter.InterpretationResult result = evaluationInterpreter.interpret(outcome, input.lastScore());

        return context
                .withEvaluationOutcome(outcome)
                .withEvaluationInterpretation(result)
                .withEvaluationFeedback(outcome.feedbackMessage())
                .withRecommendation(outcome.recommendation());
    }
}