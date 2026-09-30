package com.walpro.autobuilder.protocol.merge;

import java.util.List;

import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.core.ProblemContextPromptBlocks;

public class MergeProtocolContract {

    public static final String VERSION = "1.0-merge";

    public static final String SYSTEM_PROMPT = """
            You are a MergeAgent. Your job is to merge multiple variants of a Java file into one consistent, goal-aligned version.

            RULES:
            - Preserve all features and annotations added in any variant unless conflicting.
            - Resolve conflicts intelligently based on subgoal relevance.
            - Do not duplicate methods, imports, or annotations.
            - Do not remove existing functionality without clear reason.
            - Maintain idiomatic Java and consistent formatting.
            - If BlockingInScope > 0, avoid merging changes that reintroduce or preserve the problematic structure referenced by keywords. Prefer the variant(s) that best address the keywords while preserving functionality.
            - PREFER_HIGHEST_SCORED_VARIANT: Each variant includes an Evaluation Score. When two variants implement the same method or block differently, prefer the implementation from the variant with the highest score. Do not revert to the base file's implementation if a higher-scored variant provides an alternative.

            RESPONSE FORMAT:
            ```java [<file-path>]
            // merged content
            ```
            """;

    public static String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    public static String getUserPrompt(String path, String baseContent, List<FileVariant> variants, CycleContextData context) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- BASE FILE ---\n");
        sb.append("[").append(path).append("]\n");
        sb.append(baseContent).append("\n\n");
        sb.append("--- CURRENT GOAL CONTEXT ---\n");
        sb.append("Current Goal: ").append(context.currentGoalDescription()).append("\n");
        sb.append("Current Subgoal: ").append(context.currentSubGoalDescription()).append("\n");

        for (var variant : variants) {
            sb.append("--- VARIANT ---\n");
            sb.append("Subgoals: ").append(variant.subgoals()).append("\n");
            sb.append("Origin Cycle: ").append(variant.originCycleId()).append("\n");
            if (variant.evaluationScore() != null) {
                sb.append("Evaluation Score: ").append(String.format("%.3f", variant.evaluationScore())).append("\n");
            }
            sb.append("```java\n").append(variant.content()).append("\n```\n\n");
        }

        sb.append("--- CURRENT GOAL CONTEXT ---\n");
        sb.append("Merge all relevant changes into a stable version of ").append(path).append(".\n");
        sb.append("Preserve all functionality unless directly conflicting.\n\n");

        sb.append(ProblemContextPromptBlocks.asPromptBlock(
                context.problemContext(),
                context.currentGoalId(),
                context.currentSubGoalId()
        ));
        sb.append("\n");

        sb.append("--- RESPONSE FORMAT ---\n");
        sb.append("```java [").append(path).append("]\n<merged content>\n```");

        return sb.toString();
    }

    public static boolean isValidResponse(String content) {
        if (content == null) return false;

        int start = content.indexOf("```java [");
        if (start < 0) return false;

        int filenameEnd = content.indexOf("]", start);
        if (filenameEnd < 0) return false;

        int codeStart = content.indexOf("\n", filenameEnd);
        if (codeStart < 0) return false;

        int codeEnd = com.walpro.autobuilder.protocol.FencedBlocks.closeAfter(content, codeStart + 1);
        if (codeEnd < 0) return false;

        // ensure there is at least something inside (non-whitespace)
        String merged = content.substring(codeStart + 1, codeEnd).trim();
        return !merged.isBlank();
    }
}
