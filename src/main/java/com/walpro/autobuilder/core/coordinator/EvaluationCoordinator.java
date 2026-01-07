package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.messaging.AgentMessage;
import com.walpro.autobuilder.core.messaging.AgentMessageType;
import com.walpro.autobuilder.core.messaging.AgentRoles;
import com.walpro.autobuilder.core.messaging.MessageBus;
import com.walpro.autobuilder.model.GoalProgressState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RequiredArgsConstructor
@Slf4j
@Component
public class EvaluationCoordinator implements AgentCoordinator {

    private final EvaluationAgent evaluationAgent;
    private final ScoringService scoringService;
    private final EvaluationInterpreter evaluationInterpreter;
    private final MessageBus messageBus;

    private Map<String, Object> lastEvolutionRequest = Map.of();

    @PostConstruct
    void initMessageSubscription() {
        messageBus.subscribe(AgentRoles.EVALUATION, this::onAgentMessage);
        log.debug("EvaluationCoordinator subscribed to messages as '{}'", AgentRoles.EVALUATION);
    }

    public CycleContextData evaluate(CycleContextData context) {
        ProjectDiffResult diff = context.projectDiff();
        if (diff == null) {
            log.warn("EvaluationCoordinator projectDiff is null.");
            return context;
        }
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
        } else {
            context = context.withSignal(
                    Signal.of(
                            BasicSignals.LOW_CONFIDENCE,
                            1.0,
                            0.05,
                            "evaluation"
                    )
            );
        }

        context = context
                .withEvaluationOutcome(outcome)
                .withLastEvaluationOutcome(outcome)
                .withEvaluationInterpretation(result)
                .withEvaluationFeedback(outcome.feedbackMessage())
                .withRecommendation(outcome.recommendation())
                .withMutationState(result.mutationState())
                .withGoalProgress(result.goalProgress())
                .withLastEvaluatedEvolutionFingerprint(context.lastEvolutionFingerprint());

        if (result.goalProgress() == GoalProgressState.STAGNATING) {
            context = context.withSignal(
                    Signal.of(BasicSignals.STAGNATION, 1.0, 0.2, "evaluation")
            );
        }

        publishEvaluationInsight(context, outcome);
        publishEvaluationResponse(context, outcome);

        return context;
    }

    @Override
    public String role() {
        return "evaluation";
    }

    @Override
    public boolean canExecute(CycleContextData context) {
        if (context == null) return false;

        // 1) Needs role signal
        if (!context.hasStrongSignal(BasicSignals.ROLE_EVALUATION, 0.10)) return false;

        // 2) Needs something to evaluate
        if (context.projectDiff() == null) return false;

        return !Objects.equals(context.lastEvolutionFingerprint(), context.lastEvaluatedEvolutionFingerprint());
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing coordinator (role={})", role());

        context = evaluate(context);

        return context;
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

        // Planner: can refine goals based on this
        messageBus.publish(new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.PLANNER,
                AgentMessageType.INSIGHT,
                "refine",
                null,
                payload
        ));

        // Evolution: can adjust mutation mode based on the same insight
        messageBus.publish(new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.EVOLUTION,
                AgentMessageType.INSIGHT,
                "adjust",
                null,
                payload
        ));
    }

    void onAgentMessage(AgentMessage message) {
        if (message.type() == AgentMessageType.REQUEST
                && AgentRoles.EVOLUTION.equals(message.fromAgent())) {

            log.debug("EvaluationCoordinator received Evolution REQUEST: {}", message.payload());

            handleEvolutionRequest(message);
        }
    }

    private void handleEvolutionRequest(AgentMessage message) {
        // We do not mutate context here directly because coordinators receive context per-cycle.
        // Instead, we record the payload in a simple buffer and the next evaluate(...) call can access it.

        this.lastEvolutionRequest = message.payload();  // <--- step A2: store only
        log.debug("EvaluationCoordinator stored Evolution REQUEST payload for next evaluation.");
    }

    // package-private getter for tests
    Map<String, Object> getLastEvolutionRequest() {
        return lastEvolutionRequest;
    }

    private void publishEvaluationResponse(CycleContextData context, EvaluationOutcome outcome) {
        if (lastEvolutionRequest == null || lastEvolutionRequest.isEmpty()) {
            // No request received; nothing to respond to
            return;
        }

        AgentMessage response = new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.EVOLUTION,
                AgentMessageType.RESPONSE,
                "evolve",
                null,
                Map.of(
                        "cycleName", context.cycleName(),
                        "evaluationScore", outcome.score(),
                        "goalProgress", outcome.goalProgress(),
                        "changedFiles", context.projectDiff() != null
                                ? context.projectDiff().changedFiles()
                                : Map.of(),
                        "deletedPaths", context.projectDiff() != null
                                ? context.projectDiff().deletedPaths()
                                : Set.of()
                )
        );

        messageBus.publish(response);
    }

}
