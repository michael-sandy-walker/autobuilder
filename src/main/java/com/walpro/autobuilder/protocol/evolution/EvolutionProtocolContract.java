package com.walpro.autobuilder.protocol.evolution;

import java.util.List;
import java.util.stream.Collectors;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.core.ContractRule;
import com.walpro.autobuilder.protocol.core.ProtocolUtils;

public class EvolutionProtocolContract {

    public static final String VERSION = "1.1-evolution";

    private static final List<ContractRule> STATIC_RULES = loadRules();

    private static List<ContractRule> loadRules() {
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
            new ContractRule("AVOID_REPETITION",
                "Avoid repeating identical code or structure from your previous response. Recombination is triggered due to stagnation or low score improvement.",
                "If your last suggestion was: ```java [src/Foo.java] public class Foo { }```, then do not return the exact same file again unless it is meaningfully improved."
            ),
            new ContractRule("STRUCTURAL_VARIATION_ON_RECOMBINE",
                "When recombination is triggered, propose structurally different code or file layouts.",
                "Example: Replace class-based implementation with functional pattern or split one file into multiple."
            ),
            new ContractRule("FINGERPRINT_VARIATION_REQUIRED",
                "Avoid regenerating output with identical fingerprint. Mutation lineage must show structural evolution.",
                "Example: Do not return the exact same file with whitespace-only edits."
            ),
            new ContractRule("ALLOW_FILE_COUNT_CHANGE",
                "During recombination, you may freely split, merge, or consolidate files.",
                "Example: Merge `Foo.java` and `Bar.java` into a new `FooBarUtils.java`"
            )
        );
    }

    public static List<ContractRule> rules() {
        return STATIC_RULES;
    }

    public static String asPromptBlock() {
        return ProtocolUtils.formatRulesBlock("EVOLUTION PROTOCOL CONTRACT v" + VERSION, rules());
    }

    public static String getSystemPrompt() {
        return "You are an autonomous code evolution agent.\n\n" + asPromptBlock();
    }

    public static String getUserPrompt(CycleContextData input, InterpretationResult result) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
            Current Goal: %s (Id: %s)
            Current SubGoal: %s (Id: %s)

            Remaining Subgoals:
            %s

            Completed Subgoals:
            %s

            Structure Summary:
            %s

            Diff Summary (Last Accepted vs Current):
            %s

            Last raw evolution agent response:
            %s

            """.formatted(
            input.currentGoalDescription(), input.currentGoalId(),
            input.currentSubGoalDescription(), input.currentSubGoalId(),
            formatGoalList(input.remainingGoals()),
            formatGoalList(input.completedGoals()),
            input.projectSummary(),
            input.projectDiff().summary(),
            formatLastRawOutput(input.lastEvolutionOutcome())
        ));

        if (result != null) {
            prompt.append("""
                --- MUTATION DIFF SIGNALS ---
                Delta Score (Δ): %.4f (%.3f → %.3f)
                Fingerprint Repeated: %s

                """.formatted(
                result.delta(),
                result.previous(),
                result.current(),
                result.fingerprintRepeated() ? "true" : "false"
            ));
        }

        if (input.recombined()) {
            prompt.append("""
                ⚠️ Recombination Triggered:
                The last evolution and evaluation cycle showed signs of stagnation.
                Please try one or more of the following:
                - Combine ideas from earlier subgoals or variants.
                - Explore alternative or creative approaches.
                - Break out of local optima by suggesting significant structural changes if needed.

                """);

            if (result != null && result.fingerprintRepeated() && input.lastEvolutionOutcome() != null) {
                prompt.append("""
                    ⚠️ The last evolution output was identical to a previous attempt.
                    Please avoid repeating the same file contents or structural choices.

                    --- LAST OUTPUT THAT CAUSED REPETITION ---
                    %s

                    --- END OF LAST OUTPUT ---

                    """.formatted(formatLastRawOutput(input.lastEvolutionOutcome())));
            }

            prompt.append("""
                You may discard local minima and generate novel directions.
                You are allowed to restructure, rename, or redesign components.

                """);
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

        boolean containsCodeBlock = content.contains("```java [") && content.contains("\n");
        boolean containsDeletion = content.contains("#Delete [");

        return containsCodeBlock || containsDeletion;
    }

    private static String formatGoalList(List<? extends GoalEntity> goals) {
        if (goals == null || goals.isEmpty()) return "(none)";
        return goals.stream()
                .map(g -> "- %s: %s".formatted(g.getId(), g.getDescription()))
                .collect(Collectors.joining("\n"));
    }

    private static String formatLastRawOutput(EvolutionOutcome outcome) {
        if (outcome == null || outcome.rawResponse() == null) return "(none)";
        return outcome.rawResponse().trim();
    }
}
