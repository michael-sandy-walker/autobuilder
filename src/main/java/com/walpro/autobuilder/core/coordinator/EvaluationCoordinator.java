package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalBus;
import com.walpro.autobuilder.core.messaging.AgentMessage;
import com.walpro.autobuilder.core.messaging.AgentMessageType;
import com.walpro.autobuilder.core.messaging.AgentRoles;
import com.walpro.autobuilder.core.messaging.MessageBus;
import com.walpro.autobuilder.model.GoalProgressState;
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
    private final SignalBus signalBus;
    private final MessageBus messageBus;

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
                evaluationInterpreter.interpret(outcome, scoring.lastGlobalScore(), false); // TODO: needs further logic regarding currently default false fingerprintRepeated param (MW; 2025-NOV-18)

        scoreHistoryService.recordGoalEvaluation(scoringGoalId, outcome.score());
        if (result.success()) {
            scoreHistoryService.recordGoalAcceptance(scoringGoalId, outcome.score());
        }

        publishStagnationSignal(context, outcome, result);

        context = context
                .withEvaluationOutcome(outcome)
                .withLastEvaluationOutcome(outcome)
                .withEvaluationInterpretation(result)
                .withEvaluationFeedback(outcome.feedbackMessage())
                .withRecommendation(outcome.recommendation())
                .withMutationState(result.mutationState())
                .withGoalProgress(result.goalProgress());

        publishEvaluationInsight(context, outcome);

        return context;
    }

    @Override
    public String role() {
        return "evaluation";
    }

    @Override
    public boolean canExecute(CycleContextData context) {
        return context.hasSignal(BasicSignals.ROLE_EVALUATION);
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing coordinator (role={}):", role());

        context = evaluate(context);

        return context;
    }

    private void publishStagnationSignal(
            CycleContextData context,
            EvaluationOutcome outcome,
            EvaluationInterpreter.InterpretationResult result
    ) {
        if (result == null || result.goalProgress() != GoalProgressState.STAGNATING) {
            return;
        }

        Signal stagnation = new Signal(
                BasicSignals.STAGNATION,
                1.0,          // strong signal
                0.2,          // relatively fast decay if reused elsewhere
                0,            // age
                "evaluation", // source
                Map.of(
                        "cycleName", String.valueOf(context.cycleName()),
                        "score", Double.toString(outcome.score()),
                        "goalId", String.valueOf(context.currentGoalId()),
                        "subGoalId", String.valueOf(context.currentSubGoalId())
                )
        );

        log.warn("EvaluationCoordinator: publishing STAGNATION signal: {}", stagnation.meta());
        signalBus.publish(stagnation);
    }

    private void publishEvaluationInsight(CycleContextData context, EvaluationOutcome outcome) {
        ProjectDiffResult diff = context.projectDiff();

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("cycleName", context.cycleName());
        payload.put("evaluationScore", outcome.score());
        payload.put("goalProgress", outcome.goalProgress());
        payload.put("currentGoalId", context.currentGoalId());
        payload.put("currentSubGoalId", context.currentSubGoalId());

        if (diff != null) {
            payload.put("changedFiles", diff.changedFiles());
            payload.put("deletedPaths", diff.deletedPaths());
        }

        AgentMessage message = new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.PLANNER,
                AgentMessageType.INSIGHT,
                payload
        );

        messageBus.publish(message);
    }
}
