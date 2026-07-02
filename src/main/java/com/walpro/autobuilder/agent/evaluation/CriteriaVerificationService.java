package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.analysis.structure.WiringEvidenceAnalyzer;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.evaluation.CriterionResultEntity;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * T4-BS: structural wiring evidence for the completion gate's prompt — optional so the #NoDiff /
     * coverage consumers and the lightweight tests run without it. When present, {@link #verifyCompletion}
     * gives the verifier concrete wiring facts (stub bodies, never-assigned fields, missing persistence,
     * unmapped throws) instead of presence-matching over a flat blob.
     */
    @Setter
    @Autowired(required = false)
    private WiringEvidenceAnalyzer wiringEvidenceAnalyzer;

    /** T4-BP step 3: cumulative completion-verification LLM calls — surfaced so cost is measured, not guessed. */
    private final java.util.concurrent.atomic.AtomicInteger completionVerificationCalls = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Verifies the current subgoal's acceptance criteria against the current output files,
     * persists the per-criterion results to the graph, and returns the pass map
     * (criterion index → passed). Empty map when there is nothing to verify or on failure.
     */
    public Map<Integer, Boolean> verifyAndRecord(CycleContextData context, String subGoalId) {
        if (context == null) return Map.of();
        return verifyAndRecord(context, subGoalId,
                context.currentSubGoalCriteria(), context.currentSubGoalDescription());
    }

    /**
     * T2-EH: as {@link #verifyAndRecord(CycleContextData, String)} but with the criteria and
     * description supplied explicitly, so the convergence-stall verify-before-pause can verify
     * <i>every</i> active leaf subgoal against the current output — not only
     * {@code context.currentSubGoalId}. The shared project output is read from the context; only
     * the per-subgoal criteria/description differ.
     */
    public Map<Integer, Boolean> verifyAndRecord(CycleContextData context, String subGoalId,
                                                 List<String> criteria, String description) {
        if (context == null || subGoalId == null) return Map.of();
        if (criteria == null || criteria.isEmpty()) {
            // Criteria-less subgoal: the description itself is the only requirement statement
            // there is — verify it as a single feature. Without this, criteria-less goals can
            // neither be rescued at exhaustion nor confirmed on a completion belief (observed
            // 2026-06-11 22:55: "Create Member entity class" — Member.java on disk, no criteria,
            // and the completion belief had nothing to check against).
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

        Map<Integer, Boolean> passMap = verifyFeatures(description, criteria, output);
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
        return verifyFeatures(subjectDescription, features, outputFiles, null);
    }

    /**
     * T4-BS: as {@link #verifyFeatures(String, List, Map)} but with an optional structural-wiring
     * notes block appended to the prompt (the completion gate supplies it; other consumers pass null).
     */
    private Map<Integer, Boolean> verifyFeatures(String subjectDescription, List<String> features,
                                                Map<String, String> outputFiles, String structuralNotes) {
        if (features == null || features.isEmpty()
                || outputFiles == null || outputFiles.isEmpty()) {
            return Map.of();
        }
        String response;
        try {
            List<Message> messages = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", buildPrompt(subjectDescription, features, outputFiles, structuralNotes)));
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
     * <p>Cost (T4-BR): graph-backed per-criterion cache. Each criterion's verdict is stored as a
     * {@code CriterionResult} keyed by {@code (criterionKey, codeDigest)}, so a criterion judged
     * before at this exact code state is not re-verified — only the uncached criteria reach the LLM,
     * in one focused pass. Durable + inspectable (vs the former in-memory map) and the substrate for
     * incremental verification. Conservative on no evidence — empty criteria, empty output, or total
     * LLM failure return empty (never block a run on a transient hiccup); only a parsed ✗ (or a
     * criterion the model omits) counts as unconfirmed.
     */
    /**
     * Tri-state completion-verification result: {@code verdictObtained=false} means the LLM gave
     * no usable answer — the completion edge must treat that as "defer this closure attempt",
     * never as an all-clear (2026-07-02 false completion #2: an abstain one gate earlier
     * collapsed into a pass and the root completed on absent evidence).
     */
    public record CompletionVerification(boolean verdictObtained, Optional<String> unconfirmed) {
        public static CompletionVerification abstained() { return new CompletionVerification(false, Optional.empty()); }
        public static CompletionVerification allClear() { return new CompletionVerification(true, Optional.empty()); }
        public static CompletionVerification blocked(String summary) { return new CompletionVerification(true, Optional.of(summary)); }
    }

    public CompletionVerification verifyCompletion(String rootDescription, List<String> requiredCriteria,
                                                   Map<String, String> outputFiles) {
        if (requiredCriteria == null || requiredCriteria.isEmpty()
                || outputFiles == null || outputFiles.isEmpty()) {
            // Nothing to verify — a vacuous all-clear, not an abstain (preserves the
            // long-standing behaviour for criteria-less / output-less closures).
            return CompletionVerification.allClear();
        }
        String codeDigest = digestOf(outputFiles);

        // T4-BR: consult the graph for verdicts already recorded at this exact code state.
        Map<String, Boolean> cachedByKey;
        try {
            Set<String> keys = new LinkedHashSet<>();
            for (String c : requiredCriteria) keys.add(CriterionResultEntity.keyOf(c));
            Map<String, Boolean> found = agentGraphService.findCriterionVerdicts(keys, codeDigest);
            cachedByKey = found != null ? found : Map.of();
        } catch (Exception e) {
            log.debug("T4-BR: verdict lookup failed ({}) — verifying all criteria", e.getMessage());
            cachedByKey = Map.of();
        }

        // Verify only the criteria with no cached verdict at this code state.
        List<String> uncached = new ArrayList<>();
        for (String c : requiredCriteria) {
            if (!cachedByKey.containsKey(CriterionResultEntity.keyOf(c))) uncached.add(c);
        }
        Map<String, Boolean> freshByCriterion = new java.util.HashMap<>();
        if (uncached.isEmpty()) {
            log.info("T4-BP-3: completion verification fully cache-hit at this code state — no LLM call ({} criteria)",
                    requiredCriteria.size());
        } else {
            // T4-BS: give the verifier concrete wiring facts for THIS code state alongside the files.
            String structuralNotes = wiringEvidenceAnalyzer != null
                    ? wiringEvidenceAnalyzer.asPromptBlock(outputFiles) : null;
            Map<Integer, Boolean> passMap = verifyFeatures(rootDescription, uncached, outputFiles, structuralNotes);
            int n = completionVerificationCalls.incrementAndGet();
            log.info("T4-BP-3: completion verification LLM call #{} this run ({} verified, {} cached, {} output files)",
                    n, uncached.size(), requiredCriteria.size() - uncached.size(), outputFiles.size());
            if (passMap.isEmpty()) {
                // No verdict (LLM failure / unparseable). Previously an all-clear ("no evidence
                // is never treated as failure") — after false completion #2 the completion edge
                // distinguishes abstain from pass: the caller defers the closure attempt.
                return CompletionVerification.abstained();
            }
            recordCompletionVerdicts(uncached, passMap, codeDigest);
            for (int i = 0; i < uncached.size(); i++) {
                freshByCriterion.put(uncached.get(i), Boolean.TRUE.equals(passMap.get(i + 1)));
            }
        }

        // Combine cached + fresh; absent or false ⇒ unconfirmed.
        List<String> unconfirmed = new ArrayList<>();
        for (String c : requiredCriteria) {
            Boolean ok = cachedByKey.containsKey(CriterionResultEntity.keyOf(c))
                    ? cachedByKey.get(CriterionResultEntity.keyOf(c))
                    : freshByCriterion.get(c);
            if (ok == null || !ok) unconfirmed.add(c);
        }
        if (unconfirmed.isEmpty()) return CompletionVerification.allClear();
        String head = unconfirmed.stream().limit(5).reduce((a, b) -> a + "; " + b).orElse("");
        String tail = unconfirmed.size() > 5 ? " …and " + (unconfirmed.size() - 5) + " more" : "";
        return CompletionVerification.blocked(
                unconfirmed.size() + " acceptance criterion(s) not confirmed in the current code: " + head + tail);
    }

    /** T4-BR: persist the freshly-verified verdicts as the graph-backed cache (keyed criterionKey+digest). */
    private void recordCompletionVerdicts(List<String> criteria, Map<Integer, Boolean> passMap, String codeDigest) {
        List<CriterionResultEntity> verdicts = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            Boolean p = passMap.get(i + 1);
            if (p == null) continue; // model omitted this line — leave uncached so it is re-verified
            verdicts.add(new CriterionResultEntity(
                    "completion-verify", i + 1, criteria.get(i), p, "completion-edge", null, codeDigest));
        }
        if (verdicts.isEmpty()) return;
        try {
            agentGraphService.recordCompletionVerdicts(verdicts);
        } catch (Exception e) {
            log.warn("T4-BR: persisting completion verdicts failed: {}", e.getMessage());
        }
    }

    /** Stable digest of the output the criteria are judged against (the code state). */
    private static String digestOf(Map<String, String> output) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            output.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> md.update(("F:" + e.getKey() + "=" + e.getValue() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return java.util.UUID.randomUUID().toString(); // unique ⇒ cache miss; correctness over efficiency
        }
    }

    /** Completion-verification LLM calls so far this run. */
    public int completionVerificationCallCount() {
        return completionVerificationCalls.get();
    }

    /** Resets the per-run call counter (called on system reset). The verdict cache is graph-backed (T4-BR). */
    public void resetCostMetrics() {
        completionVerificationCalls.set(0);
    }

    private String buildPrompt(String subjectDescription, List<String> criteria, Map<String, String> output,
                               String structuralNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("GOAL:\n").append(
                subjectDescription != null ? subjectDescription : "(none)");
        sb.append("\n\nREQUIREMENTS:\n");
        for (int i = 0; i < criteria.size(); i++) {
            sb.append(i + 1).append(". ").append(criteria.get(i)).append("\n");
        }
        if (structuralNotes != null && !structuralNotes.isBlank()) {
            sb.append("\n").append(structuralNotes.strip()).append("\n");
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
