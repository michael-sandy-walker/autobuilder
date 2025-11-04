package com.walpro.autobuilder.protocol.bootstrap;

import com.walpro.autobuilder.analysis.diff.DiffPromptFormatter;
import com.walpro.autobuilder.analysis.diff.DiffReport;
import com.walpro.autobuilder.model.ProjectInput;

import java.util.Collections;
import java.util.List;

public class ProtocolContract {

	public static final String CURRENT_VERSION = "1.0.0";

	public record ContractRule(String id, String description, String example) {}

	public static List<ContractRule> rules() {
		return List.of(
				new ContractRule(
						"FORMAT_CODE_BLOCK",
						"All responses must consist of well-formed code blocks with filenames inline.",
						"```java [src/main/java/com/example/MyClass.java]\n// code here\n```"
				),
				new ContractRule(
						"MARK_DELETIONS",
						"Files marked for deletion must use the #Delete [path] marker outside any code block.",
						"#Delete [src/main/java/com/example/Obsolete.java]"
				),
				/*new ContractRule(
						"SUBGOALS_UPDATE",
						"To replace the entire sub goals, use #SubGoalUpdate followed by a list of subgoals. You must also include at least one valid code block or deletion marker.",
						"#SubGoalUpdate\n- Refactor outdated classes\n```java [src/main/java/com/example/Refactored.java]\n// code\n```"
				),
				new ContractRule(
						"SUBGOALS_APPEND",
						"To append new subgoals, use #SubGoalAppend followed by a list of new subgoals. You must also include at least one valid code block or deletion marker.",
						"#SubGoalAppend\n- Add Swagger UI\n```java [src/main/java/com/example/SwaggerConfig.java]\n// code\\n```"
				),
				new ContractRule(
						"SUBGOALS_REMOVE",
						"To remove specific subgoals, use #SubGoalRemove followed by a list of existing subgoals. You must also include at least one valid code block or deletion marker.",
						"#SubGoalRemove\n- Obsolete goal\n#Delete [src/main/java/com/example/Obsolete.java]"
				),*/
				new ContractRule(
						"AVOID_EXPLANATION",
						"Do not include any additional explanation or narrative outside of code blocks or deletion markers.",
						"WRONG: 'Here is the file you requested' — this is not allowed."
				),
				new ContractRule(
						"STICK_TO_CHANGED_FILES",
						"Only return new or modified files. Unchanged files must be omitted.",
						"If 2 files were changed, return exactly 2 code blocks."
				),
				new ContractRule(
						"USE_STRICT_FILENAME_PARSING",
						"File references must match the relative project path using forward slashes.",
						"Example: src/main/java/com/example/App.java"
				)
		);
	}

	public static String asPromptBlock() {
		StringBuilder builder = new StringBuilder();
		builder.append("AUTOBUILDER-LANGUAGE CONTRACT v").append(CURRENT_VERSION).append(":\n\n");
		for (ContractRule rule : rules()) {
			builder.append("RULE: ").append(rule.id()).append("\n")
					.append("Description: ").append(rule.description()).append("\n")
					.append("Example: ").append(rule.example()).append("\n\n");
		}
//		builder.append("NOTE: Goal stack instructions must be formatted using the defined tags, one goal per line.\n\n");
//		builder.append("You may additionally provide #SubGoal sections if appropriate.\n\n");
//		builder.append("NOTE: Use #GoalStackAppend, #GoalStackUpdate, or #GoalStackRemove to modify the goal stack.\n\n");
//		builder.append("NOTE: File paths must reflect your intended package or resource location within the project. Avoid absolute paths or parent-relative paths.\n\n");
		return builder.toString();
	}

	public static String getUserPrompt(String cycleName, ProjectInput input) {
		String diffSummary = input.diff().entries().isEmpty()
				? "No structural changes detected."
				: DiffPromptFormatter.format(input.diff());

		String structure = input.structureSummary();

		String rootGoal = input.currentGoalDescription() != null ? "- " + input.currentGoalDescription() : "(none)";
		String currentSubGoal = input.currentSubGoalDescription() != null ? "- " + input.currentSubGoalDescription() : "(none)";

		String remainingGoals = input.remainingGoals() == null || input.remainingGoals().isEmpty()
				? "(none)"
				: input.remainingGoals().stream()
				.map(g -> "- " + g)
				.reduce((a, b) -> a + "\n" + b)
				.orElse("(none)");

		String completedGoals = input.completedGoals() == null || input.completedGoals().isEmpty()
				? "(none)"
				: input.completedGoals().stream()
				.map(g -> "- " + g)
				.reduce((a, b) -> a + "\n" + b)
				.orElse("(none)");

		String feedbackBlock = "";
		if (input.lastRejectionFeedback() != null && !input.lastRejectionFeedback().isBlank()) {
			feedbackBlock = "\n\n--- LAST REJECTION FEEDBACK ---\n" + input.lastRejectionFeedback().trim();
		}

		String recommendationBlock = "";
		if (input.recommendation() != null && !input.recommendation().isBlank()) {
			recommendationBlock += "\n### Recommendation\n";
			recommendationBlock += input.recommendation() + "\n";
		}

		return getUserPromptTemplate()
				.replace("{root_goal}", rootGoal)
				.replace("{current_subgoal}", currentSubGoal)
				.replace("{remaining_goals}", remainingGoals)
				.replace("{completed_goals}", completedGoals)
				.replace("{diff_summary}", diffSummary)
				.replace("{project_structure}", structure)
				.replace("{cycle_name}", cycleName)
				+ feedbackBlock
				+ recommendationBlock;
	}

	public static String getUserPromptTemplate() {
		return """
                Given the following context, evolve the application accordingly.

                --- CURRENT OBJECTIVE ---
                {root_goal}

                --- CURRENT FOCUS ---
                {current_subgoal}

                --- REMAINING GOALS ---
                {remaining_goals}

                --- COMPLETED GOALS ---
                {completed_goals}               

                --- DIFF SUMMARY ---
                {diff_summary}

                --- CURRENT STRUCTURE ---
                {project_structure}

                --- TASK CONTEXT ---
                You are in cycle: {cycle_name}
                Evaluate whether the system should mutate code, delete outdated files, or leave them untouched.

                Apply changes strictly according to the contract. Respond ONLY with:
                - Code blocks for added/modified files (format required)
                - #Delete [relative/path] for deletions
                - #GoalStackAppend, #GoalStackUpdate, #GoalStackRemove if needed               
                - No explanation or commentary

                Do NOT output unchanged files.
                Do NOT comment inside or outside the code blocks unless specified by the contract.
                """;
	}

	public static String getSystemPrompt(boolean isBootstrap) {
		String role = isBootstrap
				? "[BOOTSTRAP MODE]\nYou are Autobuilder, an autonomous code generation agent."
				: "You are an autonomous software agent evolving a Java Spring Boot project.";

		return role + "\n\n" + asPromptBlock();
	}

	public static DiffReport emptyDiffReport() {
		return new DiffReport(Collections.emptyList());
	}
}
