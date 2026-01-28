package com.walpro.autobuilder.protocol.planner;

import java.util.List;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.core.ContractRule;
import com.walpro.autobuilder.protocol.core.ProblemContextPromptBlocks;

public class PlannerProtocolContract {

    public static final String VERSION = "1.0-planner";

    public static List<ContractRule> rules() {
        return List.of(
                new ContractRule("SUBGOAL_APPEND",
                        "To append subgoals, use #SubGoalAppend followed by a list of goals.",
                        "#SubGoalAppend\n- Improve logging\n- Refactor tests"
                ),
                new ContractRule("SUBGOAL_UPDATE",
                        "To replace the full list of subgoals, use #SubGoalUpdate followed by a list.",
                        "#SubGoalUpdate\n- Migrate to Jakarta\n- Add Swagger"
                ),
                new ContractRule("SUBGOAL_REMOVE",
                        "To remove existing subgoals, use #SubGoalRemove followed by a list.",
                        "#SubGoalRemove\n- Obsolete goal"
                ),
                new ContractRule("INCLUDE_ACTIONABLE_RESPONSE",
                        "Always include at least one actionable change (#SubGoalAppend, Update, or Remove).",
                        "#SubGoalAppend\n- Improve performance"
                ),
                new ContractRule("NO_EXTRA_COMMENTARY",
                        "Do not explain the goal changes outside the format. You may use 'Message:' as the final line.",
                        "Message: Adjusted subgoals to better align with performance improvements."
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
                        #SubGoalUpdate
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

        String problemBlock = ProblemContextPromptBlocks.asPromptBlock(
                input.problemContext(),
                input.currentGoalId(),
                input.currentSubGoalId()
        );
        
        sb.append("""
            --- GOALS ---
            Root Goal: %s
            Current SubGoal: %s

            Remaining Goals:
            %s

            Completed Goals:
            %s

            --- EVALUATION FEEDBACK ---
            %s
            \s
            --- LAST SCORE INTERPRETATION ---
            %s

            --- RECOMMENDATION ---
            %s

            %s

            --- META REQUEST ---
            %s
            \s

            --- RESPONSE FORMAT ---
            Respond with one or more of:
            - #SubGoalAppend followed by subgoal list
            - #SubGoalUpdate followed by subgoal list
            - #SubGoalRemove followed by goal list
            Optionally include: Message: <short summary>
           \s""".formatted(
                input.currentGoalDescription() == null ? "(none)" : input.currentGoalDescription(),
                input.currentSubGoalDescription() == null ? "(none)" : input.currentSubGoalDescription(),
                formatGoalInfoList(input.remainingGoals()),
                formatGoalInfoList(input.completedGoals()),
                input.evaluationFeedback() == null ? "(none)" : input.evaluationFeedback(),
                interpretationBlock,
                input.recommendation() == null ? "(none)" : input.recommendation(),
                problemBlock,
                metaRequest(input)
        ));
        if (input.currentSubGoalDescription() == null) {
            sb.append("""
                \n\nNOTE:
                There is currently no active subgoal.
                If the root goal is fulfilled, you may respond with:
                - #SubGoalUpdate: (empty)
                - Message: "Goal complete."
                
                Otherwise, inject additional subgoals if needed.
                """);
        }
        
//        String lineage = input.currentSubGoalId(); // fallback if no explicit lineage
//        String strategyHint = StrategyHintGenerator.generateHint(lineage, input.depthOrZero());
//        sb.append("\n--- BRANCHING STRATEGY ---\n");
//        sb.append("Suggested Drift: ").append(strategyHint).append("\n");

        return sb.toString();
    }

    private static String formatGoalInfoList(List<GoalEntity> goalEntities) {
        if (goalEntities == null || goalEntities.isEmpty()) return "(none)";
        return String.join("\n", goalEntities.stream().map(goalEntity -> "- %s: %s".formatted(goalEntity.getId(), goalEntity.getDescription())
        ).toList());
    }

    public static boolean isValidResponse(String content) {
        return content != null && (
                content.contains("#SubGoalUpdate") ||
                        content.contains("#SubGoalAppend") ||
                        content.contains("#SubGoalRemove")
        );
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
