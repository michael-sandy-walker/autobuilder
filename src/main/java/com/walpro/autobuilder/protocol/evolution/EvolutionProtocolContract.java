package com.walpro.autobuilder.protocol.evolution;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.core.ContractRule;
import com.walpro.autobuilder.protocol.core.ProblemContextPromptBlocks;
import com.walpro.autobuilder.protocol.core.ProtocolUtils;

public class EvolutionProtocolContract {

    public static final String VERSION = "1.1-evolution";

    private static final List<ContractRule> STATIC_RULES = loadRules();

    // Accept any fenced code block that includes a file header like:
    // ```java [src/...]
    // ```properties [src/...]
    // ```xml [pom.xml]
    // ``` [src/...]
    private static final Pattern FILE_CODE_BLOCK =
            Pattern.compile("(?s)```[^\\n`]*\\[[^\\]\\n]+\\]\\s*\\n.*?\\n```");

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
            ),
            new ContractRule(
                    "PROBLEM_TARGETING",
                    "If a PROBLEM CONTEXT block is present and OpenProblemsInScope > 0, your mutation must target resolving those problems. Prefer edits in files referenced by keywords (path:...). Avoid unrelated refactors.",
                    """
                    ```java [src/main/java/com/example/demo/HelloController.java]
                    package com.example.demo;
        
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
        
                    @RestController
                    public class HelloController {
        
                        @GetMapping("/hello")
                        public String hello() {
                            return "Hello, Autobuilder!";
                        }
                    }
                    ```
                    """
            ),
            new ContractRule(
                    "BLOCKERS_ONLY",
                    "If BlockingInScope > 0, focus exclusively on blocker-related changes. Do not introduce new features or cosmetic refactors unrelated to the blocker keywords.",
                    """
                    ```java [src/main/java/com/example/demo/HelloController.java]
                    package com.example.demo;
        
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
        
                    @RestController
                    public class HelloController {
        
                        // Focused change addressing blocker keyword(s) without unrelated edits
                        @GetMapping("/hello")
                        public String hello() {
                            return "Hello, Autobuilder!";
                        }
                    }
                    ```
                    """
            ),
            new ContractRule(
                    "KEYWORD_ANCHORING",
                    "Explicitly align your code changes with at least one problem keyword by changing the structure that plausibly caused it (e.g., reduce redundancy for REDUNDANT_OUTPUT, introduce meaningful structural change for NO_MEANINGFUL_CHANGE). Do not repeat prior fingerprints.",
                    """
                    ```java [src/main/java/com/example/demo/HelloController.java]
                    package com.example.demo;
        
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
        
                    @RestController
                    public class HelloController {
        
                        private static final String MESSAGE = "Hello, Autobuilder!";
        
                        @GetMapping("/hello")
                        public String hello() {
                            return MESSAGE;
                        }
                    }
                    ```
                    """
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
        return getUserPrompt(input, result, input.hasSignal(BasicSignals.DIVERGENCE_REQUEST));
    }

    public static String getUserPrompt(CycleContextData input, InterpretationResult result, boolean divergent) {
        StringBuilder prompt = new StringBuilder();

        String projectSummary = input.projectSummary();
        if (projectSummary == null || projectSummary.isBlank()) {
            projectSummary = "(no project summary available yet)";
        }

        String diffSummary = "(no diff available yet)";
        if (input.projectDiff() != null) {
            String s = input.projectDiff().summary();
            if (!s.isBlank()) {
                diffSummary = s;
            }
        }

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
            input.currentGoalDescription() == null ? "(none)" : input.currentGoalDescription(), input.currentGoalId() == null ? "(none)" : input.currentGoalId(),
            input.currentSubGoalDescription() == null ? "(none)" : input.currentSubGoalDescription(), input.currentSubGoalId() == null ? "(none)" : input.currentSubGoalId(),
            formatGoalList(input.remainingGoals()),
            formatGoalList(input.completedGoals()),
            projectSummary,
            diffSummary,
            formatLastRawOutput(input.lastEvolutionOutcome())
        ));

        if (result != null) {
            prompt.append("""
                --- MUTATION DIFF SIGNALS LAST EVALUATION ---
                Delta Score (Δ): %.4f (%.3f → %.3f)
                Fingerprint Repeated: %s

                """.formatted(
                result.delta(),
                result.previous(),
                result.current(),
                result.fingerprintRepeated() ? "true" : "false"
            ));
        }

        prompt.append(ProblemContextPromptBlocks.asPromptBlock(
                input.problemContext(),
                input.currentGoalId(),
                input.currentSubGoalId()
        ));
        prompt.append("\n");

        boolean repeated  = input.hasSignal(BasicSignals.REPETITION_DETECTED);

        if (!divergent && repeated) {
            prompt.append("""
        Repetition Detected:
        Your latest evolution response appears structurally identical to a previous cycle.

        Refer specifically to the diff summary and the last accepted variant shown above.
        Propose a modification that meaningfully changes:
        - method signatures,
        - class structure,
        - responsibilities,
        - or file composition.

        Do NOT regenerate the same code. Use the provided structural summary and diff
        to identify which parts have remained unchanged and target those areas.
        """);
        }

        if (divergent && !repeated) {
            prompt.append("""
        Divergence Mode Triggered:
        The system detected stagnation. You must alter the structure beyond refinements.

        Use the provided:
        - structural summary,
        - diff summary,
        - last raw output,
        - and subgoal context

        to propose a meaningfully different approach.
        This may involve reorganizing classes, extracting new components,
        or introducing alternate design patterns.

        Base your divergence on the actual patterns visible in the summaries above.
        """);
        }

        if (divergent && repeated) {
            prompt.append("""
        Escalated Divergence Required:
        Divergent evolution was requested but the output still repeated earlier patterns.

        Review the following context provided above:
        - Last raw evolution output
        - Structural summary
        - Diff summary
        - Completed and remaining subgoals

        Identify patterns that have not changed across the last cycles and
        propose a fundamentally different structural direction.

        You may:
        - replace entire class layouts,
        - introduce new abstractions,
        - split or merge files based on the existing ones,
        - or reorganize modules according to the summaries provided.

        Your output must differ significantly from BOTH the last raw output
        and the structural summary already shown.
        """);
        }

        prompt.append("""
            --- META REQUEST ---
            %s

            """.formatted(metaRequest(input)));

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

        boolean containsCodeBlock = FILE_CODE_BLOCK.matcher(content).find();
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

    private static String metaRequest(CycleContextData ctx) {
        if (ctx == null) return "(none)";
        DeliberationResult dr = ctx.deliberationResult();
        if (dr == null || dr.meta() == null) return "(none)";
        Object req = dr.meta().get("metaRequest");
        if (req == null) return "(none)";
        String s = req.toString().trim();
        return s.isBlank() ? "(none)" : s;
    }
}
