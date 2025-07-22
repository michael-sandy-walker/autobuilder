package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.diff.ProjectDiffResult;
import com.walpro.autobuilder.lifecycle.CycleContextData;
import com.walpro.autobuilder.tools.diff.ChangedMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

@RequiredArgsConstructor
@Slf4j
@Component
public class EvaluationCoordinator {

    private final EvaluationAgent evaluationAgent;
    private final ScoringService scoringService;
    private final EvaluationInterpreter evaluationInterpreter;

    public CycleContextData evaluate(CycleContextData context) {
        ProjectDiffResult diff = context.projectDiff();
        Map<String, String> changedFiles = diff.changedFiles();
        Set<String> deletedPaths = diff.deletedPaths();
        List<ChangedMethod> changedMethods = diff.changedMethods();

        Map<String, String> oldFiles = context.previousOutput();

        String scoringGoalId = context.currentSubGoalId() != null
                ? context.currentSubGoalId()
                : context.currentGoalId();

        var scoring = scoringService.computeScores(context.cycleName(), scoringGoalId, oldFiles, changedFiles, deletedPaths);
        ScoreHistoryService scoreHistoryService = scoringService.getScoreHistoryService();

        OptionalDouble last = scoreHistoryService.getLastGoalEvaluation(scoringGoalId);
        Double lastScore = last.isPresent() ? last.getAsDouble() : null;

        EvaluationInput input = EvaluationInput.from(context)
                .withScore(scoring.globalScore())
                .withLastScore(lastScore)
                .withUpdatedFiles(changedFiles.keySet())
                .withDeletedFiles(deletedPaths)
                .withChangedMethods(changedMethods)
                .withCurrentGoal(context.currentGoalDescription())
                .withCurrentSubGoal(context.currentSubGoalDescription())
                .withCurrentGoalId(context.currentGoalId())
                .withCurrentSubGoalId(context.currentSubGoalId())
                .withProjectState(oldFiles)
                .withFindings(context.findings())
                .withScoreHistoryByFile(context.scoreHistoryByFile())
                .withRecentDeltas(context.recentDeltas());

        EvaluationOutcome outcome = evaluationAgent.evaluate(input);
        evaluationAgent.evolveGraph(input, outcome);

        EvaluationInterpreter.InterpretationResult result =
                evaluationInterpreter.interpret(outcome, lastScore, context.repeatedRecombination());

        scoreHistoryService.recordGoalEvaluation(scoringGoalId, outcome.score());
        if (result.success()) {
            scoreHistoryService.recordGoalAcceptance(scoringGoalId, outcome.score());
        }

        return context
                .withEvaluationOutcome(outcome)
                .withLastEvaluationOutcome(outcome)
                .withEvaluationInterpretation(result)
                .withEvaluationFeedback(outcome.feedbackMessage())
                .withRecommendation(outcome.recommendation())
                .withMutationState(result.mutationState())
                .withGoalProgress(result.goalProgress());
    }
}
