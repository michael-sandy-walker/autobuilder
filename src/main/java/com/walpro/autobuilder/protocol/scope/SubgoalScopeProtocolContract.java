package com.walpro.autobuilder.protocol.scope;

import java.util.Locale;

public final class SubgoalScopeProtocolContract {

    private SubgoalScopeProtocolContract() {}

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

Return ONLY the following format (no extra lines):
Fit: IN_SCOPE | PARTIAL | OUT_OF_SCOPE
Confidence: <decimal 0.0..1.0>
Message: <one line>
BestMatchingSubGoalId: <id> | (none)
""";
    }

    public static boolean isValidResponse(String content) {
        if (content == null) return false;
        String s = content.trim().toLowerCase(Locale.ROOT);

        boolean hasFit = s.contains("fit:");
        boolean hasConf = s.contains("confidence:");
        boolean hasMsg = s.contains("message:");
        boolean hasBest = s.contains("bestmatchingsubgoalid:");

        if (!(hasFit && hasConf && hasMsg && hasBest)) return false;

        // Minimal token validation
        if (!(s.contains("fit: in_scope") || s.contains("fit: partial") || s.contains("fit: out_of_scope"))) {
            return false;
        }

        return true;
    }
}
