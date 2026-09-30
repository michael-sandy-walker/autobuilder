package com.walpro.autobuilder.protocol.merge;

import com.walpro.autobuilder.agent.merge.model.MergeOutcome;

public class MergeResponseParser {

    public MergeOutcome parse(String content) {
        try {
            if (content == null) {
                return new MergeOutcome(false, null, "Missing content");
            }

            if (!content.contains("```java [")) {
                return new MergeOutcome(false, null, "Missing code block");
            }

            int start = content.indexOf("```java [");
            int filenameEnd = content.indexOf("]", start);
            if (filenameEnd == -1) {
                return new MergeOutcome(false, null, "Malformed code block");
            }

            int codeStart = content.indexOf("\n", filenameEnd);
            int codeEnd = com.walpro.autobuilder.protocol.FencedBlocks.closeAfter(content, codeStart + 1);

            if (codeStart == -1 || codeEnd == -1) {
                return new MergeOutcome(false, null, "Malformed code block");
            }

            String merged = content.substring(codeStart + 1, codeEnd).trim();

            return new MergeOutcome(true, merged, null);

        } catch (Exception e) {
            return new MergeOutcome(false, null, "Parser exception: " + e.getMessage());
        }
    }
}