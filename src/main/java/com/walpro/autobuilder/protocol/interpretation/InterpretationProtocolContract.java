package com.walpro.autobuilder.protocol.interpretation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.protocol.core.ContractRule;

public class InterpretationProtocolContract {

    public static final String VERSION = "1.0-interpretation";

    private static final List<ContractRule> STATIC_RULES = loadRules();

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
                "Status must be APPLIED (you detected meaningful patterns or steering advice) or NOOP (nothing notable).",
                """
                APPLIED — when you emit signals or have substantive directives.
                NOOP    — when the cycle history shows no notable patterns and no signals are warranted.
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
        rules.add(new ContractRule("DIRECTIVES_OPTIONAL",
                "Directives should be a brief sentence or two guiding the next cycle. Omit or leave blank if NOOP.",
                "Directives: Inject REPLAN_REQUESTED and increase subgoal granularity before next evolution attempt."
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
            if (a.content() != null) artifactBlock.append(a.content()).append("\n");
        }

        return """
                Analyse the following cycle history and emit steering signals if warranted.

                --- GOAL CONTEXT ---
                Goal ID:    %s
                SubGoal ID: %s
                Cycle:      %s
                Goal Progress: %s
                Mutation State: %s
                Cycle Result:   %s

                --- ACTIVE SIGNALS ---
                %s

                --- ARTIFACT HISTORY ---
                %s

                Respond using this exact format:
                Status: APPLIED or NOOP
                Summary: <one-paragraph description of the key pattern>
                Directives: <brief guidance for the next cycle, or omit if NOOP>
                (Optional) Signal: REPLAN_REQUESTED
                (Optional) Signal: LOW_CONFIDENCE
                (Optional) Signal: DIVERGENCE_REQUEST
                """.formatted(
                ctx.currentGoalId(),
                ctx.currentSubGoalId(),
                ctx.cycleName(),
                ctx.goalProgress(),
                ctx.mutationState(),
                ctx.cycleResultState(),
                ctx.signals() != null ? ctx.signals() : "(none)",
                artifactBlock
        );
    }

    public static boolean isValidResponse(String response) {
        if (response == null || response.isBlank()) return false;

        boolean hasStatus = response.lines().anyMatch(l ->
                l.trim().startsWith("Status: APPLIED") || l.trim().startsWith("Status: NOOP"));

        boolean hasSummary = response.lines().anyMatch(l ->
                l.trim().toLowerCase().startsWith("summary:") && l.trim().length() > "summary:".length());

        return hasStatus && hasSummary;
    }
}
