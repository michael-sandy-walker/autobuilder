package com.walpro.autobuilder.agent.evaluation.protocol;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import java.util.Locale;

public class EvaluationResponseParser {

    public EvaluationOutcome parse(String content) {
        double score = parseScore(content);
        MutationAcceptanceState mutationState = parseMutationState(content);
        GoalProgressState goalState = parseGoalProgress(content);
        String message = parseMessage(content);
        String recommendation = parseRecommendation(content);
        
        return new EvaluationOutcome(score, mutationState, goalState, message, recommendation);
    }

    private double parseScore(String content) {
        return content.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("score:"))
                .map(line -> line.substring("score:".length()).trim())
                .mapToDouble(s -> {
                    try {
                        return Double.parseDouble(s);
                    } catch (NumberFormatException e) {
                        return -1.0;
                    }
                })
                .findFirst()
                .orElse(-1.0);
    }

    private MutationAcceptanceState parseMutationState(String content) {
        if (content.contains("MutationAcceptanceState: REJECTED")) {
            return MutationAcceptanceState.REJECTED;
        } else if (content.contains("MutationAcceptanceState: ACCEPTED")) {
            return MutationAcceptanceState.ACCEPTED;
        }
        return MutationAcceptanceState.REJECTED; // fail-safe default
    }

    private GoalProgressState parseGoalProgress(String content) {
        if (content.contains("GoalProgressState: PROGRESSING")) {
            return GoalProgressState.PROGRESSING;
        } else if (content.contains("GoalProgressState: STAGNATING")) {
            return GoalProgressState.STAGNATING;
        } else if (content.contains("GoalProgressState: NOT_EVALUATED")) {
            return GoalProgressState.NOT_EVALUATED;
        }
        return GoalProgressState.NOT_EVALUATED;
    }

    private String parseMessage(String content) {
        return content.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("message:"))
                .map(line -> line.substring("message:".length()).trim())
                .findFirst()
                .orElse("No explanation provided.");
    }
    
    private String parseRecommendation(String content) {
        return content.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("recommendation:"))
                .map(line -> line.substring("recommendation:".length()).trim())
                .findFirst()
                .orElse(null);
    }
}
