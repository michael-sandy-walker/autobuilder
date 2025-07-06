package com.walpro.autobuilder.agent.merge.protocol;

import com.walpro.autobuilder.agent.merge.model.MergeInput;

public class MergeProtocolContract {

    public static final String VERSION = "1.0-merge";

    public static String getSystemPrompt() {
        return """
            You are a MergeAgent. Your job is to merge multiple variants of a Java file into one consistent, goal-aligned version.

            RULES:
            - Preserve all features and annotations added in any variant unless conflicting.
            - Resolve conflicts intelligently based on subgoal relevance.
            - Do not duplicate methods, imports, or annotations.
            - Do not remove existing functionality without clear reason.
            - Maintain idiomatic Java and consistent formatting.

            RESPONSE FORMAT:
            ```java [<file-path>]
            // merged content
            ```
            """;
    }

    public static String getUserPrompt(String path, String baseContent, MergeInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- BASE FILE ---\n");
        sb.append("[").append(path).append("]\n");
        sb.append(baseContent).append("\n\n");

        for (var variant : input.variants()) {
            sb.append("--- VARIANT ---\n");
            sb.append("Subgoals: ").append(variant.subgoals()).append("\n");
            sb.append("Origin Cycle: ").append(variant.originCycleId()).append("\n");
            sb.append("```java\n").append(variant.content()).append("\n```\n\n");
        }

        sb.append("--- CURRENT GOAL CONTEXT ---\n");
        sb.append("Merge all relevant changes into a stable version of ").append(path).append(".\n");
        sb.append("Preserve all functionality unless directly conflicting.\n\n");

        sb.append("--- RESPONSE FORMAT ---\n");
        sb.append("```java [").append(path).append("]\n<merged content>\n```");

        return sb.toString();
    }

    public static boolean isValidResponse(String content) {
        return content != null && content.contains("```java [");
    }
}
