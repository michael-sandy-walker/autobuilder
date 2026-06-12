package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.evaluation.CriterionResultEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-BP step 1: per-criterion verification on the {@code #NoDiff} route. The null-eval
 * EVOLUTION_NOOP promotion path never runs an evaluation, so subgoals promoted that way leave
 * zero per-criterion evidence in the graph (observed: the run that promoted {@code goal-f6d38fbe}
 * recorded no Criteria Progress at all). This service runs one focused LLM pass — acceptance
 * criteria against the <i>current</i> output files — and persists the results as
 * {@code CriterionResult} nodes (with the T4-BP-2 stable {@code criterionKey}), so the graph
 * accumulates pass/fail evidence for subgoals that never merge.
 *
 * <p>Recording, not gating: the promotion decision is unchanged in this step. The future read
 * path (T4-BP step 3) gates promotion on this accumulated evidence once it exists in the field.
 *
 * <p>Best-effort by contract: empty result on missing criteria, missing output, LLM failure, or
 * unparseable response — callers must treat empty as "no evidence", never as "failed".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CriteriaVerificationService {

    static final int MAX_FILES = 25;
    static final int MAX_LINES_PER_FILE = 150;

    private static final Pattern ASSESSMENT_LINE =
            Pattern.compile("(?m)^\\s*(\\d+)\\.\\s*([✓✗])\\s*(.+)$");

    private static final String SYSTEM_PROMPT = """
            You verify whether acceptance criteria are satisfied by the current state of a codebase.
            Assess only what the shown files actually implement. Answer strictly in the requested format with no extra commentary.

            BEHAVIOURAL GRADING RULE: when a requirement names internal structure (a specific
            method, field, class, or helper name), grade the observable behaviour that requirement
            implies, NOT the literal identifier. The implementation may choose any internal shape that
            produces the behaviour. Example: "ServiceX.findItemsByOwner() correctly retrieves items"
            is satisfied by ANY code path that retrieves items for an owner, regardless of method name.
            Grade ✗ only when the behaviour itself is absent from the shown files.
            """;

    private final LLMClient llmClient;
    private final AgentGraphService agentGraphService;

    /**
     * Verifies the current subgoal's acceptance criteria against the current output files,
     * persists the per-criterion results to the graph, and returns the pass map
     * (criterion index → passed). Empty map when there is nothing to verify or on failure.
     */
    public Map<Integer, Boolean> verifyAndRecord(CycleContextData context, String subGoalId) {
        if (context == null || subGoalId == null) return Map.of();
        List<String> criteria = context.currentSubGoalCriteria();
        if (criteria == null || criteria.isEmpty()) {
            // Criteria-less subgoal: the description itself is the only requirement statement
            // there is — verify it as a single feature. Without this, criteria-less goals can
            // neither be rescued at exhaustion nor confirmed on a completion belief (observed
            // 2026-06-11 22:55: "Create Member entity class" — Member.java on disk, no criteria,
            // and the completion belief had nothing to check against).
            String description = context.currentSubGoalDescription();
            if (description == null || description.isBlank()) {
                log.info("T4-BP-1: no criteria and no description on subgoal {} — nothing to verify", subGoalId);
                return Map.of();
            }
            criteria = List.of(description);
        }
        Map<String, String> output = context.previousOutput();
        if (output == null || output.isEmpty()) {
            log.info("T4-BP-1: no output files on context — skipping verification for {}", subGoalId);
            return Map.of();
        }

        Map<Integer, Boolean> passMap = verifyFeatures(context.currentSubGoalDescription(), criteria, output);
        if (passMap.isEmpty()) return Map.of();

        recordToGraph(context, subGoalId, criteria, passMap);
        long passed = passMap.values().stream().filter(Boolean::booleanValue).count();
        log.info("T4-BP-1: verified {} criteria for {} against output — {} passed",
                passMap.size(), subGoalId, passed);
        return passMap;
    }

    /**
     * Reusable core: verifies a list of requirement statements against the given output files
     * in one LLM pass and returns the pass map (1-based feature index → satisfied). Empty map
     * on LLM failure or unparseable response — callers must treat empty as "no evidence",
     * never as "failed". Consumers: the #NoDiff route above (T4-BP-1) and the T2-EA root
     * coverage gate (filters description-level gap candidates against actual code before
     * injecting subgoals).
     */
    public Map<Integer, Boolean> verifyFeatures(String subjectDescription, List<String> features,
                                                Map<String, String> outputFiles) {
        if (features == null || features.isEmpty()
                || outputFiles == null || outputFiles.isEmpty()) {
            return Map.of();
        }
        String response;
        try {
            List<Message> messages = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", buildPrompt(subjectDescription, features, outputFiles)));
            ResponseEntity<ChatResponse> r = llmClient.ask(messages);
            response = r.getBody() != null ? r.getBody().getFirstContent() : null;
        } catch (Exception e) {
            log.warn("T4-BP-1: verification LLM call failed ({}) — no evidence recorded", e.getMessage());
            return Map.of();
        }
        if (response == null || response.isBlank()) return Map.of();

        Map<Integer, Boolean> passMap = parse(response, features.size());
        if (passMap.isEmpty()) {
            log.warn("T4-BP-1: unparseable verification response — no evidence recorded");
        }
        return passMap;
    }

    private String buildPrompt(String subjectDescription, List<String> criteria, Map<String, String> output) {
        StringBuilder sb = new StringBuilder();
        sb.append("GOAL:\n").append(
                subjectDescription != null ? subjectDescription : "(none)");
        sb.append("\n\nREQUIREMENTS:\n");
        for (int i = 0; i < criteria.size(); i++) {
            sb.append(i + 1).append(". ").append(criteria.get(i)).append("\n");
        }
        sb.append("\nCURRENT PROJECT FILES:\n");
        int files = 0;
        for (Map.Entry<String, String> e : output.entrySet()) {
            if (files++ >= MAX_FILES) {
                sb.append("... (").append(output.size() - MAX_FILES).append(" more files omitted)\n");
                break;
            }
            sb.append("=== ").append(e.getKey()).append(" ===\n");
            sb.append(capLines(e.getValue())).append("\n");
        }
        sb.append("""

                For EACH requirement, answer with exactly one line:
                <requirement number>. ✓ <requirement text>   (when the current files satisfy it)
                <requirement number>. ✗ <requirement text>   (when they do not)
                """);
        return sb.toString();
    }

    private static String capLines(String content) {
        if (content == null) return "";
        String[] lines = content.split("\\R", -1);
        if (lines.length <= MAX_LINES_PER_FILE) return content;
        return String.join("\n", java.util.Arrays.copyOf(lines, MAX_LINES_PER_FILE))
                + "\n... (" + (lines.length - MAX_LINES_PER_FILE) + " lines truncated)";
    }

    /** Parses {@code <n>. ✓/✗ <text>} lines; indices outside [1, criterionCount] are ignored. */
    Map<Integer, Boolean> parse(String raw, int criterionCount) {
        Map<Integer, Boolean> out = new LinkedHashMap<>();
        Matcher m = ASSESSMENT_LINE.matcher(raw);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx < 1 || idx > criterionCount) continue;
            out.put(idx, "✓".equals(m.group(2)));
        }
        return out;
    }

    private void recordToGraph(CycleContextData context, String subGoalId,
                               List<String> criteria, Map<Integer, Boolean> passMap) {
        String actionId = "noDiff-verify-" + (context.cycleName() != null ? context.cycleName() : "unknown");
        List<CriterionResultEntity> results = new ArrayList<>(passMap.size());
        for (Map.Entry<Integer, Boolean> e : passMap.entrySet()) {
            String text = e.getKey() - 1 < criteria.size() ? criteria.get(e.getKey() - 1) : "";
            results.add(new CriterionResultEntity(
                    actionId, e.getKey(), text, e.getValue(), context.cycleName(), subGoalId));
        }
        try {
            agentGraphService.linkCriterionResults(results);
        } catch (Exception e) {
            log.warn("T4-BP-1: graph persistence of verification results failed: {}", e.getMessage());
        }
    }
}
