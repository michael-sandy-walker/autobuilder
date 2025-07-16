package com.walpro.autobuilder.agent.evaluation.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.protocol.ContractRule;
import com.walpro.autobuilder.tools.diff.ChangedMethod;

public class EvaluationProtocolContract {

    public static final String VERSION = "1.0-evaluation";

    private static final List<ContractRule> STATIC_RULES = loadRules();

    private static List<ContractRule> loadRules() {
        List<ContractRule> rules = new ArrayList<>();
        rules.add(new ContractRule("STRUCTURED_RESPONSE",
                "Respond using the required format headers only.",
                """
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: PROGRESSING
                Message: The changes improve modularity and readability.
                """
        ));
        rules.add(new ContractRule("NO_CODE_BLOCKS",
                "Do not include any Java code or file content in this response.",
                "WRONG: ```java\n// code\n```"
        ));
        rules.add(new ContractRule("NO_GOAL_MANIPULATION",
                "Do not propose or modify the goal stack in this context.",
                "WRONG: #GoalStackAppend - Fix goal evaluation"
        ));
        rules.add(new ContractRule("RELEVANT_MESSAGE",
                "Message should be concise and relevant to the mutation impact.",
                "Example: Reduced code duplication across utils."
        ));
        rules.add(new ContractRule("INCLUDE_SCORE",
                "Include a score between 0.0 and 1.0 on its own line, formatted as 'Score: <number>'.",
                "Score: 0.85"
        ));
        rules.add(new ContractRule("REJECTION_EXAMPLE",
                "If the mutation is functionally identical to the last or lacks meaningful improvement, reject it.",
                """
                Score: 0.3
                MutationAcceptanceState: REJECTED
                GoalProgressState: STAGNATING
                Message: Repeated mutation with no structural or functional change.
                """
        ));
        rules.add(new ContractRule("STRUCTURAL_SIGNIFICANCE_REQUIRED",
                "Do not accept mutations that are structurally or semantically insignificant.",
                """
                Score: 0.4
                MutationAcceptanceState: REJECTED
                GoalProgressState: STAGNATING
                Message: Minor formatting change only. No functional or structural improvement detected.
                """
        ));
        rules.add(new ContractRule("OPTIONAL_RECOMMENDATION",
        	    "You may include a Recommendation: line suggesting next steps or improvements for future mutations.",
        	    "Recommendation: Refactor helper methods into dedicated utility class."
        	));
        return rules;
    }

    public static List<ContractRule> rules() {
        return Collections.unmodifiableList(STATIC_RULES);
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

        StringBuilder methodChanges = new StringBuilder();
        if (input.changedMethods() != null && !input.changedMethods().isEmpty()) {
            for (ChangedMethod m : input.changedMethods()) {
                methodChanges.append("- [%s] %s\n".formatted(m.type(), m.name()));

                if (m.oldMethodCode() != null || m.newMethodCode() != null) {
                    methodChanges.append("  Old:\n");
                    methodChanges.append("  >>> ").append(
                        m.oldMethodCode() != null && !m.oldMethodCode().isBlank()
                        ? indentMultiline(m.oldMethodCode(), "  >>> ")
                        : "(none)"
                    ).append("\n");

                    methodChanges.append("  New:\n");
                    methodChanges.append("  <<< ").append(
                        m.newMethodCode() != null && !m.newMethodCode().isBlank()
                        ? indentMultiline(m.newMethodCode(), "  <<< ")
                        : "(none)"
                    ).append("\n");
                }

                methodChanges.append("\n");
            }
        }

        return """
            Evaluate the recent mutation using the following context.

            --- SCORE (GOAL-BASED) ---
            Current Score: %s
            Last Score: %s

            --- GOAL CONTEXT ---
            Current Goal: %s
            Current SubGoal: %s
            Goal Id: %s
            SubGoal Id: %s

            --- FILE CHANGES ---
            Updated Files: %s
            Deleted Files: %s

            --- FINDINGS ---
            %s

            --- METHOD-LEVEL CHANGES ---
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
			(Optional) Recommendation: Suggested improvement or direction for future mutations.
            """.formatted(
            input.score(),
            input.lastScore(),
            input.currentGoal(),
            input.currentSubGoal(),
            input.currentGoalId(),
            input.currentSubGoalId(),
            input.updatedFiles(),
            input.deletedFiles(),
            findings.isBlank() ? "(none)" : findings,
            methodChanges,
            input.projectState().keySet(),
            scoreHistoryBlock,
            deltaBlock
        );
    }

    private static String indentMultiline(String code, String prefix) {
        return code.lines()
                   .map(line -> prefix + line)
                   .collect(Collectors.joining("\n"));
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
                line.trim().toLowerCase().startsWith("message:") &&
                        line.trim().length() > "message:".length());

        boolean hasScoreLine = response.lines().anyMatch(line ->
                line.trim().toLowerCase().startsWith("score:")
        );
        
//        boolean hasOptionalRecommendation = response.lines().anyMatch(line ->
//	        line.trim().toLowerCase().startsWith("recommendation:")
//	    );

        return hasValidMutationState && hasValidGoalProgress && hasMessageLine && hasScoreLine;
    }
}
