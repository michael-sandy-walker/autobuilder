package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;
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
import org.springframework.beans.factory.annotation.Value;
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
 * zero per-criterion observation in the graph (observed: the run that promoted {@code goal-f6d38fbe}
 * recorded no Criteria Progress at all). This service runs one focused LLM pass — acceptance
 * criteria against the <i>current</i> output files — and persists the results as
 * {@code CriterionResult} nodes (with the T4-BP-2 stable {@code criterionKey}), so the graph
 * accumulates pass/fail observations for subgoals that never merge.
 *
 * <p>Originally recording-only; since run 18 (2026-07-06) the #NoDiff route also GATES on the
 * result — a fresh unmet criterion refuses the promotion (see AutobuilderService), because a
 * recording that arrives after the promotion decision leaves a closed-but-unmet contradiction
 * the closure edge can only give up on.
 *
 * <p>Best-effort by contract: empty result on missing criteria, missing output, LLM failure, or
 * unparseable response — callers must treat empty as "no observation", never as "failed".
 */
@Slf4j
@Service
@RequiredArgsConstructor
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "the field held here is a per-run cost counter; the criterion results themselves go "
                + "to the graph")
public class CriteriaVerificationService {

    static final int MAX_FILES = 25;

    /**
     * T2-FI: a budget for the whole FILES block, not a per-file line count.
     *
     * <p>The cap it replaces was {@code MAX_LINES_PER_FILE = 150}, and it manufactured a false
     * completion. Run {@code 2026-09-11_16-27-02} asked whether the tool prints the malformed row's
     * physical line; {@code Main.java} is 215 lines and {@code countPhysicalLines} — the method that
     * computes the number — is at 200, so the model saw the CALL SITE and never the body. It filled
     * the gap from the requirement text and answered met. Proven by ablation, same model, same
     * prompt, temperature 0, 5 samples each way: cropped it says {@code line 2} (the specification's
     * value), whole it says {@code line 1} (what the artifact really prints).
     *
     * <p><b>Why a character budget and why this default.</b> A per-file line cap crops the largest
     * file, which is the one a behavioural criterion is usually about, while leaving small files
     * whole — it spends the allowance in exactly the wrong order. The default is set against
     * measured facts rather than taste: the four targets' entire Java sources are 8.3k, 15.8k, 8.1k
     * and 7.3k characters, so this is roughly eight times the largest project the loop has ever
     * evolved, and about 30k tokens against the configured model's measured 262,144-token context.
     * Truncation should therefore be rare — which is what makes refusing to close on it safe rather
     * than a wall ([[feedback-relative-thresholds]]).
     */
    @Value("${autobuilder.verification.file-budget-chars:120000}")
    @Setter
    private int fileBudgetChars = 120_000;

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
     * T4-BS: structural wiring facts for the prompt — optional so the coverage consumer and the
     * lightweight tests run without it. When present, {@link #verifyCompletion} AND the per-subgoal
     * {@link #verifyAndRecord} give the checker concrete wiring facts (stub bodies, never-assigned
     * fields, missing persistence, throw-vs-mapping) instead of presence-matching over a flat blob.
     */
    @Setter
    @Autowired(required = false)
    private WiringFactsAnalyzer wiringFactsAnalyzer;

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

        // T4-BS (run 18, 2026-07-06): without the facts this consumer judged status-code
        // criteria from raw files and marked behaviour that exists as unmet ("422 when limit
        // exceeded" ✗ while its strictly-stronger sibling passed in the same answer) — the
        // banked ✗ then walled the subgoal and the root gave up on finished work. The
        // completion gate fixed this exact class by supplying wiring facts; same facts here.
        String structuralNotes = wiringFactsAnalyzer != null
                ? wiringFactsAnalyzer.asPromptBlock(output) : null;
        Assessment assessment = verifyFeatures(description, criteria, output, structuralNotes);
        // T2-FI: same rule as the completion edge, for the same reason one level down. These
        // verdicts are BANKED — recorded to the graph as this subgoal's per-criterion truth — so a
        // pass formed on a cropped file would outlive the prompt that produced it and be read later
        // as a verified observation. Empty is this method's contract for "nothing observed".
        if (!assessment.evidenceComplete()) {
            log.warn("T2-FI: not banking criteria for {} — the prompt did not carry all the evidence ({})",
                    subGoalId, assessment.gaps());
            return Map.of();
        }
        Map<Integer, Boolean> passMap = assessment.passMap();
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
     * on LLM failure or unparseable response — callers must treat empty as "no observation",
     * never as "failed". Consumers: the #NoDiff route above (T4-BP-1) and the T2-EA root
     * coverage gate (filters description-level gap candidates against actual code before
     * injecting subgoals).
     */
    public Map<Integer, Boolean> verifyFeatures(String subjectDescription, List<String> features,
                                                Map<String, String> outputFiles) {
        Assessment a = verifyFeatures(subjectDescription, features, outputFiles, null);
        // T2-FI: a verdict on cropped evidence is not an observation. Empty is this method's own
        // contract for "nothing observed", and every caller already treats it that way, so an
        // incomplete view degrades to silence instead of arriving as a confident answer.
        return a.evidenceComplete() ? a.passMap() : Map.of();
    }

    /**
     * T4-BS: as {@link #verifyFeatures(String, List, Map)} but with an optional structural-wiring
     * notes block appended to the prompt (the completion gate supplies it; other consumers pass null).
     */
    private Assessment verifyFeatures(String subjectDescription, List<String> features,
                                                Map<String, String> outputFiles, String structuralNotes) {
        if (features == null || features.isEmpty()
                || outputFiles == null || outputFiles.isEmpty()) {
            return Assessment.none();
        }
        Evidence evidence = buildPrompt(subjectDescription, features, outputFiles, structuralNotes);
        if (!evidence.complete()) {
            log.warn("T2-FI: the verification prompt does not carry all the evidence — {}. "
                    + "Verdicts formed on it cannot confirm a criterion.", evidence.describeGaps());
        }
        String response;
        try {
            List<Message> messages = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", evidence.prompt()));
            ResponseEntity<ChatResponse> r;
            try (var llmPurpose = com.walpro.autobuilder.client.LlmCallContext.scopeIfUnscoped("criteria-verification")) {
                r = llmClient.ask(messages);
            }
            response = r.getBody() != null ? r.getBody().getFirstContent() : null;
        } catch (Exception e) {
            log.warn("T4-BP-1: verification LLM call failed ({}) — no observation recorded", e.getMessage());
            return Assessment.none();
        }
        if (response == null || response.isBlank()) return Assessment.none();

        Map<Integer, Boolean> passMap = parse(response, features.size());
        if (passMap.isEmpty()) {
            log.warn("T4-BP-1: unparseable verification response — no observation recorded");
        }
        return new Assessment(passMap, evidence.complete(), evidence.describeGaps());
    }

    /**
     * T2-FI: verdicts, plus whether the evidence they were formed on was whole.
     *
     * <p>Kept together deliberately. The two facts are only useful in combination — a pass means one
     * thing about a complete view of the code and something quite different about a cropped one, and
     * separating them is how the second fact went missing for as long as it did.
     */
    private record Assessment(Map<Integer, Boolean> passMap, boolean evidenceComplete, String gaps) {
        static Assessment none() {
            return new Assessment(Map.of(), true, "");
        }
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
     * <p>Cost (T4-BR): graph-backed per-criterion cache. Each criterion's result is stored as a
     * {@code CriterionResult} keyed by {@code (criterionKey, codeDigest)}, so a criterion judged
     * before at this exact code state is not re-verified — only the uncached criteria reach the LLM,
     * in one focused pass. Durable + inspectable (vs the former in-memory map) and the substrate for
     * incremental verification. Conservative on no observation — empty criteria, empty output, or total
     * LLM failure return empty (never block a run on a transient hiccup); only a parsed ✗ (or a
     * criterion the model omits) counts as unconfirmed.
     */
    /**
     * Tri-state completion-verification result: {@code answered=false} means the LLM gave
     * no usable answer — the completion edge must treat that as "defer this closure attempt",
     * never as an all-clear (2026-07-02 false completion #2: an unanswered check one gate
     * earlier collapsed into a pass and the root completed without one).
     */
    public record CompletionVerification(boolean answered, Optional<String> unconfirmed,
                                         List<String> unconfirmedCriteria) {
        public static CompletionVerification abstained() { return new CompletionVerification(false, Optional.empty(), List.of()); }
        public static CompletionVerification allClear() { return new CompletionVerification(true, Optional.empty(), List.of()); }
        public static CompletionVerification blocked(String summary, List<String> unconfirmedCriteria) {
            return new CompletionVerification(true, Optional.of(summary), List.copyOf(unconfirmedCriteria));
        }
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

        // T4-BR: consult the graph for results already recorded at this exact code state.
        Map<String, Boolean> cachedByKey;
        try {
            Set<String> keys = new LinkedHashSet<>();
            for (String c : requiredCriteria) keys.add(CriterionResultEntity.keyOf(c));
            Map<String, Boolean> found = agentGraphService.findCriterionResults(keys, codeDigest);
            cachedByKey = found != null ? found : Map.of();
        } catch (Exception e) {
            log.debug("T4-BR: result lookup failed ({}) — verifying all criteria", e.getMessage());
            cachedByKey = Map.of();
        }

        // Verify only the criteria with no cached result at this code state.
        List<String> uncached = new ArrayList<>();
        for (String c : requiredCriteria) {
            if (!cachedByKey.containsKey(CriterionResultEntity.keyOf(c))) uncached.add(c);
        }
        Map<String, Boolean> freshByCriterion = new java.util.HashMap<>();
        boolean evidenceComplete = true;
        String evidenceGaps = "";
        if (uncached.isEmpty()) {
            log.info("T4-BP-3: completion verification fully cache-hit at this code state — no LLM call ({} criteria)",
                    requiredCriteria.size());
        } else {
            // T4-BS: give the verifier concrete wiring facts for THIS code state alongside the files.
            String structuralNotes = wiringFactsAnalyzer != null
                    ? wiringFactsAnalyzer.asPromptBlock(outputFiles) : null;
            Assessment assessment = verifyFeatures(rootDescription, uncached, outputFiles, structuralNotes);
            Map<Integer, Boolean> passMap = assessment.passMap();
            evidenceComplete = assessment.evidenceComplete();
            evidenceGaps = assessment.gaps();
            int n = completionVerificationCalls.incrementAndGet();
            log.info("T4-BP-3: completion verification LLM call #{} this run ({} verified, {} cached, {} output files, evidence={})",
                    n, uncached.size(), requiredCriteria.size() - uncached.size(), outputFiles.size(),
                    evidenceComplete ? "complete" : "INCOMPLETE");
            if (passMap.isEmpty()) {
                // No answer (LLM failure / unparseable). Previously an all-clear ("no observation
                // is never treated as failure") — after false completion #2 the completion edge
                // distinguishes abstain from pass: the caller defers the closure attempt.
                return CompletionVerification.abstained();
            }
            // T2-FI: a verdict formed on cropped evidence must not become the cache's answer for
            // this code state. Caching it would let one incomplete prompt speak for every later
            // closure at the same digest, long after the file that was cut has been forgotten.
            if (evidenceComplete) {
                recordCompletionResults(uncached, passMap, codeDigest);
            } else {
                log.warn("T2-FI: not caching {} completion verdict(s) — they were formed on incomplete evidence ({})",
                        passMap.size(), evidenceGaps);
            }
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
        if (unconfirmed.isEmpty()) {
            // T2-FI: THE ASYMMETRY IS THE WHOLE POINT, and it is deliberate rather than uniform.
            //
            // An all-clear on cropped evidence closes the root, and closing on a requirement nobody
            // checked is the worst outcome this system has — it produced the 2026-09-11 false
            // completion, where the method computing the value sat in the 66 lines the prompt cut.
            // So an all-clear demands a complete view and otherwise defers, which is a delay.
            //
            // A BLOCKED verdict is left alone. Its cost is the loop working a criterion that may
            // already be met — wasted cycles, recoverable. Refusing both directions equally would
            // trade a rare severe failure for a frequent mild one, and the frequent one is what
            // walls a run ([[project-completion-edge-gates]]: absence of evidence is not a pass,
            // and it is not a failure either).
            if (!evidenceComplete) {
                log.warn("T4-BP-3 / T2-FI: every criterion came back confirmed, but the prompt did "
                        + "not carry all the evidence ({}) — deferring this closure attempt rather "
                        + "than completing on an unexamined requirement", evidenceGaps);
                return CompletionVerification.abstained();
            }
            return CompletionVerification.allClear();
        }
        String head = unconfirmed.stream().limit(5).reduce((a, b) -> a + "; " + b).orElse("");
        String tail = unconfirmed.size() > 5 ? " …and " + (unconfirmed.size() - 5) + " more" : "";
        return CompletionVerification.blocked(
                unconfirmed.size() + " acceptance criterion(s) not confirmed in the current code: " + head + tail,
                unconfirmed);
    }

    /** T4-BR: persist the freshly-verified results as the graph-backed cache (keyed criterionKey+digest). */
    private void recordCompletionResults(List<String> criteria, Map<Integer, Boolean> passMap, String codeDigest) {
        List<CriterionResultEntity> results = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            Boolean p = passMap.get(i + 1);
            if (p == null) continue; // model omitted this line — leave uncached so it is re-verified
            results.add(new CriterionResultEntity(
                    "completion-verify", i + 1, criteria.get(i), p, "completion-edge", null, codeDigest));
        }
        if (results.isEmpty()) return;
        try {
            agentGraphService.recordCompletionResults(results);
        } catch (Exception e) {
            log.warn("T4-BR: persisting completion results failed: {}", e.getMessage());
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

    /** Resets the per-run call counter (called on system reset). The result cache is graph-backed (T4-BR). */
    public void resetCostMetrics() {
        completionVerificationCalls.set(0);
    }

    private Evidence buildPrompt(String subjectDescription, List<String> criteria, Map<String, String> output,
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
        int omitted = 0;
        int remaining = fileBudgetChars > 0 ? fileBudgetChars : Integer.MAX_VALUE;
        List<String> cropped = new ArrayList<>();
        for (Map.Entry<String, String> e : output.entrySet()) {
            if (files++ >= MAX_FILES) {
                omitted = output.size() - MAX_FILES;
                sb.append("... (").append(omitted).append(" more files omitted)\n");
                break;
            }
            String content = e.getValue() != null ? e.getValue() : "";
            sb.append("=== ").append(e.getKey()).append(" ===\n");
            if (content.length() <= remaining) {
                sb.append(content).append("\n");
                remaining -= content.length();
            } else {
                // Spend what is left rather than dropping the file outright: a partial view is
                // still worth something to a criterion about another part of it. What it must NOT
                // do is pass silently, which is what `cropped` exists to prevent.
                sb.append(content, 0, Math.max(0, remaining))
                        .append("\n... (").append(content.length() - Math.max(0, remaining))
                        .append(" characters truncated)\n");
                cropped.add(e.getKey());
                remaining = 0;
            }
        }
        sb.append("""

                For EACH requirement, answer with exactly one line:
                <requirement number>. ✓ <requirement text>   (when the current files satisfy it)
                <requirement number>. ✗ <requirement text>   (when they do not)
                """);
        return new Evidence(sb.toString(), List.copyOf(cropped), omitted);
    }

    /**
     * T2-FI: the prompt, and what it could not fit.
     *
     * <p>The gaps travel WITH the prompt because the caller is the only party that can act on them.
     * Before this the service knew it had cropped a file — it wrote "… (N lines truncated)" into the
     * text — and told nobody, so a verdict formed on cropped evidence was indistinguishable from one
     * formed on all of it.
     */
    private record Evidence(String prompt, List<String> croppedFiles, int omittedFiles) {
        boolean complete() {
            return croppedFiles.isEmpty() && omittedFiles == 0;
        }

        String describeGaps() {
            List<String> parts = new ArrayList<>();
            if (!croppedFiles.isEmpty()) parts.add("cropped " + String.join(", ", croppedFiles));
            if (omittedFiles > 0) parts.add(omittedFiles + " file(s) omitted entirely");
            return String.join("; ", parts);
        }
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
