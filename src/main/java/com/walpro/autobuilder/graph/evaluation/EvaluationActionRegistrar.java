package com.walpro.autobuilder.graph.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class EvaluationActionRegistrar {

    private final AgentGraphService agentGraphService;

    private final ObjectMapper objectMapper;

    /** Matches lines of the form {@code [N] ✓ <text>} or {@code [N] ✗ <text>}. */
    private static final Pattern ASSESSMENT_PATTERN =
            Pattern.compile("^\\[(\\d+)]\\s*([\u2713\u2717])\\s*(.*)");

    public void registerEvaluation(String agentId, CycleContextData context, NegotiationResult negotiationResult) {
        if (negotiationResult == null) {
            return;
        }
        EvaluationOutcome outcome = negotiationResult.outcome();
        if (outcome == null) {
        	return;
        }
        
        String actionId = UUID.randomUUID().toString();
        String activeGoalId = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank()
                ? context.currentSubGoalId()
                : context.currentGoalId();

        String state = outcome.mutationState() != null
                ? outcome.mutationState().name()
                : "NOT_EVALUATED";
        
        ActionEntity action = new ActionEntity(
            actionId,
            "evaluation",
            state,
            agentId,
            activeGoalId,
            context.currentGoalId(),
            context.cycleName(),
            Instant.now(),
            context.lastAttemptId(),
            context.parentAttemptId()
        );
        agentGraphService.registerAction(action);

        String jsonPayload;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("goalProgress", outcome.goalProgress() != null ? outcome.goalProgress().name() : null);
            payload.put("mutationState", outcome.mutationState() != null ? outcome.mutationState().name() : null);
            payload.put("scoreNow", outcome.score());
            payload.put("attemptId", context.lastAttemptId());
            payload.put("cycleId", context.cycleName());
            payload.put("goalId", context.currentGoalId());
            payload.put("subGoalId", context.currentSubGoalId());
            payload.put("message", outcome.feedbackMessage());
            payload.put("recommendation", outcome.recommendation());

            List<HeuristicOpinion> allOpinions = negotiationResult.allOpinions();
            if (allOpinions != null && !allOpinions.isEmpty()) {
                List<Map<String, Object>> opinionsPayload = allOpinions.stream()
                        .map(op -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", op.name());
                            entry.put("confidence", op.confidence());
                            entry.put("level", op.level() != null ? op.level().name() : null);
                            entry.put("rationale", op.rationale());
                            entry.put("critical", negotiationResult.criticalOpinions().contains(op));
                            return entry;
                        })
                        .toList();
                payload.put("heuristicOpinions", opinionsPayload);
                payload.put("criticalOpinionCount", negotiationResult.criticalOpinions().size());
            }

            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            // fail open: keep metadataJson null
            jsonPayload = "{\"error\":\"failed_to_serialize_evaluation_payload\"}";
        }

        ArtifactEntity artifact = new ArtifactEntity(
            UUID.randomUUID().toString(),
            "evaluation_payload",
            null,
            jsonPayload,
            outcome.score()
        );

        agentGraphService.linkActionOutput(actionId, artifact);

        // T3-BB: persist per-criterion pass/fail as CriterionResult nodes linked from this action.
        // When the LLM omits CriteriaCheck (common for rejected mutations), fill in any defined
        // criteria not present in the assessments as passed=false so the streak tracker has data.
        List<String> assessments = context.criteriaAssessments();
        List<CriterionResultEntity> criterionResults = parseCriterionResults(
                actionId, assessments, context.cycleName(), context.currentSubGoalId());

        List<String> defined = context.currentSubGoalCriteria();
        if (defined != null && !defined.isEmpty()) {
            Set<Integer> assessedIndices = new HashSet<>();
            for (CriterionResultEntity cr : criterionResults) {
                assessedIndices.add(cr.criterionIndex());
            }
            for (int i = 0; i < defined.size(); i++) {
                int idx = i + 1; // criteria are 1-based
                if (!assessedIndices.contains(idx)) {
                    criterionResults.add(new CriterionResultEntity(
                            actionId, idx, defined.get(i), false,
                            context.cycleName(), context.currentSubGoalId()));
                }
            }
        }

        if (!criterionResults.isEmpty()) {
            agentGraphService.linkCriterionResults(criterionResults);
        }
    }

    private static List<CriterionResultEntity> parseCriterionResults(
            String actionId, List<String> assessments, String cycleId, String subgoalId) {
        List<CriterionResultEntity> results = new ArrayList<>();
        for (String line : assessments) {
            Matcher m = ASSESSMENT_PATTERN.matcher(line.trim());
            if (!m.matches()) continue;
            int index = Integer.parseInt(m.group(1));
            boolean passed = "\u2713".equals(m.group(2));
            // Strip failure reason after em-dash or en-dash to store only the criterion text
            String text = m.group(3).trim().replaceAll("\\s*[\u2014\u2013].*$", "").trim();
            results.add(new CriterionResultEntity(actionId, index, text, passed, cycleId, subgoalId));
        }
        return results;
    }
}
