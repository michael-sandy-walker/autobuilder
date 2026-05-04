package com.walpro.autobuilder.protocol.evolution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.walpro.autobuilder.analysis.structure.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.build.BuildResultPromptBlock;
import com.walpro.autobuilder.protocol.core.ContractRule;
import com.walpro.autobuilder.protocol.core.ProblemContextPromptBlocks;
import com.walpro.autobuilder.protocol.core.ProtocolUtils;
import com.walpro.autobuilder.protocol.meta.MetaIntention;

public class EvolutionProtocolContract {

    public static final String VERSION = "1.1-evolution";

    private static final List<ContractRule> STATIC_RULES = loadRules();

    /** T2-CC / T1-DO: HTTP paths, camelCase, and PascalCase identifiers extracted for surface-scan. */
    private static final Pattern HTTP_PATH_TERM = Pattern.compile("/[a-zA-Z][a-zA-Z0-9/]+");
    private static final Pattern CAMEL_IDENT_TERM = Pattern.compile("\\b[a-z][a-zA-Z]{4,}\\b");
    private static final Pattern PASCAL_IDENT_TERM = Pattern.compile("\\b[A-Z][a-zA-Z]{2,}\\b");

    /** T2-CC: common English words to exclude from the surface-scan key term set. */
    private static final Set<String> SURFACE_SCAN_STOP_WORDS = Set.of(
            "return", "returns", "response", "request", "method", "class", "field", "value",
            "object", "string", "when", "should", "given", "after", "before", "there",
            "where", "which", "their", "about", "these", "those", "other", "exist",
            "exists", "handle", "handles", "called", "contains", "mapped", "match",
            "body", "valid", "using", "store", "stores", "saved", "create", "creates",
            "result", "status", "types", "found", "items", "array", "count", "error"
    );

    /** PascalCase tokens that appear in criteria text but are NOT class names. */
    private static final Set<String> COMMON_CRITERIA_TOKENS = Set.of(
            "HTTP", "GET", "POST", "PUT", "DELETE", "PATCH", "JSON", "API",
            "Returns", "Response", "Request", "Status", "Code", "Body", "Field",
            "String", "List", "Map", "Optional", "Long", "Integer", "Boolean",
            "True", "False", "The", "This", "When", "With", "For", "From",
            "All", "Any", "Each", "No", "New", "Old", "Has", "Can", "Is"
    );

    private static final Pattern CRITERIA_CLASS_NAME = Pattern.compile("\\b([A-Z][a-zA-Z0-9]{2,})\\b");

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

        // T4-AN-3: surface ground-truth build state to the evolver so it cannot hallucinate
        // that a missing build artifact (e.g. pom.xml) is present. Same formatter the evaluator
        // uses, with an evolver-appropriate closing directive that forbids #NoDiff while broken.
        String buildBlock = BuildResultPromptBlock.render(input, BuildResultPromptBlock.Audience.EVOLUTION);
        if (buildBlock != null) {
            prompt.append(buildBlock).append("\n\n");
        }

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

        if (input.hasSignal(BasicSignals.SCORE_PLATEAU)) {
            int plateauN = input.plateauCycles();
            prompt.append("""
        Score Plateau Detected:
        The evaluation score has been flat (within \u00b10.03) for %d consecutive accepted mutations.
        The current approach is not producing measurable improvement.

        Attempt a structurally different implementation: change method names, class structure,
        file composition, or design patterns rather than refining the same code.
        """.formatted(plateauN));
        }

        List<String> heuristicWarnings = input.persistentHeuristicWarnings();
        if (heuristicWarnings != null && !heuristicWarnings.isEmpty()) {
            prompt.append("--- PERSISTENT HEURISTIC WARNINGS ---\n");
            prompt.append("The following structural issues have been flagged at critical confidence ");
            prompt.append("for multiple consecutive cycles. Your mutation MUST address them:\n\n");
            for (String warning : heuristicWarnings) {
                prompt.append("• ").append(warning).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("""
            --- INTERPRETATION CONTEXT (previous cycle) ---
            %s

            --- META INTENTION ---
            %s

            """.formatted(interpretationContext(input), metaIntention(input)));

        String basePackage = input.establishedBasePackage();
        if (basePackage != null && !basePackage.isBlank()) {
            prompt.append("""
                --- PACKAGE CONSTRAINT ---
                This project already has Java files under the package `%s`.
                All generated Java files MUST use `%s` or a sub-package of it (e.g. `%s.service`, `%s.model`).
                Do NOT introduce a different top-level package.

                """.formatted(basePackage, basePackage, basePackage, basePackage));
        }

        String criteriaProgress = criteriaProgressBlock(input);
        if (criteriaProgress != null) {
            prompt.append(criteriaProgress);
            prompt.append("\n");
        }

        String requiredFiles = requiredFilesBlock(input);
        if (requiredFiles != null) {
            prompt.append(requiredFiles);
            prompt.append("\n");
        }

        String implStatus = currentImplementationStatusBlock(input);
        if (implStatus != null) {
            prompt.append(implStatus);
            prompt.append("\n\n");
        }

        String planContext = planContextBlock(input);
        if (planContext != null) {
            prompt.append(planContext);
            prompt.append("\n\n");
        }

        prompt.append("""
            Respond with:
            - One or more code blocks for changed files
            - Or one or more #Delete directives
            - Or `#NoDiff` on its own line (followed by a one-sentence explanation) if the current output already satisfies all criteria

            Do not return unchanged files.
            Do not include commentary outside code blocks (except when responding with #NoDiff).
            """);

        return prompt.toString();
    }

    public static boolean isValidResponse(String content) {
        if (content == null || content.isBlank()) return false;

        boolean containsCodeBlock = FILE_CODE_BLOCK.matcher(content).find();
        boolean containsDeletion = content.contains("#Delete [");
        // T2-CC: #NoDiff on its own line is a valid no-op response
        boolean containsNoDiff = content.lines().anyMatch(line -> line.trim().equals("#NoDiff"));

        return containsCodeBlock || containsDeletion || containsNoDiff;
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

    private static String interpretationContext(CycleContextData ctx) {
        if (ctx == null) return "(none)";
        String summary = ctx.interpretationSummary();
        String directives = ctx.interpretationDirectives();
        if ((summary == null || summary.isBlank()) && (directives == null || directives.isBlank())) return "(none)";
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) sb.append("Summary: ").append(summary.trim());
        if (directives != null && !directives.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("Directives: ").append(directives.trim());
        }
        return sb.toString();
    }

    private static String metaIntention(CycleContextData ctx) {
        if (ctx == null) return "(none)";
        MetaIntention mi = ctx.metaIntention();
        if (mi == null) return "(none)";
        String result = "";
        if (mi.role() != null) result += "Role: " + mi.role() + "\n";
        if (mi.request() != null) result += "Request: " + mi.request() + "\n";
        return result.isBlank() ? "(none)" : result.trim();
    }

    /**
     * T2-BN: builds a CRITERIA PROGRESS block showing which acceptance criteria are already
     * banked (✓) and which are still unmet (✗), so the evolution agent focuses its mutation
     * on the remaining gap rather than re-implementing already-satisfied criteria.
     * Returns null when no cumulative status is available yet.
     */
    static String criteriaProgressBlock(CycleContextData input) {
        Map<Integer, Boolean> status = input.cumulativeCriteriaStatus();
        if (status == null || status.isEmpty()) return null;

        List<String> criteria = input.currentSubGoalCriteria();

        StringBuilder sb = new StringBuilder("--- CRITERIA PROGRESS ---\n");
        int count = criteria != null ? criteria.size() : status.size();
        for (int i = 0; i < count; i++) {
            Boolean passed = status.get(i);
            String text = (criteria != null && i < criteria.size()) ? criteria.get(i) : "(unknown criterion)";
            if (Boolean.TRUE.equals(passed)) {
                sb.append("[").append(i + 1).append("] \u2713 ").append(text).append("  \u2190 already satisfied\n");
            } else {
                sb.append("[").append(i + 1).append("] \u2717 ").append(text).append("  \u2190 still unmet; focus your mutation here\n");
            }
        }
        return sb.toString();
    }

    /**
     * T2-BS: builds a REQUIRED FILES block when the current subgoal's acceptance criteria
     * reference class names that map to ≥2 distinct files already in the project.
     * Tells the LLM which files it must emit code blocks for in this cycle.
     * T2-BX: extends each existing file entry with a structural summary of its public
     * declarations so the evolution agent does not clobber them.
     */
    static String requiredFilesBlock(CycleContextData input) {
        List<String> criteria = input.currentSubGoalCriteria();
        if (criteria == null || criteria.size() < 2) return null;

        Map<String, String> prev = input.previousOutput();
        if (prev == null || prev.isEmpty()) return null;

        Set<String> filePaths = new LinkedHashSet<>();
        for (String criterion : criteria) {
            Matcher m = CRITERIA_CLASS_NAME.matcher(criterion);
            while (m.find()) {
                String className = m.group(1);
                if (COMMON_CRITERIA_TOKENS.contains(className)) continue;
                String suffix = "/" + className + ".java";
                prev.keySet().stream()
                        .filter(p -> p.endsWith(suffix))
                        .findFirst()
                        .ifPresent(filePaths::add);
            }
        }

        if (filePaths.size() < 2) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("--- REQUIRED FILES ---\n");
        sb.append("This subgoal requires changes to the following files. ");
        sb.append("Your response MUST include a code block for each:\n\n");

        boolean anyExisting = false;
        for (String path : filePaths) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            String content = prev.get(path);
            if (content != null && !content.isBlank()) {
                anyExisting = true;
                sb.append(fileName).append("  [EXISTS]\n");
                List<String> decls = DefaultProjectStructureSummarizer.summarizeContent(content);
                for (String decl : decls) {
                    sb.append(decl).append("\n");
                }
                sb.append("\n");
            } else {
                sb.append("- ").append(path).append("\n");
            }
        }

        if (anyExisting) {
            sb.append("For files marked [EXISTS], your code block MUST include all existing declarations ");
            sb.append("shown above plus your new additions. Do NOT omit existing methods or handlers.\n");
        }

        return sb.toString().stripTrailing();
    }

    /**
     * T2-CC: surface-scans the current output files against acceptance criteria key terms.
     * Returns a CURRENT IMPLEMENTATION STATUS block only when all criteria have at least one
     * key term found in the output, together with a #NoDiff permission directive.
     * Returns null when criteria are absent, output is empty, or not all criteria are matched.
     */
    static String currentImplementationStatusBlock(CycleContextData input) {
        List<String> criteria = input.currentSubGoalCriteria();
        if (criteria == null || criteria.isEmpty()) return null;

        Map<String, String> prevOutput = input.previousOutput();
        if (prevOutput == null || prevOutput.isEmpty()) return null;

        String allOutput = String.join("\n", prevOutput.values());

        List<String> statusLines = new ArrayList<>();
        boolean allApparentlySatisfied = true;

        for (String criterion : criteria) {
            List<String> keyTerms = extractSurfaceScanTerms(criterion);
            if (keyTerms.isEmpty()) {
                statusLines.add("• " + criterion + "  → (unable to surface-check)");
                allApparentlySatisfied = false;
                continue;
            }
            boolean found = keyTerms.stream().anyMatch(allOutput::contains);
            if (found) {
                statusLines.add("• " + criterion + "  → appears present in output");
            } else {
                statusLines.add("• " + criterion + "  → not detected in current output");
                allApparentlySatisfied = false;
            }
        }

        if (!allApparentlySatisfied) return null;

        StringBuilder sb = new StringBuilder("--- CURRENT IMPLEMENTATION STATUS ---\n");
        sb.append("Surface scan of current output files against acceptance criteria:\n");
        for (String line : statusLines) {
            sb.append(line).append("\n");
        }
        sb.append("\nAll criteria appear to be present in the current output. ");
        sb.append("Verify the implementation satisfies each criterion in full. ");
        sb.append("If confirmed, respond with `#NoDiff` on its own line followed by a one-sentence explanation. ");
        sb.append("Only use `#NoDiff` if you are certain the current code is complete; otherwise provide your code changes as normal.");
        return sb.toString();
    }

    /**
     * T3-BH: builds a PLAN CONTEXT block showing the plan rationale and sibling subgoals,
     * so the evolution agent focuses only on the current subgoal and does not re-implement
     * functionality that belongs to completed or upcoming siblings.
     * Returns null when no plan context is available.
     */
    static String planContextBlock(CycleContextData input) {
        String rationale = input.planRationale();
        String parentId = input.currentSubGoalParentId();
        String currentId = input.currentSubGoalId();

        if (rationale == null && parentId == null) return null;

        List<GoalEntity> completedSiblings = input.completedGoals() == null ? List.of()
                : input.completedGoals().stream()
                        .filter(g -> parentId != null && parentId.equals(g.getParentId()))
                        .toList();
        List<GoalEntity> upcomingSiblings = input.remainingGoals() == null ? List.of()
                : input.remainingGoals().stream()
                        .filter(g -> parentId != null && parentId.equals(g.getParentId())
                                && !g.getId().equals(currentId))
                        .toList();

        if (rationale == null && completedSiblings.isEmpty() && upcomingSiblings.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("--- PLAN CONTEXT ---\n");
        if (rationale != null) {
            sb.append("Plan rationale: ").append(rationale).append("\n");
        }
        if (!completedSiblings.isEmpty()) {
            sb.append("Completed sibling subgoals (already done — do not re-implement their functionality):\n");
            for (GoalEntity g : completedSiblings) {
                sb.append("  - ").append(g.getDescription()).append("\n");
            }
        }
        if (!upcomingSiblings.isEmpty()) {
            sb.append("Upcoming sibling subgoals (not yet started — do not implement their functionality now):\n");
            for (GoalEntity g : upcomingSiblings) {
                sb.append("  - ").append(g.getDescription()).append("\n");
            }
        }
        sb.append("Focus ONLY on what this subgoal requires. Do not implement functionality covered by upcoming siblings.");
        return sb.toString();
    }

    /** T1-DO: promoted to public so EvolutionAgent can use it for hasOutputEvidenceForSubgoal. */
    public static List<String> extractSurfaceScanTerms(String criterion) {
        List<String> terms = new ArrayList<>();
        Matcher m1 = HTTP_PATH_TERM.matcher(criterion);
        while (m1.find()) terms.add(m1.group());
        Matcher m2 = CAMEL_IDENT_TERM.matcher(criterion);
        while (m2.find()) {
            String t = m2.group();
            if (!SURFACE_SCAN_STOP_WORDS.contains(t)) terms.add(t);
        }
        Matcher m3 = PASCAL_IDENT_TERM.matcher(criterion);
        while (m3.find()) {
            String t = m3.group();
            if (!COMMON_CRITERIA_TOKENS.contains(t)) terms.add(t);
        }
        return terms;
    }
}
