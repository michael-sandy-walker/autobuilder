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
import com.walpro.autobuilder.core.lifecycle.SignalBus;
import com.walpro.autobuilder.core.messaging.AgentMessage;
import com.walpro.autobuilder.core.messaging.AgentMessageType;
import com.walpro.autobuilder.core.messaging.AgentRoles;
import com.walpro.autobuilder.core.messaging.MessageBus;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.model.GoalProgressState;
import jakarta.annotation.PostConstruct;
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
    private final GoalPlannerService goalPlannerService;

    private Map<String, Object> lastEvolutionRequest = Map.of();

    @PostConstruct
    void initMessageSubscription() {
        messageBus.subscribe(AgentRoles.EVALUATION, this::onAgentMessage);
        log.debug("EvaluationCoordinator subscribed to messages as '{}'", AgentRoles.EVALUATION);
    }

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

        applyGoalProgressToPlanner(context, result);

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
        publishEvaluationResponse(context, outcome);

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

        // Planner: can refine goals based on this
        messageBus.publish(new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.PLANNER,
                AgentMessageType.INSIGHT,
                payload
        ));

        // Evolution: can adjust mutation mode based on the same insight
        messageBus.publish(new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.EVOLUTION,
                AgentMessageType.INSIGHT,
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

    private void applyGoalProgressToPlanner(
            CycleContextData context,
            EvaluationInterpreter.InterpretationResult result
    ) {
        if (result == null) {
            return;
        }

        var progress = result.goalProgress();
        if (progress == null) {
            return;
        }

        // Prefer the current subgoal if present, fall back to current root goal
        String targetGoalId = context.currentSubGoalId() != null
                ? context.currentSubGoalId()
                : context.currentGoalId();

        if (targetGoalId == null) {
            log.debug("EvaluationCoordinator: no currentGoalId/currentSubGoalId set; "
                    + "skipping applyGoalProgressToPlanner.");
            return;
        }

        log.debug("EvaluationCoordinator: applying progress {} to goalId={}", progress, targetGoalId);
        goalPlannerService.applyGoalProgress(targetGoalId, progress);
    }

}
