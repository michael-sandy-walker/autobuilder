package com.walpro.autobuilder.agent.merge.protocol;

import com.walpro.autobuilder.agent.merge.model.MergeOutcome;

public class MergeResponseParser {

    public MergeOutcome parse(String content) {
        int start = content.indexOf("```java [");
        if (start == -1) return new MergeOutcome(false, null, "Missing code block");

        int filenameEnd = content.indexOf("]", start);
        int codeStart = content.indexOf("\n", filenameEnd);
        int codeEnd = content.indexOf("```", codeStart + 1);

        if (codeStart == -1 || codeEnd == -1) return new MergeOutcome(false, null, "Malformed code block");

        String merged = content.substring(codeStart + 1, codeEnd).trim();
        return new MergeOutcome(true, merged, "Successfully parsed merged content.");
    }
}
