package com.walpro.autobuilder.protocol.scope;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SubgoalScopeProtocolContract {

    private SubgoalScopeProtocolContract() {}

    // Same anchor as SubgoalScopeResponseParser.FIT — must match a line containing exactly one token.
    private static final Pattern VALID_FIT =
            Pattern.compile("(?im)^\\s*Fit\\s*:\\s*(IN_SCOPE|PARTIAL|OUT_OF_SCOPE)\\s*$");

    public static String getSystemPrompt() {
        return """
You are a strict classifier for subgoal scope alignment in an autonomous software-building loop.

You MUST follow these rules:
- Do NOT propose code.
- Do NOT propose new subgoals.
- Only classify whether the mutation matches the CURRENT subgoal.
- If you output BestMatchingSubGoalId, it MUST be one of the provided RemainingSubGoals ids.
- If you are uncertain, choose PARTIAL.

NO_OP RULE (very important):
- If DIFF SUMMARY indicates NO_OP: true OR the changed/deleted counts are both zero,
  then respond:
  Fit: IN_SCOPE
  Confidence: 0.20
  Message: No changes detected; scope cannot be judged.
  BestMatchingSubGoalId: (none)

OUTPUT FORMAT RULES (strictly enforced):
- Output EXACTLY the four lines below — no preamble, no explanation, no markdown, no extra text before or after.
- Do NOT write "I would classify..." or any other prose. Output only the structured lines.
- WRONG: "The diff involves refactoring... I would classify this as IN_SCOPE..."
- CORRECT: four structured lines only.

Return ONLY the following format (no extra lines).
Choose EXACTLY ONE token for Fit — do NOT output all three:

Fit: IN_SCOPE
Confidence: 0.72
Message: The changes directly implement the current subgoal requirements.
BestMatchingSubGoalId: (none)

Valid Fit values (pick one): IN_SCOPE, PARTIAL, OUT_OF_SCOPE
Valid BestMatchingSubGoalId values: one of the provided RemainingSubGoals ids, or (none)
""";
    }

    public static boolean isValidResponse(String content) {
        if (content == null) return false;
        String s = content.trim().toLowerCase(Locale.ROOT);

        boolean hasConf = s.contains("confidence:");
        boolean hasMsg = s.contains("message:");
        boolean hasBest = s.contains("bestmatchingsubgoalid:");

        if (!(hasConf && hasMsg && hasBest)) return false;

        // Use the same anchored regex as the parser so "Fit: IN_SCOPE | PARTIAL | OUT_OF_SCOPE"
        // (template echo) is rejected — substring matching falsely accepted it before.
        return VALID_FIT.matcher(content).find();
    }
}
