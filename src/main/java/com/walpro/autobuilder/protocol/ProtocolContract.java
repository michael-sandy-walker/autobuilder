package com.walpro.autobuilder.protocol;

import com.walpro.autobuilder.diff.DiffPromptFormatter;
import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.model.ProjectInput;

import java.util.Collections;
import java.util.List;

/**
 * Represents the current contract version between the Autobuilder system and the LLM.
 * Can be expanded to support validation and versioning of message format expectations.
 */
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
        return builder.toString();
    }

    public static String getUserPrompt(String cycleName, ProjectInput input) {
        String diffSummary = input.diff().entries().isEmpty()
            ? "No structural changes detected."
            : /*input.diff().entries().stream()
                  .map(e -> "- [" + e.type() + "] " + e.path())
                  .reduce((a, b) -> a + "\n" + b)
                  .orElse("");*/
            	DiffPromptFormatter.formatForLLM(input.diff());

        String structure = input.structureSummary();

        return getUserPromptTemplate()
            .replace("{diff_summary}", diffSummary)
            .replace("{project_structure}", structure)
            .replace("{cycle_name}", cycleName);
    }

    public static String getUserPromptTemplate() {
        return """
            Given the following context, evolve the application accordingly.

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
            - No explanation or commentary

            Do NOT output unchanged files.
            Do NOT comment inside or outside the code blocks unless specified by the contract.
            """;
    }

    public static String getSystemPrompt(boolean isBootstrap) {
        String role = isBootstrap
            ? "You are Autobuilder, an autonomous code generation agent."
            : "You are an autonomous software agent evolving a Java Spring Boot project.";

        return role + "\n\n" + asPromptBlock();
    }
    
    public static DiffReport emptyDiffReport() {
        return new DiffReport(Collections.emptyList());
    }
}
