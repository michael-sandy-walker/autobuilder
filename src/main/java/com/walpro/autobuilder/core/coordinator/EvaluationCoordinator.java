package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@Slf4j
@Component
public class EvaluationCoordinator implements AgentCoordinator {

    private final EvaluationAgent evaluationAgent;
    private final ScoringService scoringService;
    private final EvaluationInterpreter evaluationInterpreter;

    public CycleContextData evaluate(CycleContextData context) {
        ProjectDiffResult diff = context.projectDiff();
        Map<String, String> changedFiles = diff.changedFiles();
        Set<String> deletedPaths = diff.deletedPaths();
        Map<String, String> oldFiles = context.previousOutput();

        String scoringGoalId = context.currentSubGoalId() != null
                ? context.currentSubGoalId()
                : context.currentGoalId();

        var scoring = scoringService.computeScores(context.cycleName(), scoringGoalId, oldFiles, changedFiles, deletedPaths);
        ScoreHistoryService scoreHistoryService = scoringService.getScoreHistoryService();
        
        context = context
        	    .withScore(scoring.globalScore())
        	    .withLastScore(scoring.lastGlobalScore())
                .withScoreHistoryByFile(scoring.scoreHistoryByFile())
                .withRecentDeltas(scoring.scoreDeltas())
                .withFindings(scoring.findings());

        EvaluationOutcome outcome = evaluationAgent.evaluate(context);
        evaluationAgent.evolveGraph(context, outcome);

        EvaluationInterpreter.InterpretationResult result =
                evaluationInterpreter.interpret(outcome, scoring.lastGlobalScore(), context.repeatedRecombination());

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

    @Override
    public String role() {
        return "evaluation";
    }

    @Override
    public boolean canExecute(CycleContextData context) {
        // mimic logic currently in the corresponding AgentWrapper
        return true; // minimal version; refine next step
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        return evaluate(context);
    }
}
