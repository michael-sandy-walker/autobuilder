package com.walpro.autobuilder.agent.planner.protocol;

import java.util.List;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.ContractRule;

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
            
            --- LAST SCORE INTERPRETATION ---
        	%s

            --- RECOMMENDATION ---
            %s

            --- RESPONSE FORMAT ---
            Respond with one or more of:
            - #SubGoalAppend followed by subgoal list
            - #SubGoalUpdate followed by subgoal list
            - #SubGoalRemove followed by goal list
            Optionally include: Message: <short summary>
            """.formatted(
                input.currentGoalDescription(),
                input.currentSubGoalDescription(),
                formatGoalInfoList(input.remainingGoals()),
                formatGoalInfoList(input.completedGoals()),
                input.evaluationFeedback() == null ? "(none)" : input.evaluationFeedback(),
                interpretationBlock,
                input.recommendation() == null ? "(none)" : input.recommendation()
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
}
