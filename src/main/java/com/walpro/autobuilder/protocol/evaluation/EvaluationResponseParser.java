package com.walpro.autobuilder.protocol.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class EvaluationResponseParser {

    private static final Pattern SCORE_PATTERN =
            Pattern.compile("(?i)^score:\\s*([0-9]*\\.?[0-9]+)");

    public EvaluationOutcome parse(String content) {
        Double score = parseScore(content);
        MutationAcceptanceState mutationState = parseMutationState(content);
        GoalProgressState goalState = parseGoalProgress(content);
        String message = parseMessage(content);
        String recommendation = parseRecommendation(content);

        return new EvaluationOutcome(score, mutationState, goalState, message, recommendation);
    }

    private Double parseScore(String content) {
        if (content == null || content.isBlank()) return null;

        return content.lines()
                .map(String::trim)
                .map(SCORE_PATTERN::matcher)
                .filter(Matcher::find)
                .map(m -> Double.parseDouble(m.group(1)))
                .filter(v -> v >= 0.0 && v <= 1.0)
                .findFirst()
                .orElse(null);
    }

    private MutationAcceptanceState parseMutationState(String content) {
        if (content == null) return MutationAcceptanceState.REJECTED;

        if (content.contains("MutationAcceptanceState: REJECTED")) {
            return MutationAcceptanceState.REJECTED;
        } else if (content.contains("MutationAcceptanceState: ACCEPTED")) {
            return MutationAcceptanceState.ACCEPTED;
        } else if (content.contains("MutationAcceptanceState: NOT_EVALUATED")) {
            return MutationAcceptanceState.NOT_EVALUATED;
        }
        return MutationAcceptanceState.REJECTED;
    }

    private GoalProgressState parseGoalProgress(String content) {
        if (content == null) return GoalProgressState.NOT_EVALUATED;

        if (content.contains("GoalProgressState: PROGRESSING")) {
            return GoalProgressState.PROGRESSING;
        } else if (content.contains("GoalProgressState: STAGNATING")) {
            return GoalProgressState.STAGNATING;
        } else if (content.contains("GoalProgressState: NOT_EVALUATED")) {
            return GoalProgressState.NOT_EVALUATED;
        } else if (content.contains("GoalProgressState: REGRESSING")) {
            return GoalProgressState.REGRESSING;
        } else if (content.contains("GoalProgressState: FULFILLED")) {
            return GoalProgressState.FULFILLED;
        } else if (content.contains("GoalProgressState: GIVEN_UP")) {
            return GoalProgressState.GIVEN_UP;
        }
        return GoalProgressState.NOT_EVALUATED;
    }

    private String parseMessage(String content) {
        if (content == null || content.isBlank()) return "No explanation provided.";

        return content.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("message:"))
                .map(line -> line.substring("message:".length()).trim())
                .findFirst()
                .orElse("No explanation provided.");
    }

    private String parseRecommendation(String content) {
        if (content == null || content.isBlank()) return null;

        return content.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("recommendation:"))
                .map(line -> line.substring("recommendation:".length()).trim())
                .findFirst()
                .orElse(null);
    }
}