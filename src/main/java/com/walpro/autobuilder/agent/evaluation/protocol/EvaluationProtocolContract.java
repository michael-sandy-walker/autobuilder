package com.walpro.autobuilder.agent.evaluation.protocol;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;

import java.util.List;
import java.util.stream.Collectors;

public class EvaluationProtocolContract {

    public static final String VERSION = "1.0-evaluation";

    public record ContractRule(String id, String description, String example) {}

    public static List<ContractRule> rules() {
        return List.of(
                new ContractRule("STRUCTURED_RESPONSE",
                        "Respond using the required format headers only.",
                        """
                        MutationAcceptanceState: ACCEPTED
                        GoalProgressState: PROGRESSING
                        Message: The changes improve modularity and readability.
                        """
                ),
                new ContractRule("NO_CODE_BLOCKS",
                        "Do not include any Java code or file content in this response.",
                        "WRONG: ```java\n// code\n```"
                ),
                new ContractRule("NO_GOAL_MANIPULATION",
                        "Do not propose or modify the goal stack in this context.",
                        "WRONG: #GoalStackAppend - Fix goal evaluation"
                ),
                new ContractRule("RELEVANT_MESSAGE",
                        "Message should be concise and relevant to the mutation impact.",
                        "Example: Reduced code duplication across utils."
                ),
                new ContractRule("INCLUDE_SCORE",
                        "Include a score between 0.0 and 1.0 on its own line, formatted as 'Score: <number>'.",
                        "Score: 0.85"
                )

        );
    }

    public static String asPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("EVALUATION PROTOCOL CONTRACT v").append(VERSION).append("\n\n");
        for (ContractRule rule : rules()) {
            sb.append("RULE: ").append(rule.id()).append("\n")
                    .append("Description: ").append(rule.description()).append("\n")
                    .append("Example: ").append(rule.example()).append("\n\n");
        }
        return sb.toString();
    }

    public static String getSystemPrompt() {
        return "You are a mutation evaluation agent.\n\n" + asPromptBlock();
    }

    public static String getUserPrompt(EvaluationInput input) {
        String findings = input.findings().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));

        StringBuilder scoreHistoryBlock = new StringBuilder();
        input.scoreHistoryByFile().forEach((file, history) -> {
            scoreHistoryBlock.append(file).append(": ").append(history).append("\n");
        });

        StringBuilder deltaBlock = new StringBuilder();
        input.recentDeltas().forEach((file, delta) -> {
            deltaBlock.append(file).append(": ").append(String.format("%.2f", delta)).append("\n");
        });

        return """
            Evaluate the recent mutation using the following context.

            --- SCORE ---
            Current Score: %s
            Last Score: %s

            --- GOAL CONTEXT ---
            Current Goal: %s

            --- FILE CHANGES ---
            Updated Files: %s
            Deleted Files: %s

            --- FINDINGS ---
            %s

            --- PROJECT STATE KEYS ---
            %s
            
            --- SCORE HISTORY BY FILE ---
            %s
            
            --- RECENT SCORE DELTAS ---
            %s

            Respond using this exact format:
            Score: [decimal number between 0.0 and 1.0]
            MutationAcceptanceState: ACCEPTED or REJECTED
            GoalProgressState: PROGRESSING, STAGNATING, or NOT_EVALUATED
            Message: One-line explanation of your reasoning.
            """.formatted(
                input.score(),
                input.lastScore(),
                input.currentGoal(),
                input.updatedFiles(),
                input.deletedFiles(),
                findings.isBlank() ? "(none)" : findings,
                input.projectState().keySet(),
                scoreHistoryBlock,
                deltaBlock
        );
    }

    public static boolean isValidResponse(String response) {
        if (response == null || response.isBlank()) return false;

        boolean hasValidMutationState =
                response.contains("MutationAcceptanceState: ACCEPTED") ||
                        response.contains("MutationAcceptanceState: REJECTED");

        boolean hasValidGoalProgress =
                response.contains("GoalProgressState: PROGRESSING") ||
                        response.contains("GoalProgressState: STAGNATING") ||
                        response.contains("GoalProgressState: NOT_EVALUATED");

        boolean hasMessageLine = response.lines().anyMatch(line ->
                line.trim().toLowerCase().startsWith("message:")
                        && line.trim().length() > "message:".length());

        boolean hasScoreLine = response.lines().anyMatch(line ->
                line.trim().toLowerCase().startsWith("score:")
        );

        return hasValidMutationState && hasValidGoalProgress && hasMessageLine && hasScoreLine;
    }
}
