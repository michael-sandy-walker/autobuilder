package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec-grounding check for acceptance criteria (run 19, 2026-07-07). The loop repeatedly killed
 * near-working runs on requirements it AUTHORED itself: run 17 died on self-authored test files a
 * no-test spec never asked for; run 18 on consolidation-folded contradictory criteria; run 19 on
 * "bookId and memberId are strings" — a criterion minted from a transient compile diagnostic that
 * contradicts both the spec and the working code, which the evaluator then adopted as its
 * steering target. All three are one failure: transient observations fossilized into persistent
 * requirements with no check against the specification.
 *
 * <p>This service answers, per criterion, whether the root specification states or directly
 * implies it. Consumers stamp the result as the criterion's binding (SPEC vs AUTHORED); authored
 * criteria remain visible to the work loop as guidance but cannot block root closure, burn retry
 * budget, or join the completion-verification set (see GoalPlannerService).
 *
 * <p>Abstain-safe: an empty result (LLM failure, unparseable answer) means "no answer" and
 * callers must leave the criteria at the SPEC default — grounding never fails open into
 * advisory when the model is down.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "memo of a structural check against the current tree — a restored tree must re-derive "
                + "it")
public class CriteriaGroundingService {

    private static final Pattern ANSWER_LINE = Pattern.compile("(?m)^\\s*(\\d+)\\.\\s*([✓✗])");

    private static final String SYSTEM_PROMPT = """
            You check whether requirements are grounded in a project specification.

            A requirement is GROUNDED (✓) when the specification states it or directly implies it.
            A technical restatement of specified behaviour is grounded — e.g. a specified
            "GET /items/{id} returns 404 when absent" grounds "the item lookup distinguishes
            missing entities".

            A requirement is NOT grounded (✗) when the specification neither states nor implies
            it. Typical examples: internal type or naming choices the specification never mentions,
            constraints carried over from a past build error, exact response wording the
            specification does not demand, tests or tooling the specification never asks for.

            Judge each requirement independently, only against the specification text. Answer
            strictly in the requested format with no extra commentary.
            """;

    private final LLMClient llmClient;

    /** In-memory result cache keyed by (specification digest, criterion text). */
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    /**
     * Classifies each criterion against the specification. Returns a map of 1-based criterion
     * index to grounded (true = stated/implied by the spec). An empty map means NO ANSWER —
     * the caller must not treat it as "all authored" (abstain keeps the SPEC default).
     */
    public Map<Integer, Boolean> classifyGrounded(String specification, List<String> criteria) {
        if (specification == null || specification.isBlank()
                || criteria == null || criteria.isEmpty()) {
            return Map.of();
        }
        String specDigest = digest(specification);

        Map<Integer, Boolean> out = new LinkedHashMap<>();
        List<String> uncached = new ArrayList<>();
        List<Integer> uncachedIdx = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            Boolean hit = cache.get(specDigest + "|" + digest(criteria.get(i)));
            if (hit != null) {
                out.put(i + 1, hit);
            } else {
                uncached.add(criteria.get(i));
                uncachedIdx.add(i + 1);
            }
        }
        if (uncached.isEmpty()) return out;

        String response;
        try {
            List<Message> messages = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", buildPrompt(specification, uncached)));
            ResponseEntity<ChatResponse> r = llmClient.ask(messages);
            response = r.getBody() != null ? r.getBody().getFirstContent() : null;
        } catch (Exception e) {
            log.warn("criteria-grounding: LLM call failed ({}) — no answer, criteria keep the SPEC default", e.getMessage());
            return Map.of();
        }
        if (response == null || response.isBlank()) return Map.of();

        Map<Integer, Boolean> parsed = parse(response, uncached.size());
        if (parsed.isEmpty()) {
            log.warn("criteria-grounding: unparseable answer — criteria keep the SPEC default");
            return Map.of();
        }
        for (Map.Entry<Integer, Boolean> e : parsed.entrySet()) {
            int originalIdx = uncachedIdx.get(e.getKey() - 1);
            out.put(originalIdx, e.getValue());
            cache.put(specDigest + "|" + digest(criteria.get(originalIdx - 1)), e.getValue());
        }
        return out;
    }

    private static String buildPrompt(String specification, List<String> criteria) {
        StringBuilder sb = new StringBuilder();
        sb.append("SPECIFICATION:\n").append(specification.strip()).append("\n\nREQUIREMENTS:\n");
        for (int i = 0; i < criteria.size(); i++) {
            sb.append(i + 1).append(". ").append(criteria.get(i)).append("\n");
        }
        sb.append("""

                For EACH requirement, answer with exactly one line:
                <requirement number>. ✓ <requirement text>   (when the specification states or directly implies it)
                <requirement number>. ✗ <requirement text>   (when it does not)
                """);
        return sb.toString();
    }

    /** Parses {@code <n>. ✓/✗ …} lines; indices outside [1, criterionCount] are ignored. */
    Map<Integer, Boolean> parse(String raw, int criterionCount) {
        Map<Integer, Boolean> out = new LinkedHashMap<>();
        Matcher m = ANSWER_LINE.matcher(raw);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx < 1 || idx > criterionCount) continue;
            out.put(idx, "✓".equals(m.group(2)));
        }
        return out;
    }

    private static String digest(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
