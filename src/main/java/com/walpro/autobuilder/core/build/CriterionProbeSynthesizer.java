package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-CU: turns one acceptance criterion into the ordered HTTP requests that would exercise it.
 *
 * <p><b>The division of labour is the point.</b> Today the model is asked to judge behaviour it
 * cannot observe — "does this source return 400 for invalid input?" — and it answers from the
 * shape of the code. It answered {@code ✓} on an artifact with no validation at all
 * (run {@code 2026-07-27_16-41-48}). Here it is asked instead for an <em>input</em>: "what would
 * you send to try this?". Producing a JSON body with a missing field is a generation task the
 * local model does well, and — decisively — nothing it returns is taken as a verdict. The running
 * artifact answers; this class only supplies the question.
 *
 * <p>A sequence rather than a single request, because criteria are routinely stateful: "POST
 * /members with duplicate email returns HTTP 409" is only reachable after a first member exists.
 * The LAST request in the sequence is the asserted one; everything before it is setup.
 *
 * <p><b>Abstain-safe, and guarded against drift.</b> An empty result means "no probe" and the
 * criterion keeps whatever status it already had — a synthesis failure must never read as a failed
 * criterion. The returned sequence is rejected outright when its final request does not match the
 * verb and path the criterion asserts: a model that wandered onto a different endpoint would
 * otherwise produce a confident verdict about something the criterion never claimed.
 *
 * <p><b>T4-CY, 2026-07-29 — the model is no longer asked first, and by default not at all.</b>
 * Asking it worked and found real defects, but a synchronous call to the same local model the loop
 * is using cost 29.5 and 14.9 minutes of one 90-minute run, because {@code LLMClient} escalates its
 * read timeout across retries. {@link DeterministicProbeSynthesizer} constructs the shapes the
 * specifications actually state, from the artifact's own sources, in milliseconds. The model path
 * survives behind {@code autobuilder.build.criterion-probe.llm-fallback-enabled} (default
 * {@code false}) for the shapes the deterministic path abstains on — it is affordable at the
 * completion edge, and not inside a cycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "memo of synthesised probes, re-derivable from the criterion")
public class CriterionProbeSynthesizer {

    /** One request in a probe sequence. {@code body} is null for methods sent without one. */
    public record ProbeRequest(String method, String path, String body) {
        @Override
        public String toString() {
            return method + " " + path + (body == null || body.isBlank() ? "" : " " + body);
        }
    }

    /** Upper bound on sequence length — a probe needing more setup than this is not worth trusting. */
    static final int MAX_REQUESTS = 6;

    private static final Pattern REQUEST_LINE = Pattern.compile(
            "(?m)^\\s*\\d+[.)]\\s*(GET|POST|PUT|PATCH|DELETE)\\s+(\\S+)[ \\t]*(.*)$");

    private static final String SYSTEM_PROMPT = """
            You turn a single acceptance criterion into the HTTP requests that would exercise it
            against a running application.

            Answer with a numbered list of requests, one per line, in the order they must be sent:
                1. <METHOD> <path> <json body or - when the request has no body>

            The LAST line must be the request the criterion is about. Earlier lines exist only to
            create the state that request needs — for example, a criterion about a duplicate value
            needs the first value to have been created already.

            Rules:
            - Use only paths the criterion and specification mention.
            - Keep bodies on ONE line, valid JSON, no trailing commentary.
            - Choose field values that actually produce the situation the criterion describes: to
              exercise a missing required field, leave that field out; to exceed a stated limit,
              go past it.
            - When the path contains an id:
              * if the criterion is about something that does NOT exist, use a large number that
                was never created, e.g. /items/999999;
              * if it is about something that MUST exist, create it in an earlier request and
                refer to the value from that response as $N.field, e.g. request 1 creates it and
                request 2 is GET /items/$1.id. Never invent an id for something that must exist.
            - Emit nothing but the numbered lines.
            """;

    private final LLMClient llmClient;

    /**
     * Whether a criterion the deterministic path cannot construct may fall back to a model call.
     * OFF by default: the call is synchronous, competes with the loop's own use of the same local
     * model, and can block for minutes when its read timeout escalates across retries.
     */
    @Value("${autobuilder.build.criterion-probe.llm-fallback-enabled:false}")
    private boolean llmFallbackEnabled = false;

    /** Cache keyed by (specification digest, criterion text digest). */
    private final Map<String, List<ProbeRequest>> cache = new ConcurrentHashMap<>();

    /**
     * The request sequence that exercises {@code assertion}, or empty when no trustworthy sequence
     * could be obtained. Empty means "do not probe this criterion".
     *
     * <p>{@code artifactRoot} is the tree whose model sources supply request bodies for the
     * deterministic path, which is tried first and answers every shape the specifications state.
     * The model is consulted only for what is left over, and only when the fallback is enabled.
     */
    public Optional<List<ProbeRequest>> synthesize(String specification,
                                                   BehaviouralCriterionParser.Assertion assertion,
                                                   Path artifactRoot) {
        return synthesize(specification, assertion, artifactRoot, false);
    }

    /**
     * T4-DB: as above, but the caller may permit the model fallback for THIS call regardless of
     * the global flag.
     *
     * <p>T4-CY switched the fallback off after measuring 29.5 and 14.9 minutes of a 90-minute run
     * consumed by synchronous calls to the same local model the loop was using — and recorded the
     * condition under which it is still worth having: <i>"affordable at the completion edge, and
     * not inside a cycle."</i> That distinction was expressible all along (the completion edge is
     * one of four call sites) and simply was not expressed, so the capability stayed globally off.
     *
     * <p>Measured 2026-08-04, on a healthy artifact roughly 15 of 18 remaining abstentions share
     * one cause: no request body the application will accept. {@code derivedBody} fills
     * placeholders from field names, and a correct application declines them. The model is the
     * only source of a body that satisfies constraints stated in prose.
     */
    public Optional<List<ProbeRequest>> synthesize(String specification,
                                                   BehaviouralCriterionParser.Assertion assertion,
                                                   Path artifactRoot,
                                                   boolean allowModelFallback) {
        if (assertion == null) return Optional.empty();

        Optional<List<ProbeRequest>> deterministic =
                DeterministicProbeSynthesizer.synthesize(assertion, artifactRoot);
        if (deterministic.isPresent()) {
            log.debug("T4-CY: probe for [{}] synthesized deterministically as {}",
                    assertion.describe(), deterministic.get());
            return carryStatedInput(deterministic.get(), assertion);
        }
        if (!llmFallbackEnabled && !allowModelFallback) {
            log.debug("T4-CY: no deterministic probe for [{}] and the model fallback is off — not probed",
                    assertion.describe());
            return Optional.empty();
        }
        // T4-DB: at INFO, because this is the expensive path and a mechanism whose firing cannot
        // be seen cannot be judged. Shipped without it on 2026-08-04 and the first run could not
        // answer whether the edge fallback had run at all. Once per unprobed criterion at the
        // completion edge, so the volume is bounded by the thing it is reporting.
        long t0 = System.currentTimeMillis();
        Optional<List<ProbeRequest>> viaModel = synthesizeWithModel(specification, assertion);
        log.info("T4-DB: model fallback for [{}] -> {} ({} ms)", assertion.describe(),
                viaModel.map(seq -> seq.size() + " request(s)").orElse("no usable probe"),
                System.currentTimeMillis() - t0);
        return viaModel.flatMap(seq -> carryStatedInput(seq, assertion));
    }

    /**
     * T1-FW: put back the input the requirement named and identity normalization dropped.
     *
     * <p>Applied HERE, at the single entry point and AFTER {@code isUsable} has already accepted
     * the sequence, so identity comparison never sees the component and neither
     * {@link #isUsable} nor {@link #pathMatches} changes. The ticket expected this to force a
     * second field on the assertion and a change to the comparison; reading the order of
     * operations showed the cheaper form — carry it on the EXERCISE, not on the identity.
     *
     * <p>Only the request the criterion is ABOUT gets it. Earlier requests are setup, and their
     * job is to create state, not to exercise the requirement.
     */
    private static Optional<List<ProbeRequest>> carryStatedInput(List<ProbeRequest> sequence,
                                                                 BehaviouralCriterionParser.Assertion assertion) {
        Optional<String> stated = BehaviouralCriterionParser.statedInput(assertion.criterionText());
        if (stated.isEmpty() || sequence.isEmpty()) return Optional.of(sequence);

        String component = stated.get();
        if (component.contains("{")) {
            // A PATTERN, and it is NOT probed whatever status it asserts.
            //
            // Grounding it where the requirement asserts the reference RESOLVES needs a resource
            // created first, and knowing which collection to create it in means reading the
            // handler's own parameter declaration — the capability T1-FV names and this project
            // does not have.
            //
            // ⚠ Grounding it where the requirement asserts the reference resolves to NOTHING looks
            // safe and was built, then withdrawn on measurement. Of the archive's 37 pattern
            // criteria, 10 assert 404 for an absent member — and THE SPECIFICATION NEVER SAYS
            // THAT: it states only "list all loans for a member", while the 404 belongs to a
            // different endpoint. The artifact answers 200 with an empty array and is right to;
            // one of those criteria even hedges, "returns HTTP 404 or empty array (per spec: 404
            // if member not found)". Probing them would report a correct artifact as defective on
            // the strength of a criterion the loop invented — the very failure this ticket is
            // about. The disagreement is real and worth surfacing, but as a criterion that does
            // not follow from the seed (T4-DA), never as an artifact defect.
            log.debug("T1-FW: [{}] names an input pattern that cannot be grounded — not probed",
                    assertion.describe());
            return Optional.empty();
        }
        List<ProbeRequest> out = new ArrayList<>(sequence);
        ProbeRequest last = out.get(out.size() - 1);
        if (last.path().contains("?")) return Optional.of(sequence);   // already carries it
        out.set(out.size() - 1, new ProbeRequest(last.method(), last.path() + "?" + component, last.body()));
        return Optional.of(List.copyOf(out));
    }

    /** The status a requirement uses to say a named reference resolves to nothing. */
    private static final int ABSENT_REFERENCE_STATUS = 404;

    /** An id no resource is created with, matching the value the path-variable builder uses. */
    private static final String UNUSED_REFERENCE = "999999999";

    /**
     * The model-driven path: asks for an input, never a verdict, and rejects any answer whose final
     * request is not the one the criterion asserts. Reached only for shapes
     * {@link DeterministicProbeSynthesizer} abstains on, and only when the fallback is enabled —
     * {@link #synthesize} is the entry point everything in production goes through.
     */
    Optional<List<ProbeRequest>> synthesizeWithModel(String specification,
                                                     BehaviouralCriterionParser.Assertion assertion) {
        if (assertion == null) return Optional.empty();
        String key = digest(specification == null ? "" : specification) + "|" + digest(assertion.criterionText());
        List<ProbeRequest> cached = cache.get(key);
        if (cached != null) return cached.isEmpty() ? Optional.empty() : Optional.of(cached);

        String response;
        try {
            List<Message> messages = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", buildPrompt(specification, assertion)));
            ResponseEntity<ChatResponse> r = llmClient.ask(messages);
            response = r.getBody() != null ? r.getBody().getFirstContent() : null;
        } catch (Exception e) {
            log.warn("T4-CU: probe synthesis failed for [{}] ({}) — criterion not probed",
                    assertion.describe(), e.getMessage());
            return Optional.empty();
        }
        if (response == null || response.isBlank()) return Optional.empty();

        List<ProbeRequest> parsed = parse(response);
        if (!isUsable(parsed, assertion)) {
            log.info("T4-CU: synthesized probe for [{}] does not end on the asserted endpoint — not probed",
                    assertion.describe());
            cache.put(key, List.of());
            return Optional.empty();
        }
        cache.put(key, parsed);
        return Optional.of(parsed);
    }

    private static String buildPrompt(String specification, BehaviouralCriterionParser.Assertion a) {
        StringBuilder sb = new StringBuilder();
        if (specification != null && !specification.isBlank()) {
            sb.append("SPECIFICATION:\n").append(specification.strip()).append("\n\n");
        }
        sb.append("CRITERION:\n").append(a.criterionText()).append("\n\n");
        sb.append("The final request must be: ").append(a.verb()).append(' ').append(a.path()).append('\n');
        return sb.toString();
    }

    /**
     * Numbered request lines, in order. A body of {@code -} (or absent) becomes null.
     *
     * <p><b>T1-FU: an answer longer than the budget is REFUSED, never cut down.</b> This loop used
     * to stop at {@link #MAX_REQUESTS} and return the prefix, which turns a correct answer into a
     * different experiment. Measured on run 2026-08-24_22-14-05: for "a member may hold at most 3
     * active loans — 422 if exceeded" the model answered with nine requests — four DIFFERENT books,
     * one member, four loans — which is exactly right. Truncation kept the first six, so the three
     * loans that ESTABLISH the precondition were dropped, and the probe then asked a member holding
     * ONE loan to be refused a second. The application correctly answered 201 and was recorded as
     * contradicting the specification, which blocked root closure 9 to 34 times per run across four
     * runs and three builds — the loop reaching a correct artifact and being told it had not.
     *
     * <p>Nothing downstream can detect the damage: {@code isUsable} only checks that the sequence
     * ENDS on the asserted verb and path, and a truncated tail still does; {@code SETUP_REFUSED}
     * only fires when a setup request is REJECTED, and these all legitimately succeed.
     *
     * <p>Abstaining costs one criterion. Raising the cap instead would trade a false contradiction
     * for an unbounded probe budget, which is the thing the cap exists to prevent.
     */
    List<ProbeRequest> parse(String raw) {
        List<ProbeRequest> out = new ArrayList<>();
        Matcher m = REQUEST_LINE.matcher(raw);
        while (m.find()) {
            String path = EndpointSurfaceQuery.normalizePath(m.group(2));
            if (path == null) continue;
            String body = m.group(3) == null ? "" : m.group(3).strip();
            out.add(new ProbeRequest(m.group(1).toUpperCase(),
                    path,
                    body.isEmpty() || "-".equals(body) ? null : body));
            if (out.size() > MAX_REQUESTS) {
                log.info("T1-FU: the synthesized sequence needs more than {} requests — abstaining "
                        + "rather than truncating, because a shortened setup tests a different situation",
                        MAX_REQUESTS);
                return List.of();
            }
        }
        return out;
    }

    /**
     * A sequence is usable only when it ends on the verb and path the criterion asserts. This is
     * the guard against a drifted answer producing a verdict about a different endpoint.
     *
     * <p>The criterion's path may be a PATTERN ({@code /books/{}}), so the final request's path is
     * matched segment-wise with {@code {}} as a single-segment wildcard: {@code /books/999999}
     * matches, {@code /members/1} does not, and neither does {@code /books/1/reviews}. Comparing
     * the literal strings would reject every grounded path-variable probe.
     */
    static boolean isUsable(List<ProbeRequest> requests, BehaviouralCriterionParser.Assertion assertion) {
        if (requests == null || requests.isEmpty()) return false;
        ProbeRequest last = requests.get(requests.size() - 1);
        return assertion.verb().equals(last.method()) && pathMatches(assertion.path(), last.path());
    }

    /** Segment-wise match of a concrete path against a pattern whose {@code {}} are wildcards. */
    static boolean pathMatches(String pattern, String concrete) {
        if (pattern == null || concrete == null) return false;
        if (pattern.equals(concrete)) return true;
        String[] p = pattern.split("/", -1);
        String[] c = concrete.split("/", -1);
        if (p.length != c.length) return false;
        for (int i = 0; i < p.length; i++) {
            if ("{}".equals(p[i])) {
                if (c[i].isEmpty()) return false; // a wildcard must stand for something
                continue;
            }
            if (!p[i].equals(c[i])) return false;
        }
        return true;
    }

    public void setLlmFallbackEnabled(boolean llmFallbackEnabled) {
        this.llmFallbackEnabled = llmFallbackEnabled;
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
