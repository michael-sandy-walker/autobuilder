package com.walpro.autobuilder.protocol.planner;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.core.ContractRule;
import com.walpro.autobuilder.protocol.core.ProblemContextPromptBlocks;
import com.walpro.autobuilder.protocol.meta.MetaIntention;

public class PlannerProtocolContract {

    public static final String VERSION = "1.0-planner";

    public static List<ContractRule> rules() {
        return List.of(
                new ContractRule("SUBGOAL_APPEND",
                        "To append subgoals, use #SubGoalAppend followed by a list of goals.",
                        "#SubGoalAppend\n- Improve logging\n- Refactor tests"
                ),
                new ContractRule("SUBGOAL_REMOVE",
                        "To remove existing subgoals, use #SubGoalRemove followed by the goal IDs (not descriptions) taken from the Remaining Goals list above.",
                        "#SubGoalRemove\n- goal-550e8400-e29b-41d4-a716-446655440000"
                ),
                new ContractRule("INCLUDE_ACTIONABLE_RESPONSE",
                        "Always include at least one actionable change (#SubGoalAppend, Update, or Remove).",
                        "#SubGoalAppend\n- Improve performance"
                ),
                new ContractRule("NO_EXTRA_COMMENTARY",
                        "Output ONLY the structured response (#SubGoalAppend / #SubGoalRemove, optionally followed by 'Message:'). Do not output any text before, after, or between these blocks — no explanations, no preamble, no markdown prose.",
                        "Message: Adjusted subgoals to better align with performance improvements."
                ),
                new ContractRule("NO_JSON",
                        "Do NOT output JSON. Use only the plain-text format defined in these rules (#SubGoalAppend, #SubGoalRemove, Message:).",
                        "#SubGoalAppend\n- Improve logging\nMessage: Added logging subgoal."
                ),
                new ContractRule(
                        "RESTRICT_TO_SOFTWARE_SCOPE",
                        "Only suggest subgoals related to software development (e.g., refactoring, testing, API design). Business tasks like 'conduct market research' or 'identify stakeholders' are not allowed.",
                        "#SubGoalAppend\n- Improve logging\n- Refactor controller"
                ),
                new ContractRule(
                        "NO_DUPLICATES",
                        "Do NOT re-emit goals that are semantically the same as existing remaining or completed goals.",
                        "Check phrasing and intent against the current goal lists."
                ),
                new ContractRule(
                        "PROBLEM_AWARENESS",
                        "If a PROBLEM CONTEXT block is present, you must consider it. If there are OPEN problems in scope, your subgoal changes must address them (directly or by adding diagnostic subgoals). Do not ignore problem keywords.",
                        """
                        #SubGoalAppend
                        - Investigate and fix keywords: STAGNATION, path:src/main/java/com/example/demo/HelloController.java
                        - Add a targeted test reproducing the failing behavior referenced by the problem context
                        Message: Added subgoals to address open in-scope problems before further feature work.
                        """
                ),
                new ContractRule(
                        "BLOCKERS_FIRST",
                        "If BlockingInScope > 0, prioritize blocker-resolution subgoals first. Do not propose unrelated improvements while blockers are open.",
                        """
                        #SubGoalRemove
                        - goal-550e8400-e29b-41d4-a716-446655440000
                        #SubGoalAppend
                        - Fix blocker keywords: NO_MEANINGFUL_CHANGE, path:src/main/java/com/example/demo/HelloController.java
                        - Add regression test to ensure the fix remains stable
                        Message: Reordered subgoals to resolve blockers first.
                        """
                ),
                new ContractRule(
                        "KEYWORDS_TO_SUBGOALS",
                        "Translate problem keywords into concrete, software-scoped subgoals. Use file paths from keywords (path:...) when present to make subgoals specific and actionable.",
                        """
                        #SubGoalAppend
                        - Refactor src/main/java/com/example/demo/HelloController.java to remove duplication flagged by keywords: REDUNDANT_OUTPUT
                        - Add unit tests for the refactored behavior
                        Message: Converted problem keywords into file-targeted subgoals.
                        """
                ),
                new ContractRule(
                        "NO_REPEAT_KNOWN_FAILURES",
                        "If the problem keywords indicate repetition or stagnation (e.g., REPEAT, REPETITION_DETECTED, STAGNATION, NO_MEANINGFUL_CHANGE), do not propose semantically identical subgoals again. Propose a different diagnostic or structural angle.",
                        """
                        #SubGoalAppend
                        - Compare last accepted vs current diff for the files referenced by keywords and identify missing structural changes
                        - Introduce a new abstraction or test harness to force a meaningful change
                        Message: Avoided repeating prior subgoals; added a different diagnostic approach due to repetition/stagnation.
                        """
                ),
                new ContractRule(
                        "NO_REPEAT_ABANDONED",
                        "Do NOT re-propose abandoned goals verbatim. If an Abandoned Goals block is present, every goal listed there was already attempted and given up. If a similar approach is still warranted, reframe it with a concrete structural difference (different file, different technique, or narrower scope).",
                        "#SubGoalAppend\n- Refactor HelloController endpoint routing using a dedicated RequestMapping strategy (prior attempt used inline mapping — try a separate config class instead)"
                ),
                new ContractRule(
                        "GOAL_DERIVED_SUBGOALS",
                        "Every proposed subgoal must be directly traceable to a specific requirement stated in the root goal. " +
                        "Do not add generic boilerplate subgoals (e.g., 'Return Hello World', 'Add logging', 'Set up health check') " +
                        "unless the root goal explicitly requires them. " +
                        "If the root goal specifies an API contract (endpoints, HTTP codes, request/response format, validation rules), " +
                        "subgoals must implement those specifics — not intermediate scaffolding that the goal does not mention.",
                        """
                        Root Goal: REST API with POST /tasks, GET /tasks, PATCH /tasks/{id}/complete.
                        WRONG:
                        #SubGoalAppend
                        - Set up a controller returning "Hello World" at /
                        - Add a health check endpoint
                        OK:
                        #SubGoalAppend
                        - Implement POST /tasks with title validation (max 100 chars), returning HTTP 201 with task body or HTTP 400 with {\"message\":\"...\"} on invalid input
                        - Implement GET /tasks returning in-memory task list
                        - Implement PATCH /tasks/{id}/complete returning HTTP 404 for unknown id
                        """
                ),
                new ContractRule(
                        "ACCEPTANCE_CRITERIA",
                        "Every subgoal line in #SubGoalAppend MUST be followed immediately by a 'Criteria:' sub-block. " +
                        "List 1-4 short 'done when' statements prefixed with '*'. " +
                        "Each criterion must be specific and mechanically checkable (e.g., 'POST /tasks returns HTTP 201 with task body'). " +
                        "If no observable output criterion applies (e.g., the subgoal is purely structural), write exactly: " +
                        "'* (subgoal is structural — no observable output criterion)'.",
                        """
                        #SubGoalAppend
                        - Implement POST /tasks with title validation
                        Criteria:
                        * Returns HTTP 201 with task body on success
                        * Returns HTTP 400 with {"message":"..."} body when title is missing or > 100 chars
                        * Title field is stored and returned in response
                        - Refactor controller to use a dedicated RequestMapping config class
                        Criteria:
                        * (subgoal is structural — no observable output criterion)
                        - Implement GET /tasks
                        Criteria:
                        * GET /tasks returns HTTP 200 with a JSON array of tasks
                        * Response includes all previously created tasks
                        """
                ),
                new ContractRule(
                        "PRUNE_BEFORE_GROW",
                        "When the remaining goal count is large relative to completed goals, review the backlog before adding more. Specifically look for: (1) remaining goals with overlapping or near-identical intent, (2) goals that require code execution, running tests, or observing program output — this system cannot execute code, (3) goals superseded by a more recent or specific sibling. Remove goals matching these criteria, then add new ones if still needed.",
                        "#SubGoalRemove\n- goal-550e8400-e29b-41d4-a716-446655440000\n- goal-6ba7b810-9dad-11d1-80b4-00c04fd430c8\nMessage: Removed two near-duplicate analysis goals before adding a more targeted one.\n#SubGoalAppend\n- Refactor HelloController to return structured JSON response"
                )
        );
    }

    public static String asPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("PLANNER PROTOCOL CONTRACT v").append(VERSION).append("\n\n");
        for (ContractRule rule : rules()) {
            sb.append("RULE: ").append(rule.id()).append("\n")
                    .append("Description: ").append(rule.description()).append("\n")
                    .append("Example: ").append(rule.example()).append("\n\n");
        }
        return sb.toString();
    }

    public static String getSystemPrompt() {
        return "You are a goal planning agent.\n\n" + asPromptBlock();
    }

    public static String getUserPrompt(CycleContextData input) {
        StringBuilder sb = new StringBuilder();

        InterpretationResult interp = input.evaluationInterpretation();

        String interpretationBlock = interp == null
            ? "(no interpretation available)" : """
                   Current Score: %.3f
                   Last Score: %.3f
                   Delta: %.4f
                   Goal progress: %s
                """.formatted(interp.current(), interp.previous(), interp.delta(), interp.goalProgress());

        String skippedBlock = (input.skippedGoals() == null || input.skippedGoals().isEmpty())
                ? null
                : "The following proposals were rejected last cycle as too similar to existing goals.\n"
                  + "Do NOT re-propose them verbatim or with only cosmetic rewording:\n"
                  + input.skippedGoals().stream().map(g -> "- " + g).collect(Collectors.joining("\n"));

        String problemBlock = ProblemContextPromptBlocks.asPromptBlock(
                input.problemContext(),
                input.currentGoalId(),
                input.currentSubGoalId()
        );

        String subgoalStatusBlock = currentSubGoalStatusBlock(input);
        String projectStructBlock = projectStructureBlock(input);

        int remainingCount = input.remainingGoals() == null ? 0 : input.remainingGoals().size();
        int completedCount = input.completedGoals() == null ? 0 : input.completedGoals().size();
        sb.append("""
            --- GOALS ---
            Root Goal: %s
            Current SubGoal: %s

            Remaining Goals (%d) — indented entries are active subgoals of the item above them; do NOT remove a parent while it still has active subgoals:
            %s

            Completed Goals (%d):
            %s

            Abandoned Goals (given up — do not re-propose verbatim):
            %s
            %s
            %s
            --- EVALUATION FEEDBACK ---
            %s
            \s
            --- LAST SCORE INTERPRETATION ---
            %s

            --- RECOMMENDATION ---
            %s

            %s

            --- INTERPRETATION CONTEXT (previous cycle) ---
            %s

            --- META INTENTION ---
            %s
            \s
            %s
            --- RESPONSE FORMAT ---
            Respond with one or more of:
            - #SubGoalAppend followed by subgoal list (to add new subgoals)
            - #SubGoalRemove followed by goal IDs (from the Remaining Goals list above) (to remove existing subgoals)
            To replace the subgoal list: use #SubGoalRemove to remove unwanted goals, then #SubGoalAppend to add new ones.
            Optionally include: Message: <short summary>
            Output ONLY the structured blocks above. Do not add any text before, after, or between them.
           \s""".formatted(
                input.currentGoalDescription() == null ? "(none)" : input.currentGoalDescription(),
                input.currentSubGoalDescription() == null ? "(none)" : input.currentSubGoalDescription(),
                remainingCount,
                formatGoalInfoList(input.remainingGoals()),
                completedCount,
                formatGoalInfoList(input.completedGoals()),
                formatDescriptionList(input.abandonedGoals()),
                skippedBlock == null ? "" : "\n--- REJECTED PROPOSALS (too similar — do not re-propose) ---\n" + skippedBlock + "\n",
                subgoalStatusBlock == null ? "" : subgoalStatusBlock + "\n",
                input.evaluationFeedback() == null ? "(none)" : input.evaluationFeedback(),
                interpretationBlock,
                input.recommendation() == null ? "(none)" : input.recommendation(),
                problemBlock,
                interpretationContext(input),
                metaIntention(input),
                projectStructBlock == null ? "" : projectStructBlock + "\n"
        ));
        if (input.currentSubGoalDescription() == null) {
            sb.append("""
                \n\nNOTE:
                There is currently no active subgoal.
                If the root goal is fulfilled, respond with:
                Message: Goal complete.

                Otherwise, use #SubGoalAppend to inject additional subgoals.
                """);
        }
        
//        String lineage = input.currentSubGoalId(); // fallback if no explicit lineage
//        String strategyHint = StrategyHintGenerator.generateHint(lineage, input.depthOrZero());
//        sb.append("\n--- BRANCHING STRATEGY ---\n");
//        sb.append("Suggested Drift: ").append(strategyHint).append("\n");

        return sb.toString();
    }

    private static String currentSubGoalStatusBlock(CycleContextData ctx) {
        if (ctx.currentSubGoalDescription() == null) return null;

        StringBuilder sb = new StringBuilder("--- CURRENT SUBGOAL STATUS ---\n");

        int stagnating = ctx.cumulativeStagnatingCycles();
        sb.append("Stagnating cycles: ").append(stagnating).append("\n");

        if (ctx.lastScore() != null) {
            double delta = ctx.score() - ctx.lastScore();
            sb.append("Score: %.3f → %.3f (Δ %+.4f)\n".formatted(ctx.lastScore(), ctx.score(), delta));
        } else if (ctx.score() > 0) {
            sb.append("Score: %.3f\n".formatted(ctx.score()));
        }

        List<String> criteria = ctx.currentSubGoalCriteria();
        List<String> assessments = ctx.criteriaAssessments();
        if (criteria != null && !criteria.isEmpty()) {
            sb.append("Acceptance criteria:\n");
            for (int i = 0; i < criteria.size(); i++) {
                String mark = findAssessmentMark(assessments, i + 1);
                sb.append("  [").append(i + 1).append("] ").append(mark)
                  .append(" ").append(criteria.get(i)).append("\n");
            }
        }

        List<String> warnings = ctx.persistentHeuristicWarnings();
        if (warnings != null && !warnings.isEmpty()) {
            sb.append("Persistent heuristic warnings:\n");
            for (String w : warnings) {
                sb.append("  \u26a0 ").append(w).append("\n");
            }
        }

        // T2-BG: show the decompose coaching note on formal stagnation (>=3 cycles) OR when
        // REPLAN_REQUESTED is strong (>=0.50), which means the subgoal is stuck regardless of
        // how many formal STAGNATING cycles have been recorded. In REPLAN mode (pre-T1-BD),
        // evolution was blocked so STAGNATING results were impossible and the counter never
        // reached 3; with REPLAN_REQUESTED as an additional trigger the coaching fires sooner.
        boolean replanRequested = ctx.hasStrongSignal(BasicSignals.REPLAN_REQUESTED, 0.50);
        if (stagnating >= 3 || replanRequested) {
            String trigger = stagnating >= 3
                    ? stagnating + " stagnating cycles"
                    : "REPLAN_REQUESTED signal (subgoal is stuck)";
            sb.append("\n\u26a0 This subgoal is stuck (").append(trigger).append(").")
              .append(" Consider decomposing: use `#SubGoalRemove <id>` to remove it, then")
              .append(" `#SubGoalAppend` with narrower, file-level replacements.")
              .append(" Removal is processed before injection — the backlog will be empty in time for the new subgoals.\n");
        }

        return sb.toString().stripTrailing();
    }

    private static String findAssessmentMark(List<String> assessments, int index) {
        if (assessments == null || assessments.isEmpty()) return "?";
        String prefix = "[" + index + "]";
        for (String a : assessments) {
            if (a.startsWith(prefix)) {
                if (a.contains("\u2713")) return "\u2713";
                if (a.contains("\u2717")) return "\u2717";
            }
        }
        return "?";
    }

    private static String projectStructureBlock(CycleContextData ctx) {
        String summary = ctx.projectSummary();
        if (summary == null || summary.isBlank()) return null;

        String[] lines = summary.split("\n", -1);
        String content;
        if (lines.length <= 30) {
            content = summary;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                sb.append(lines[i]).append("\n");
            }
            sb.append("... (truncated)");
            content = sb.toString();
        }
        return "--- PROJECT STRUCTURE ---\n" + content;
    }

    private static String formatGoalInfoList(List<GoalEntity> goalEntities) {
        if (goalEntities == null || goalEntities.isEmpty()) return "(none)";

        Set<String> ids = goalEntities.stream().map(GoalEntity::getId).collect(Collectors.toSet());

        Map<String, List<GoalEntity>> childrenByParent = goalEntities.stream()
                .filter(g -> g.getParentId() != null && ids.contains(g.getParentId()))
                .collect(Collectors.groupingBy(GoalEntity::getParentId));

        List<GoalEntity> roots = goalEntities.stream()
                .filter(g -> g.getParentId() == null || !ids.contains(g.getParentId()))
                .toList();

        StringBuilder sb = new StringBuilder();
        for (GoalEntity root : roots) {
            appendGoalTree(sb, root, childrenByParent, 0);
        }
        return sb.toString().stripTrailing();
    }

    private static void appendGoalTree(StringBuilder sb, GoalEntity goal,
            Map<String, List<GoalEntity>> childrenByParent, int depth) {
        List<GoalEntity> children = childrenByParent.getOrDefault(goal.getId(), List.of());
        sb.append("    ".repeat(depth))
          .append("- ").append(goal.getId()).append(": ").append(goal.getDescription());
        int merges = goal.getMergeCount();
        if (merges > 0) {
            sb.append(" [").append(merges).append(merges == 1 ? " merge" : " merges").append("]");
        }
        if (!children.isEmpty()) {
            sb.append(" [has ").append(children.size()).append(" active subgoal")
              .append(children.size() == 1 ? "" : "s").append(" — remove children before removing this]");
        }
        sb.append("\n");
        for (GoalEntity child : children) {
            appendGoalTree(sb, child, childrenByParent, depth + 1);
        }
    }

    private static String formatDescriptionList(List<GoalEntity> goalEntities) {
        if (goalEntities == null || goalEntities.isEmpty()) return "(none)";
        return String.join("\n", goalEntities.stream()
                .map(g -> "- " + g.getDescription())
                .toList());
    }

    public static boolean isValidResponse(String content) {
        return content != null && (
                content.contains("#SubGoalUpdate") ||
                        content.contains("#SubGoalAppend") ||
                        content.contains("#SubGoalRemove")
        );
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
}
