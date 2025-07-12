package com.walpro.autobuilder.agent.evolution.protocol;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evolution.model.EvolutionInput;

import java.util.List;

public class EvolutionProtocolContract {

    public static final String VERSION = "1.0-evolution";

    public record ContractRule(String id, String description, String example) {}

    public static List<ContractRule> rules() {
        return List.of(
                new ContractRule("RETURN_CHANGED_FILES",
                        "Return only new or modified files in code blocks.",
                        "```java [src/main/java/com/example/MyClass.java]\n// modified code\n```"
                ),
                new ContractRule("MARK_DELETED_FILES",
                        "Use #Delete [relative/path] to mark deleted files.",
                        "#Delete [src/main/java/com/example/OldCode.java]"
                ),
                new ContractRule("NO_EXPLANATIONS",
                        "Do not include explanations or commentary outside code blocks.",
                        "WRONG: Here is the updated file."
                ),
                new ContractRule("INCLUDE_AT_LEAST_ONE_CHANGE",
                        "You must include at least one modified or deleted file.",
                        "#Delete [...] or ```java [...]``` required"
                ),
                new ContractRule(
                	    "AVOID_REPETITION",
                	    "Avoid repeating identical code or structure from your previous response. Recombination is triggered due to stagnation or low score improvement.",
                	    "If your last suggestion was: ```java [src/Foo.java] public class Foo { }```, then do not return the exact same file again unless it is meaningfully improved."
                )

        );
    }

    public static String asPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("EVOLUTION PROTOCOL CONTRACT v").append(VERSION).append("\n\n");
        for (ContractRule rule : rules()) {
            sb.append("RULE: ").append(rule.id()).append("\n")
                    .append("Description: ").append(rule.description()).append("\n")
                    .append("Example: ").append(rule.example()).append("\n\n");
        }
        return sb.toString();
    }

    public static String getSystemPrompt() {
        return "You are an autonomous code evolution agent.\n\n" + asPromptBlock();
    }

    public static String getUserPrompt(EvolutionInput input, EvaluationInterpreter.InterpretationResult result) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
            Current Goal: %s (Id: %s)
            Current SubGoal: %s (Id: %s)

            Diff Summary:
            %s

            Structure Summary:
            %s

            """.formatted(
                input.currentGoal(), input.currentGoalId(),
                input.currentSubGoal(), input.currentSubGoalId(),
                input.diff().summary(),
                input.structureSummary()
            )
        );

        if (input.recombineFlag()) {
            prompt.append("""
                ⚠️ Recombination Triggered:
                The last evolution and evaluation cycle showed signs of stagnation.
                Please try one or more of the following:
                - Combine ideas from earlier subgoals or variants.
                - Explore alternative or creative approaches.
                - Break out of local optima by suggesting significant structural changes if needed.
                """);
            if (result != null && Math.abs(result.delta()) < 0.01) {
                prompt.append("\n⚠️ Score stagnation detected — Δ=%.4f (%.3f → %.3f)\n"
                        .formatted(result.delta(), result.previous(), result.current()));
            }
            if (result.fingerprintRepeated() && input.lastRawEvolutionOutput() != null) {
                prompt.append("""
                    ⚠️ The last evolution output was identical to a previous attempt.
                    Please avoid repeating the same file contents or structural choices.

                    --- LAST OUTPUT THAT CAUSED REPETITION ---
                    %s

                    --- END OF LAST OUTPUT ---
                    
                    """.formatted(input.lastRawEvolutionOutput().trim()));
            }

            prompt.append("\nYou may override or discard recent ideas.\n\n");
            prompt.append("\nAvoid mixing content from unrelated root goals.\r\n"
            		+ "You may explore earlier subgoals from the *same* goal context if beneficial.");
        }

        prompt.append("""
            Respond with:
            - One or more code blocks for changed files
            - Or one or more #Delete directives

            Do not return unchanged files.
            Do not include commentary outside code blocks.
            """);

        return prompt.toString();
    }


    public static boolean isValidResponse(String content) {
        if (content == null || content.isBlank()) return false;

        boolean containsCodeBlock = content.contains("```");
        boolean containsDeletion = content.contains("#Delete [");

        return containsCodeBlock || containsDeletion;
    }
}
