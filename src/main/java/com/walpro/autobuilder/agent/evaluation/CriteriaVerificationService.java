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
import java.util.Optional;
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
     * T4-BP step 3: verdicts cached by (criteria + output) digest, so re-checking the same code at
     * the completion edge never re-calls the LLM. Bounds verification cost — a given
     * (criteria, code-state) is judged at most once. Cleared on reset via {@link #resetCostMetrics()}.
     */
    private final java.util.Map<String, Map<Integer, Boolean>> verdictCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** T4-BP step 3: cumulative completion-verification LLM calls — surfaced so cost is measured, not guessed. */
    private final java.util.concurrent.atomic.AtomicInteger completionVerificationCalls = new java.util.concurrent.atomic.AtomicInteger();

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

    /**
     * T4-BP step 3 — positive-corroboration completion gate. Re-verifies {@code requiredCriteria}
     * against the <i>current</i> output with one focused pass and returns a summary of the criteria
     * NOT positively confirmed (→ caller refuses completion and injects them as a work item), or
     * empty when every required criterion is confirmed.
     *
     * <p>Why this exists: closure trusted each subgoal's <i>banked</i> pass-status, which can be set
     * optimistically by score-based / #NoDiff promotion without verifying the final code. A hollow
     * artifact (stub bodies, uncomputed values) thus reached a false COMPLETED (2026-06-16) — the
     * coverage gate's leading "is this gap implemented?" framing over a flat blob false-positived.
     * This asks the inverse, criterion by criterion, against the code that actually exists now.
     *
     * <p>Cost: digest-cached (unchanged code ⇒ no LLM call) and call-counted. Conservative on no
     * evidence — empty criteria, empty output, or total LLM failure return empty (never block a run
     * on a transient hiccup); only a parsed ✗ (or a criterion the model omits from a parsed answer)
     * counts as unconfirmed.
     */
    public Optional<String> verifyCompletion(String rootDescription, List<String> requiredCriteria,
                                             Map<String, String> outputFiles) {
        if (requiredCriteria == null || requiredCriteria.isEmpty()
                || outputFiles == null || outputFiles.isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, Boolean> passMap = verifyFeaturesCached(rootDescription, requiredCriteria, outputFiles);
        if (passMap.isEmpty()) {
            // No verdict (LLM failure / unparseable): no evidence is never treated as failure.
            return Optional.empty();
        }
        List<String> unconfirmed = new ArrayList<>();
        for (int i = 0; i < requiredCriteria.size(); i++) {
            Boolean ok = passMap.get(i + 1);
            if (ok == null || !ok) unconfirmed.add(requiredCriteria.get(i));
        }
        if (unconfirmed.isEmpty()) return Optional.empty();
        String head = unconfirmed.stream().limit(5).reduce((a, b) -> a + "; " + b).orElse("");
        String tail = unconfirmed.size() > 5 ? " …and " + (unconfirmed.size() - 5) + " more" : "";
        return Optional.of(unconfirmed.size() + " acceptance criterion(s) not confirmed in the current code: " + head + tail);
    }

    /** Cached + counted wrapper over {@link #verifyFeatures}; one LLM call per distinct (criteria, code) state. */
    private Map<Integer, Boolean> verifyFeaturesCached(String desc, List<String> criteria, Map<String, String> output) {
        String digest = digest(criteria, output);
        Map<Integer, Boolean> cached = verdictCache.get(digest);
        if (cached != null) {
            log.info("T4-BP-3: completion verification cache hit — no LLM call ({} criteria)", criteria.size());
            return cached;
        }
        Map<Integer, Boolean> passMap = verifyFeatures(desc, criteria, output);
        int n = completionVerificationCalls.incrementAndGet();
        log.info("T4-BP-3: completion verification LLM call #{} this run ({} criteria, {} output files)",
                n, criteria.size(), output.size());
        if (!passMap.isEmpty()) verdictCache.put(digest, passMap);
        return passMap;
    }

    /** Stable digest of the criteria set + the output it is judged against. */
    private static String digest(List<String> criteria, Map<String, String> output) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (String c : criteria) md.update(("C:" + c + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> md.update(("F:" + e.getKey() + "=" + e.getValue() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            // No digest ⇒ treat as a unique state (cache miss). Correctness over cache efficiency.
            return java.util.UUID.randomUUID().toString();
        }
    }

    /** Completion-verification LLM calls so far this run. */
    public int completionVerificationCallCount() {
        return completionVerificationCalls.get();
    }

    /** Clears the per-run verdict cache and call counter (called on system reset). */
    public void resetCostMetrics() {
        verdictCache.clear();
        completionVerificationCalls.set(0);
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
