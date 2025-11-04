package com.walpro.autobuilder.protocol.merge;

import com.walpro.autobuilder.agent.merge.model.MergeOutcome;

import java.util.ArrayList;
import java.util.List;

public class MergeResponseParser {

    public MergeOutcome parse(String content) {
        try {
            String reasoning = "No reasoning provided";
            List<String> contributingCycles = new ArrayList<>();
            boolean manualReview = false;

            // Optional: parse metadata block
            if (content.contains("--- MERGE METADATA ---")) {
                int metaStart = content.indexOf("--- MERGE METADATA ---");
                int codeBlockStart = content.indexOf("```java [", metaStart);

                String metadataSection = content.substring(metaStart, codeBlockStart);
                for (String line : metadataSection.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("reasoning:")) {
                        reasoning = line.substring("reasoning:".length()).trim();
                    } else if (line.startsWith("manualReviewRecommended:")) {
                        manualReview = Boolean.parseBoolean(line.substring("manualReviewRecommended:".length()).trim());
                    } else if (line.startsWith("-")) {
                        contributingCycles.add(line.substring(1).trim());
                    }
                }
            }

            // Required: extract merged code
            int start = content.indexOf("```java [");
            if (start == -1) return new MergeOutcome(false, null, "Missing code block");

            int filenameEnd = content.indexOf("]", start);
            int codeStart = content.indexOf("\n", filenameEnd);
            int codeEnd = content.indexOf("```", codeStart + 1);

            if (codeStart == -1 || codeEnd == -1)
                return new MergeOutcome(false, null, "Malformed code block");

            String merged = content.substring(codeStart + 1, codeEnd).trim();
            return new MergeOutcome(true, merged, reasoning + (manualReview ? "\n\nMANUAL REVIEW RECOMMENDED!" : ""));

        } catch (Exception e) {
            return new MergeOutcome(false, null, "Parser exception: " + e.getMessage());
        }
    }
}
