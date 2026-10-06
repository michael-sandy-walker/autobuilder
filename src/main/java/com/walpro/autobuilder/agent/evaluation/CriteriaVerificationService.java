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
    /** T6-T: "N. ? needs <path>[, <path>]" -- the criterion cannot be judged without a file that was only referenced. */
    private static final Pattern NEEDS_LINE = Pattern.compile("(?m)^\\s*(\\d+)\\.\\s*\\?\\s*needs?:?\\s+(.+)$");
    /** T6-T: "N. <path>, <path>" or "N. none" -- the locating pass's answer. */
    private static final Pattern LOCATE_LINE = Pattern.compile("(?m)^\\s*(\\d+)\\.\\s*(.+)$");
    /** T6-T: rounds of the decentralized verification -- the first, and the ones answering "? needs". */
    static final int MAX_ROUNDS = 3;

    /**
     * T6-T: the deployed context, so the file budget is a share of what the model can read rather than a constant
     * that outgrew it (120,000 characters against a 32k-token window: the 2026-10-06 closure prompt was 128,000).
     * Optional; absent, the configured budget stands.
     */
    @Setter
    @Autowired(required = false)
    private com.walpro.autobuilder.protocol.PromptTokenBudget promptTokenBudget;

    /** The characters the files may take, after {@code otherChars} of prompt around them. */
    int fileBudget(int otherChars) {
        int budget = fileBudgetChars > 0 ? fileBudgetChars : Integer.MAX_VALUE;
        if (promptTokenBudget != null) {
            long fromContext = (long) promptTokenBudget.promptHeadroomTokens() * 4 - otherChars - SYSTEM_PROMPT.length();
            budget = (int) Math.max(0, Math.min(budget, fromContext));
        }
        return budget;
    }

    private static final String SYSTEM_PROMPT = """
            You verify whether acceptance criteria are satisfied by the current state of a codebase.
            Assess only what the shown files actually implement. Answer strictly in the requested format with no extra commentary.

            BEHAVIOURAL GRADING RULE: when a requirement names internal structure (a specific
            method, field, class, or helper name), grade the observable behaviour that requirement
            implies, NOT the literal identifier. The implementation may choose any internal shape that
            produces the behaviour. Example: "ServiceX.findItemsByOwner() correctly retrieves items"
            is satisfied by ANY code path that retrieves items for an owner, regardless of method name.
            Grade ✗ only when the behaviour itself is absent from the shown files.

            TEST RUN RULE: when a PROJECT TEST RUN section is present, it is an OBSERVATION of the
            project's own tests executing on exactly these files, not a claim. Judge any requirement
            about tests running or passing from it, never by predicting outcomes from the source.
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
        // A claim with an authority other than a reading is answered by it, here as at the completion
        // edge: receiver-questions run 2026-09-26_22-44-36 walled a subgoal on three claims that nothing
        // was changed, which a model reading the source cannot confirm and the adoption manifest can.
        passMap = new java.util.LinkedHashMap<>(passMap);
        String goalText = (context.currentGoalDescription() == null ? "" : context.currentGoalDescription())
                + " " + (description == null ? "" : description);
        for (int i = 1; i <= criteria.size(); i++) {
            Boolean recorded = recordAnswer(criteria.get(i - 1), goalText);
            if (recorded != null) passMap.put(i, recorded);
        }

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
            // T6-T: the tree does not fit -- verify it in pieces that do, the rest referenced by outline.
            log.info("T6-T: the files do not fit one verification prompt ({}) -- verifying per criterion, the rest"
                    + " of the tree referenced", evidence.describeGaps());
            return verifyDecentralized(subjectDescription, features, outputFiles, structuralNotes);
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
     * T6-T: verification of a tree too large for one prompt (the user's direction, 2026-10-06: "split it up and
     * process it in such a way that it can fit the context window while the other part of the tree gets only
     * referenced").
     *
     * <p>Each criterion has HOME files: the files whose class it names, those a locating pass over the outlines
     * points to when it names none, and those it asks for. Criteria are packed into calls whose home files fit
     * whole; every other file is shown only as its outline (path and declarations). A criterion is answered
     * ✓, ✗, or "? needs &lt;path&gt;", and a "needs" answer is asked again with that file whole, for at most
     * {@link #MAX_ROUNDS} rounds.
     *
     * <p>T2-FI's rule holds per call: a ✓ counts only from a call that showed its files whole; a ✗ counts from
     * any call (the asymmetry: a wrong block costs cycles, a wrong pass a false completion). The evidence is
     * complete when every criterion has a counted verdict.
     */
    private Assessment verifyDecentralized(String subject, List<String> features, Map<String, String> files,
                                           String structuralNotes) {
        Map<Integer, java.util.LinkedHashSet<String>> home = new LinkedHashMap<>();
        List<Integer> unhomed = new ArrayList<>();
        for (int i = 1; i <= features.size(); i++) {
            java.util.LinkedHashSet<String> named = namedFiles(features.get(i - 1), files);
            home.put(i, named);
            if (named.isEmpty()) unhomed.add(i);
        }
        if (!unhomed.isEmpty()) locate(subject, features, unhomed, files, home);
        for (int i : unhomed) {
            if (home.get(i).isEmpty()) home.get(i).addAll(files.keySet()); // nothing located: the whole tree, as before
        }

        Map<Integer, Boolean> counted = new LinkedHashMap<>();
        List<String> gaps = new ArrayList<>();
        List<Integer> pending = new ArrayList<>(home.keySet());
        for (int round = 0; round < MAX_ROUNDS && !pending.isEmpty(); round++) {
            List<Integer> again = new ArrayList<>();
            for (List<Integer> batch : pack(pending, home, files, structuralNotes)) {
                java.util.LinkedHashSet<String> whole = new java.util.LinkedHashSet<>();
                for (int i : batch) whole.addAll(home.get(i));
                Evidence ev = buildBatchPrompt(subject, features, batch, files, whole, structuralNotes);
                String response = ask(ev.prompt());
                if (response == null) continue;
                Map<Integer, Boolean> verdicts = parse(response, batch.size());
                Map<Integer, List<String>> needs = parseNeeds(response, batch.size(), files);
                for (int k = 1; k <= batch.size(); k++) {
                    int criterion = batch.get(k - 1);
                    Boolean v = verdicts.get(k);
                    if (Boolean.FALSE.equals(v) || (Boolean.TRUE.equals(v) && ev.complete())) {
                        counted.put(criterion, v);
                    } else if (needs.containsKey(k) && home.get(criterion).addAll(needs.get(k))) {
                        again.add(criterion);
                    } else if (Boolean.TRUE.equals(v)) {
                        gaps.add("criterion " + criterion + " passed on cropped files (" + ev.describeGaps() + ")");
                    }
                }
            }
            pending = again;
        }
        List<Integer> unanswered = new ArrayList<>();
        for (int i : home.keySet()) if (!counted.containsKey(i)) unanswered.add(i);
        if (!unanswered.isEmpty()) gaps.add("no counted verdict for criteria " + unanswered);
        log.info("T6-T: decentralized verification -- {} of {} criteria answered on whole files{}", counted.size(),
                features.size(), gaps.isEmpty() ? "" : " (" + String.join("; ", gaps) + ")");
        return new Assessment(counted, unanswered.isEmpty(), String.join("; ", gaps));
    }

    /** The files whose class a criterion names, as a word -- main sources before tests. */
    static java.util.LinkedHashSet<String> namedFiles(String criterion, Map<String, String> files) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (criterion == null) return out;
        List<String> paths = new ArrayList<>(files.keySet());
        paths.sort(java.util.Comparator.comparing((String p) -> p.replace('\\', '/').contains("/src/test/")
                || p.replace('\\', '/').startsWith("src/test/")));
        for (String path : paths) {
            String p = path.replace('\\', '/');
            if (!p.endsWith(".java")) continue;
            String simple = p.substring(p.lastIndexOf('/') + 1, p.length() - ".java".length());
            if (Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(criterion).find()) out.add(path);
        }
        return out;
    }

    /** Criteria into calls whose home files fit whole beside the outline of the rest; a criterion too large goes alone. */
    private List<List<Integer>> pack(List<Integer> criteria, Map<Integer, java.util.LinkedHashSet<String>> home,
                                     Map<String, String> files, String structuralNotes) {
        int notes = structuralNotes == null ? 0 : structuralNotes.length();
        int budget = fileBudget(notes + 2_000);
        List<List<Integer>> batches = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        java.util.Set<String> shown = new java.util.HashSet<>();
        int used = 0;
        for (int c : criteria) {
            int add = 0;
            for (String f : home.get(c)) if (!shown.contains(f)) add += files.getOrDefault(f, "").length();
            int outline = outlineChars(files, shown, home.get(c));
            if (!current.isEmpty() && used + add + outline > budget) {
                batches.add(current);
                current = new ArrayList<>();
                shown = new java.util.HashSet<>();
                used = 0;
                add = 0;
                for (String f : home.get(c)) add += files.getOrDefault(f, "").length();
            }
            current.add(c);
            shown.addAll(home.get(c));
            used += add;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    private static int outlineChars(Map<String, String> files, java.util.Set<String> shown, java.util.Set<String> adding) {
        int n = 0;
        for (String f : files.keySet()) if (!shown.contains(f) && !adding.contains(f)) n += f.length() + 60;
        return n;
    }

    /** One call of the decentralized verification: its criteria (numbered 1..k), its files whole, the rest outlined. */
    private Evidence buildBatchPrompt(String subject, List<String> features, List<Integer> batch, Map<String, String> files,
                                      java.util.Set<String> whole, String structuralNotes) {
        StringBuilder sb = new StringBuilder("GOAL:\n").append(subject != null ? subject : "(none)").append("\n\nREQUIREMENTS:\n");
        for (int k = 0; k < batch.size(); k++) sb.append(k + 1).append(". ").append(features.get(batch.get(k) - 1)).append("\n");
        if (structuralNotes != null && !structuralNotes.isBlank()) sb.append("\n").append(structuralNotes.strip()).append("\n");
        StringBuilder outline = new StringBuilder("\nREFERENCED, NOT SHOWN (the rest of the project: each file's declarations only):\n");
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (whole.contains(e.getKey())) continue;
            List<String> decls = com.walpro.autobuilder.analysis.structure.DefaultProjectStructureSummarizer.summarizeContent(e.getValue());
            outline.append("  ").append(e.getKey()).append(decls.isEmpty() ? "" : " -- " + String.join("; ",
                    decls.subList(0, Math.min(12, decls.size()))).replaceAll("\\s+", " ")).append("\n");
        }
        int budget = fileBudget(sb.length() + outline.length() + 800);
        if (outline.length() > budget / 4) {         // a large tree: its paths only, so the files keep the room
            outline = new StringBuilder("\nREFERENCED, NOT SHOWN (the rest of the project):\n");
            for (String f : files.keySet()) if (!whole.contains(f)) outline.append("  ").append(f).append("\n");
            budget = fileBudget(sb.length() + outline.length() + 800);
        }
        sb.append("\nSHOWN WHOLE (the files these requirements are about):\n");
        List<String> cropped = new ArrayList<>();
        int remaining = budget;
        for (String f : whole) {
            String content = files.getOrDefault(f, "");
            sb.append("=== ").append(f).append(" ===\n");
            if (content.length() <= remaining) {
                sb.append(content).append("\n");
                remaining -= content.length();
            } else {
                sb.append(content, 0, Math.max(0, remaining)).append("\n... (")
                        .append(content.length() - Math.max(0, remaining)).append(" characters truncated)\n");
                cropped.add(f);
                remaining = 0;
            }
        }
        sb.append(outline);
        sb.append("""

                For EACH requirement, answer with exactly one line:
                <requirement number>. ✓ <requirement text>   (when the shown files satisfy it)
                <requirement number>. ✗ <requirement text>   (when they do not)
                <requirement number>. ? needs <path>         (when you cannot judge it without a file listed under REFERENCED)
                """);
        return new Evidence(sb.toString(), List.copyOf(cropped), 0);
    }

    /** The locating pass: from the outlines alone, which files each unhomed criterion is about. */
    private void locate(String subject, List<String> features, List<Integer> unhomed, Map<String, String> files,
                        Map<Integer, java.util.LinkedHashSet<String>> home) {
        StringBuilder sb = new StringBuilder("GOAL:\n").append(subject != null ? subject : "(none)").append("\n\nREQUIREMENTS:\n");
        for (int k = 0; k < unhomed.size(); k++) sb.append(k + 1).append(". ").append(features.get(unhomed.get(k) - 1)).append("\n");
        sb.append("\nPROJECT FILES (declarations only):\n");
        for (Map.Entry<String, String> e : files.entrySet()) {
            List<String> decls = com.walpro.autobuilder.analysis.structure.DefaultProjectStructureSummarizer.summarizeContent(e.getValue());
            sb.append("  ").append(e.getKey()).append(decls.isEmpty() ? "" : " -- " + String.join("; ",
                    decls.subList(0, Math.min(12, decls.size()))).replaceAll("\\s+", " ")).append("\n");
        }
        sb.append("""

                For EACH requirement, answer with exactly one line naming the files (paths exactly as listed, at most 4)
                that implement it or are needed to judge it:
                <requirement number>. <path>, <path>
                or
                <requirement number>. none
                """);
        String response = ask(sb.toString());
        if (response == null) return;
        Matcher m = LOCATE_LINE.matcher(response);
        while (m.find()) {
            int k = Integer.parseInt(m.group(1));
            if (k < 1 || k > unhomed.size()) continue;
            for (String part : m.group(2).split(",")) {
                String path = part.strip().replaceAll("^`|`$", "");
                if (files.containsKey(path)) home.get(unhomed.get(k - 1)).add(path);
            }
        }
    }

    /** "N. ? needs a, b" lines, keeping only paths that are files of the tree. */
    static Map<Integer, List<String>> parseNeeds(String raw, int count, Map<String, String> files) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        Matcher m = NEEDS_LINE.matcher(raw);
        while (m.find()) {
            int k = Integer.parseInt(m.group(1));
            if (k < 1 || k > count) continue;
            List<String> paths = new ArrayList<>();
            for (String part : m.group(2).split(",")) {
                String path = part.strip().replaceAll("^`|`$", "");
                if (files.containsKey(path)) paths.add(path);
            }
            if (!paths.isEmpty()) out.put(k, paths);
        }
        return out;
    }

    /** One verification call; null on failure or an empty answer. */
    private String ask(String prompt) {
        try {
            List<Message> messages = List.of(new Message("system", SYSTEM_PROMPT), new Message("user", prompt));
            ResponseEntity<ChatResponse> r;
            try (var llmPurpose = com.walpro.autobuilder.client.LlmCallContext.scopeIfUnscoped("criteria-verification")) {
                r = llmClient.ask(messages);
            }
            String response = r.getBody() != null ? r.getBody().getFirstContent() : null;
            return response == null || response.isBlank() ? null : response;
        } catch (Exception e) {
            log.warn("T6-T: a verification call failed ({}) -- no observation from it", e.getMessage());
            return null;
        }
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
        // T4-CQ: the project's own test run, when it describes exactly this tree. Part of the cache
        // key: a verdict formed without it must not answer for a prompt that has it.
        String testRun = testRunEvidence(outputFiles);
        String codeDigest = digestOf(outputFiles) + (testRun == null ? "" : ":tests=" + Integer.toHexString(testRun.hashCode()));

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

        // T6-T: record first -- a claim the test run of this tree or the changes since adoption answer is never
        // put to a model at all. Before, every criterion went to the reading and the record overrode it after;
        // a proven "every test in X passes" still sat in a prompt that could not fit, and its cropping deferred
        // a closure the record had already settled (run 2026-10-06_11-50-45).
        Map<String, Boolean> recordedByCriterion = new java.util.HashMap<>();
        java.util.Set<String> testClaimsWithoutRun = new java.util.HashSet<>();
        boolean testRunAvailable = testRunGate == null || !testRunGate.isLive() || testRunGate.freshReport().isPresent();
        for (String c : requiredCriteria) {
            Boolean recorded = recordAnswer(c, rootDescription);
            if (recorded != null) recordedByCriterion.put(c, recorded);
            // T6-T: a test claim is answered by the test run or not at all. With the gate live and no report of
            // this tree (the run hung, or never ran), a reading would only predict -- the archive replay of
            // 2026-10-06 saw a model confirm "ParameterizedCaseInputTest passes" on a tree whose suite hangs.
            else if (!testRunAvailable && com.walpro.autobuilder.core.build.TestRunClaims.isClaim(c)) testClaimsWithoutRun.add(c);
        }
        // Verify only the criteria with no cached result at this code state, and no record answering them.
        List<String> uncached = new ArrayList<>();
        for (String c : requiredCriteria) {
            if (!cachedByKey.containsKey(CriterionResultEntity.keyOf(c)) && !recordedByCriterion.containsKey(c)
                    && !testClaimsWithoutRun.contains(c)) uncached.add(c);
        }
        Map<String, Boolean> freshByCriterion = new java.util.HashMap<>();
        boolean evidenceComplete = true;
        String evidenceGaps = "";
        if (uncached.isEmpty()) {
            log.info("T4-BP-3: completion verification answered by the cache and the record -- no LLM call ({} criteria)",
                    requiredCriteria.size());
        } else {
            // T4-BS: give the verifier concrete wiring facts for THIS code state alongside the files.
            String structuralNotes = wiringFactsAnalyzer != null
                    ? wiringFactsAnalyzer.asPromptBlock(outputFiles) : null;
            if (testRun != null) {
                structuralNotes = structuralNotes == null || structuralNotes.isBlank()
                        ? testRun : structuralNotes.strip() + "\n\n" + testRun;
            }
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
                Boolean v = passMap.get(i + 1);
                if (v != null || evidenceComplete) freshByCriterion.put(uncached.get(i), Boolean.TRUE.equals(v));
            }
        }

        // Combine cached + fresh; false ⇒ unconfirmed. Absent ⇒ unconfirmed when the evidence was whole (the model
        // omitted it), and unanswered when it was not (T6-T: a criterion the decentralized pass could not settle on
        // whole files defers the closure rather than raising a work item about code nobody judged).
        List<String> unconfirmed = new ArrayList<>();
        List<String> unanswered = new ArrayList<>();
        for (String c : requiredCriteria) {
            Boolean ok = cachedByKey.containsKey(CriterionResultEntity.keyOf(c))
                    ? cachedByKey.get(CriterionResultEntity.keyOf(c))
                    : freshByCriterion.get(c);
            Boolean recorded = recordedByCriterion.get(c);
            if (recorded != null) ok = recorded;
            if (ok == null && (!evidenceComplete || testClaimsWithoutRun.contains(c))) unanswered.add(c);
            else if (ok == null || !ok) unconfirmed.add(c);
        }
        if (unconfirmed.isEmpty() && !unanswered.isEmpty()) {
            log.warn("T4-BP-3 / T2-FI: {} criterion(s) got no verdict on whole files or from a test run ({}) -- deferring"
                    + " this closure attempt", unanswered.size(), testClaimsWithoutRun.isEmpty() ? evidenceGaps
                    : "no test run of this tree for " + testClaimsWithoutRun);
            return CompletionVerification.abstained();
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

    /** T4-CQ: optional -- absent, the verifier reads source only, as before. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.build.TestRunGate testRunGate;

    /** T4-DK: what changed since adoption, for claims that something was not changed; optional. */
    @Autowired(required = false)
    private com.walpro.autobuilder.core.build.AdoptionManifest adoptionManifest;

    /**
     * One home for the claims a reading is not the authority for -- whole claims only: that tests pass (the
     * test run of exactly this tree, {@code TestRunClaims}) and that nothing was changed (the changes since
     * adoption, {@code UnchangedClaims}). The user's decision 2026-10-02, "record first": the record's answer
     * stands over a reading's EITHER way -- before, it could only rescue a "not met", so a reading's wrong
     * "met" on "No file under src/test is changed" was never checked (receiver-questions, field21: the
     * record was consulted 0 times in three runs). TRUE proven, FALSE contradicted, null no record answers.
     */
    Boolean recordAnswer(String criterion, String goalText) {
        var report = testRunGate == null ? java.util.Optional.<com.walpro.autobuilder.core.build.TestRunReport>empty()
                : testRunGate.freshReport();
        if (report.isPresent()) {
            Boolean tests = com.walpro.autobuilder.core.build.TestRunClaims.answer(criterion, report.get());
            if (tests != null) {
                log.info("T4-CQ: [{}] {} by the project's test run of this tree ({} run, {} failed), not by a reading",
                        criterion, tests ? "confirmed" : "contradicted", report.get().run(),
                        report.get().failures() + report.get().errors());
                return tests;
            }
        }
        var changes = adoptionManifest == null ? java.util.Optional.<com.walpro.autobuilder.core.build.AdoptionManifest.Changes>empty()
                : adoptionManifest.changes();
        if (changes.isPresent()) {
            Boolean unchanged = com.walpro.autobuilder.core.build.UnchangedClaims.answer(criterion, changes.get(), goalText);
            if (unchanged != null) {
                log.info("T4-DK: [{}] {} by the changes since adoption ({} modified, {} added, {} removed), not by a reading",
                        criterion, unchanged ? "confirmed" : "contradicted", changes.get().modified().size(),
                        changes.get().added().size(), changes.get().removed().size());
                return unchanged;
            }
        }
        return null;
    }

    /**
     * The test-run block for the files this prompt will show, or null when there is no report of
     * this exact tree. Its budget is a tenth of the prompt's file budget, so the block scales with
     * the prompt rather than with the suite.
     */
    private String testRunEvidence(Map<String, String> outputFiles) {
        if (testRunGate == null) return null;
        var report = testRunGate.freshReport();
        if (report.isEmpty()) return null;
        List<String> shown = outputFiles.keySet().stream().limit(MAX_FILES).toList();
        int budget = fileBudgetChars > 0 ? fileBudgetChars / 10 : 8_000;
        return com.walpro.autobuilder.core.build.TestRunPromptBlock.render(report.get(), shown, budget);
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
        int remaining = fileBudget(sb.length() + 400);
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
