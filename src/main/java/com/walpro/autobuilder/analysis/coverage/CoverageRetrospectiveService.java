package com.walpro.autobuilder.analysis.coverage;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * T3-BN: Compares the root goal's stated requirements against the subgoals that were
 * attempted (COMPLETED or GIVEN_UP). If significant features are missing — i.e. they
 * appear in the root description but were never targeted by any subgoal — the service
 * returns them as new subgoal descriptions so the caller can reinject them before
 * declaring GOAL_EXHAUSTED.
 * <p>
 * Returns an empty list when all requirements appear to be covered, or on any LLM error
 * (conservative fallback — allows GIVEN_UP to proceed rather than looping forever).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverageRetrospectiveService {

    /** Cap on how many terminal goals are sent to the LLM to avoid token overflow. */
    private static final int MAX_PROMPT_GOALS = 25;

    private static final String SYSTEM_PROMPT = """
            You are a software project coverage analyst.
            Your task is to identify requirements from a root goal that were never addressed by the attempted work items.
            Be concise and focus only on functionally significant gaps — do not invent new features beyond what the root goal states.
            """;

    private final LLMClient llmClient;

    /**
     * Calls the LLM to identify features in {@code rootDescription} that are not covered
     * by any of the terminal goals in {@code allGoals}.
     *
     * @param rootDescription the original root goal specification
     * @param allGoals        all goals in the goal map (terminal and active)
     * @return list of missing feature descriptions to inject as new subgoals;
     *         empty if all covered or on LLM failure
     */
    public List<String> findMissingFeatures(String rootDescription, List<GoalEntity> allGoals) {
        return reviewMissingFeatures(rootDescription, allGoals).orElse(List.of());
    }

    /**
     * Answer-bearing variant: distinguishes "reviewed and found no gaps" from "no answer"
     * (LLM failure / empty response). The completion edge must treat the two differently —
     * on 2026-07-02 the reviewer timed out 3×, the abstain collapsed into "no gaps", and the
     * root COMPLETED with two of the spec's three resources never decomposed at all (false
     * completion #2). Absent evidence defers completion; it must never grant it.
     *
     * @return {@code Optional.empty()} when no answer could be obtained;
     *         {@code Optional.of(list)} when the review ran (empty list = genuinely no gaps —
     *         blank description / nothing attempted count as reviewable "no gaps to report")
     */
    public Optional<List<String>> reviewMissingFeatures(String rootDescription, List<GoalEntity> allGoals) {
        if (rootDescription == null || rootDescription.isBlank()) {
            log.warn("root description is blank — skipping coverage retrospective");
            return Optional.of(List.of());
        }

        List<GoalEntity> terminals = allGoals.stream()
                .filter(g -> g.getStatus() == GoalStatus.COMPLETED || g.getStatus() == GoalStatus.GIVEN_UP)
                .limit(MAX_PROMPT_GOALS)
                .toList();

        if (terminals.isEmpty()) {
            log.info("no terminal goals found — skipping retrospective (nothing was attempted)");
            return Optional.of(List.of());
        }

        String userPrompt = buildPrompt(rootDescription, terminals);

        try {
            List<Message> messages = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", userPrompt)
            );

            ResponseEntity<ChatResponse> response = llmClient.ask(messages);
            if (response.getBody() == null || response.getBody().getFirstContent() == null) {
                log.warn("LLM returned empty response — NO coverage result (caller decides: completion defers, give-up proceeds)");
                return Optional.empty();
            }

            return Optional.of(parseResponse(response.getBody().getFirstContent()));

        } catch (Exception e) {
            log.warn("LLM call failed ({}). NO coverage result — caller decides (completion defers, give-up proceeds).", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildPrompt(String rootDescription, List<GoalEntity> terminals) {
        StringBuilder sb = new StringBuilder();
        sb.append("Root goal (the full feature set to implement):\n");
        sb.append(rootDescription).append("\n\n");
        sb.append("Work items attempted (completed or given up):\n");
        for (int i = 0; i < terminals.size(); i++) {
            GoalEntity g = terminals.get(i);
            String status = g.getStatus() == GoalStatus.COMPLETED ? "COMPLETED" : "GIVEN_UP";
            sb.append(i + 1).append(". [").append(status).append("] ").append(g.getDescription()).append("\n");
        }
        sb.append("""

                Identify any significant features from the root goal that are NOT meaningfully covered by any of the attempted work items.
                Consider paraphrasing and partial coverage when assessing — do not report a feature as missing if a closely related work item addressed it.
                Do NOT propose work items that paraphrase or restate any GIVEN_UP entry above. If all significant features have already been attempted, respond with #COMPLETE.

                If all requirements from the root goal appear to be covered, respond with exactly:
                #COMPLETE

                Otherwise, list ONLY the missing requirements, one per line, prefixed with "- ":
                - <missing requirement 1>
                - <missing requirement 2>
                """);
        return sb.toString();
    }

    /**
     * Parses the LLM response into a list of missing feature strings.
     * Returns empty list if response contains {@code #COMPLETE} or no {@code "- "} lines.
     */
    List<String> parseResponse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String trimmed = raw.trim();

        if (trimmed.contains("#COMPLETE")) {
            log.info("LLM confirmed full coverage (#COMPLETE)");
            return List.of();
        }

        List<String> missing = new ArrayList<>();
        for (String line : trimmed.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("- ")) {
                String feature = stripped.substring(2).strip();
                if (!feature.isBlank()) {
                    missing.add(feature);
                }
            }
        }

        if (missing.isEmpty()) {
            log.info("LLM response contained no '- ' prefixed lines — treating as no gaps");
        } else {
            log.info("LLM identified {} missing feature(s)", missing.size());
        }

        return missing;
    }
}
