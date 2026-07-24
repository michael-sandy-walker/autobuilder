package com.walpro.autobuilder.protocol.interpretation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalReadout;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.protocol.core.ContractRule;

public class InterpretationProtocolContract {

    public static final String VERSION = "1.0-interpretation";

    private static final List<ContractRule> STATIC_RULES = loadRules();

    /** T2-EP: the JSON key whose escaped string value carries the full merged file source. */
    private static final String MERGED_CONTENT_KEY = "\"mergedContent\"";

    private static List<ContractRule> loadRules() {
        List<ContractRule> rules = new ArrayList<>();
        rules.add(new ContractRule("STRUCTURED_RESPONSE",
                "Respond using the required format headers only. Do not add prose outside these headers.",
                """
                Status: APPLIED
                Summary: The system is making steady progress but stagnation is emerging across two recent cycles.
                Directives: Prioritize replan to break the repetitive pattern before attempting another evolution.
                """
        ));
        rules.add(new ContractRule("STATUS_VALUES",
                "Status must be the literal word APPLIED or the literal word NOOP — nothing else. " +
                        "NEVER use a signal name (e.g. REPLAN_REQUESTED, LOW_CONFIDENCE) as the Status value. " +
                        "Signals are emitted on separate Signal: lines, not on the Status: line.",
                """
                APPLIED — when you emit signals or have substantive directives.
                NOOP    — when the cycle history shows no notable patterns and no signals are warranted.
                WRONG:  Status: REPLAN_REQUESTED   ← this is a signal name, not a status
                CORRECT: Status: APPLIED
                         Signal: REPLAN_REQUESTED
                """
        ));
        rules.add(new ContractRule("SIGNAL_EMISSION",
                "Optionally emit one or more Signal lines. Allowed values: REPLAN_REQUESTED, LOW_CONFIDENCE, DIVERGENCE_REQUEST. " +
                        "Emit REPLAN_REQUESTED when the same subgoal/mutation pattern is repeating. " +
                        "Emit LOW_CONFIDENCE when evaluation scores are consistently low or the outcome is ambiguous. " +
                        "Emit DIVERGENCE_REQUEST when the current evolution direction shows no progress across multiple cycles.",
                """
                Signal: REPLAN_REQUESTED
                Signal: LOW_CONFIDENCE
                """
        ));
        rules.add(new ContractRule("SUMMARY_REQUIRED",
                "Summary must be a single, concise paragraph describing the key pattern observed in the cycle history.",
                "Summary: Evolution is targeting the same file repeatedly with diminishing score returns; stagnation pattern detected."
        ));
        rules.add(new ContractRule("DIRECTIVES_REQUIRED",
                "Directives must always be present. " +
                        "If Status is NOOP, output exactly 'Directives: NONE'. " +
                        "If Status is APPLIED, output a specific, actionable instruction for the next cycle " +
                        "(e.g. emit a signal, change subgoal, broaden evolution scope). " +
                        "A generic restatement of the summary is NOT an acceptable directive.",
                "APPLIED example: Directives: Inject REPLAN_REQUESTED and increase subgoal granularity before next evolution attempt.\n" +
                        "NOOP example: Directives: NONE"
        ));
        rules.add(new ContractRule("NOOP_IS_CORRECT",
                "Status: NOOP is the expected, correct output for a healthy cycle. " +
                        "If the artifact history shows: no repeating patterns, no score decline, no stuck subgoals, " +
                        "and no concerning signals — output NOOP. " +
                        "Do NOT force Status: APPLIED with generic prose when you have no specific finding. " +
                        "Filler summaries like 'the system is making steady progress' are NOOP observations, not APPLIED findings.",
                "Status: NOOP\nSummary: No notable patterns detected; cycle history shows healthy progress.\nDirectives: NONE"
        ));
        rules.add(new ContractRule("TEMPORAL_AWARENESS",
                "Each artifact contains a cycle reference — look for fields named 'cycleId' or 'cycleName' inside the artifact content. " +
                        "Compare that value against the current cycle shown in the GOAL CONTEXT block. " +
                        "Artifacts from earlier cycles represent historical state — do not interpret them as actions that just happened. " +
                        "Only artifacts whose cycle reference matches the current cycle reflect the present situation.",
                "If planner_outcome has cycleName=cycle-1773101439676 but the current cycle is cycle-1773101462067, " +
                        "the planner acted in a previous cycle — do not summarise this as 'the planner has introduced new subgoals'."
        ));
        rules.add(new ContractRule("NO_CODE_BLOCKS",
                "Do not include any code blocks or file content in this response.",
                "WRONG: ```java\n// code\n```"
        ));
        rules.add(new ContractRule("NO_EXTRA_COMMENTARY",
                "Output ONLY the structured response lines (Status:, Summary:, Directives:, and optional Signal: lines). " +
                        "Do not output any text before, after, or between these lines — no preamble, no explanation, no markdown prose.",
                "Status: NOOP\nSummary: No notable patterns detected.\nDirectives: NONE"
        ));
        rules.add(new ContractRule("ROLLBACK_AWARENESS",
                "If a `--- ROLLBACK STATE ---` block is present, the previous cycle rolled back — the system has NOT made progress in those cycles. " +
                        "Status MUST be APPLIED. " +
                        "Do NOT describe those cycles as 'healthy', 'progressing', or 'making steady progress'. " +
                        "Do NOT describe a rolled-back mutation as 'accepted' even if its pre-rollback state showed mutationState=ACCEPTED. " +
                        "Summary MUST explicitly mention rollback (use the word 'rolled back' or 'rollback').",
                """
                Status: APPLIED
                Summary: Cycle history includes a rolled-back mutation; apparent acceptance was reverted by the post-merge build gate.
                Directives: Inject DIVERGENCE_REQUEST and target the BUILD_FIX subgoal before re-attempting the same files.
                Signal: DIVERGENCE_REQUEST
                """
        ));
        return rules;
    }

    public static List<ContractRule> rules() {
        return Collections.unmodifiableList(STATIC_RULES);
    }

    public static String asPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("INTERPRETATION PROTOCOL CONTRACT v").append(VERSION).append("\n\n");
        for (ContractRule rule : rules()) {
            sb.append("RULE: ").append(rule.id()).append("\n")
              .append("Description: ").append(rule.description()).append("\n")
              .append("Example: ").append(rule.example()).append("\n\n");
        }
        return sb.toString();
    }

    public static String getSystemPrompt() {
        return """
                You are a cycle interpretation agent for an autonomous software evolution system.
                Your job is to analyse the recent cycle history, detect steering-relevant patterns (stagnation, \
                low confidence, divergence needs, replan triggers), and emit signals that guide the next cycle.

                """ + asPromptBlock();
    }

    public static String getUserPrompt(CycleContextData ctx, List<ArtifactEntity> artifacts) {
        StringBuilder artifactBlock = new StringBuilder();
        artifactBlock.append("=== Recent Artifacts (goal-scoped) ===\n");
        for (ArtifactEntity a : artifacts) {
            artifactBlock.append("\n--- ").append(a.type()).append(" ---\n");
            if (a.content() != null) artifactBlock.append(compactArtifactContent(a.content())).append("\n");
        }

        // T2-CZ: surface the previous-cycle rollback so the interpreter cannot describe a phantom
        // ACCEPTED mutation as 'healthy progress'. Only rendered when lastRollback is present.
        String rollbackBlock = "";
        if (ctx.lastRollback() != null) {
            rollbackBlock = String.format(
                    "%n--- ROLLBACK STATE ---%nPrevious cycle %s rolled back %d path(s). Reason: %s%nRestored paths: %s%n",
                    ctx.lastRollback().cycleId(),
                    ctx.lastRollback().restoredPaths() != null ? ctx.lastRollback().restoredPaths().size() : 0,
                    ctx.lastRollback().errorKind() != null ? ctx.lastRollback().errorKind().name() : "unknown",
                    ctx.lastRollback().restoredPaths()
            );
        }

        long historyDepth = artifacts.stream()
                .filter(a -> "interpretation_summary".equals(a.type()))
                .count();
        String historyNote = historyDepth == 0
                ? "History depth: 0 prior interpretation cycles — NOOP is the default; only output APPLIED if this cycle's artifacts show an explicit, observable issue."
                : "History depth: " + historyDepth + " prior interpretation cycle(s).";

        return """
                Analyse the following cycle history and emit steering signals if warranted.

                --- GOAL CONTEXT ---
                Goal ID:    %s
                SubGoal ID: %s
                Cycle:      %s
                Goal Progress: %s
                Mutation State: %s
                Cycle Result:   %s
                %s
                %s

                --- ACTIVE SIGNALS ---
                %s

                --- ARTIFACT HISTORY ---
                %s

                Respond using this exact format:
                Status: APPLIED or NOOP
                Summary: <one-paragraph description of the key pattern, or 'No notable patterns detected' if NOOP>
                Directives: <specific action for next cycle, or NONE if NOOP>
                Signal: REPLAN_REQUESTED  (include only when a repeating pattern warrants replanning)
                Signal: LOW_CONFIDENCE    (include only when scores are consistently low)
                Signal: DIVERGENCE_REQUEST (include only when evolution direction shows no progress)

                IMPORTANT: If the cycle history shows no concerning patterns, output Status: NOOP with Directives: NONE.
                Do NOT produce boilerplate APPLIED responses when nothing is wrong.
                """.formatted(
                ctx.currentGoalId(),
                ctx.currentSubGoalId(),
                ctx.cycleName(),
                ctx.goalProgress(),
                ctx.mutationState(),
                ctx.cycleResultState(),
                historyNote,
                rollbackBlock,
                SignalReadout.of(ctx).fullMap(), // T4-BZ-1: full-map render via the readout
                artifactBlock
        );
    }

    /**
     * T2-EP: merge_payload artifacts carry the full merged file source in a {@code mergedContent}
     * JSON field — measured at ~37% of this prompt's artifact-history volume. The interpretation
     * agent detects steering patterns (score trends, stuck subgoals, rollbacks, repeated planner
     * actions) from the identity and decision fields; it never reads file bodies (its NO_CODE_BLOCKS
     * rule forbids even emitting them). This elides the body to a compact descriptor so the merge is
     * still represented by its cycle/subgoal identity without the passthrough weight. Every other
     * field — and every other artifact type — is left byte-identical.
     */
    static String compactArtifactContent(String content) {
        if (content == null || content.isEmpty()) return content;
        int keyIdx = content.indexOf(MERGED_CONTENT_KEY);
        if (keyIdx < 0) return content;
        // Index-based, escape-aware scan — NOT a regex. A `(?:[^"\\]|\\.)*` quantifier over the
        // full merged file source (thousands of chars) overflows java.util.regex's recursive
        // matcher (StackOverflowError, one frame per char), aborting the interpretation cycle —
        // the very body sizes this method exists to elide are the ones that blow the stack.
        StringBuilder sb = new StringBuilder(content.length());
        int pos = 0;
        while (keyIdx >= 0) {
            int i = keyIdx + MERGED_CONTENT_KEY.length();
            while (i < content.length() && Character.isWhitespace(content.charAt(i))) i++;
            if (i >= content.length() || content.charAt(i) != ':') {
                keyIdx = content.indexOf(MERGED_CONTENT_KEY, keyIdx + MERGED_CONTENT_KEY.length());
                continue;
            }
            i++; // past ':'
            while (i < content.length() && Character.isWhitespace(content.charAt(i))) i++;
            if (i >= content.length() || content.charAt(i) != '"') {
                keyIdx = content.indexOf(MERGED_CONTENT_KEY, keyIdx + MERGED_CONTENT_KEY.length());
                continue;
            }
            int bodyStart = i + 1; // first char of the escaped body, past the opening quote
            // Walk to the closing unescaped quote, counting escaped newlines. Lines = escaped
            // newline count + 1; a merge with no newlines is a one-line body.
            int j = bodyStart;
            int lines = 1;
            while (j < content.length()) {
                char c = content.charAt(j);
                if (c == '\\' && j + 1 < content.length()) {
                    if (content.charAt(j + 1) == 'n') lines++;
                    j += 2; // skip the escaped pair (\" \\ \n …) without treating it as a delimiter
                    continue;
                }
                if (c == '"') break; // closing quote of the string value
                j++;
            }
            // Emit up to and including the opening quote, then the elision marker; resume at the
            // closing quote so it (and the rest of the JSON) is preserved byte-identically.
            sb.append(content, pos, bodyStart);
            sb.append("<file body elided — ").append(lines).append(" lines>");
            pos = Math.min(j, content.length());
            keyIdx = content.indexOf(MERGED_CONTENT_KEY, pos);
        }
        sb.append(content, pos, content.length());
        return sb.toString();
    }

    public static boolean isValidResponse(String response) {
        if (response == null || response.isBlank()) return false;

        boolean isApplied = response.lines().anyMatch(l -> l.trim().startsWith("Status: APPLIED"));
        boolean isNoop = response.lines().anyMatch(l -> l.trim().startsWith("Status: NOOP"));

        if (!isApplied && !isNoop) return false;

        boolean hasSummary = response.lines().anyMatch(l ->
                l.trim().toLowerCase().startsWith("summary:") && l.trim().length() > "summary:".length());
        if (!hasSummary) return false;

        if (isApplied) {
            // APPLIED requires either a Signal line or a non-empty, non-"NONE" Directives
            boolean hasSignal = response.lines().anyMatch(l -> l.trim().toLowerCase().startsWith("signal:"));
            boolean hasDirective = response.lines()
                    .filter(l -> l.trim().toLowerCase().startsWith("directives:"))
                    .map(l -> l.trim().substring("directives:".length()).trim())
                    .anyMatch(v -> !v.isBlank() && !v.equalsIgnoreCase("NONE"));
            return hasSignal || hasDirective;
        }

        return true; // NOOP only needs status + summary
    }
}
