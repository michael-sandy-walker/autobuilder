package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.EndpointPaths;

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
            return carryStatedInput(deterministic.get(), assertion, specification, artifactRoot);
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
        return viaModel.flatMap(seq -> carryStatedInput(seq, assertion, specification, artifactRoot));
    }

    /**
     * T2-FL: ground a query reference by CREATING what it points at, never by inventing an id.
     *
     * <p><b>Why only this direction.</b> The sibling case — grounding a pattern where the
     * requirement asserts the reference resolves to NOTHING — was built and then withdrawn on
     * measurement, because criteria asserting a 404 for an absent member state something the
     * specification never does, and probing them reports a correct artifact as defective. That
     * withdrawal stands. This grounds the opposite assertion: the resource EXISTS, so the setup
     * creates it and the probe asks about a reference that genuinely resolves.
     *
     * <p><b>The one convention this accepts, and its limit.</b> A reference parameter is matched
     * to a collection by removing a trailing id-suffix and comparing the stem to the collections
     * THE SPECIFICATION ITSELF DECLARES — so no domain name is written down here
     * ([[feedback-no-target-domain-in-framework-strings]]), and a project whose specification
     * declares no matching collection gets no probe. Exactly one collection must match: zero is
     * nothing to create, and two would make the choice a guess.
     *
     * <p>Abstains, leaving the caller's existing refusal in place, on: more than one query
     * component, a component that is not {@code name={var}}, an unmatched or ambiguous stem, and
     * a collection whose model yields no derivable body — the same condition that already stops
     * a path-variable setup being sent against a resource that was never created.
     */
    private static Optional<List<ProbeRequest>> groundQueryReference(
            List<ProbeRequest> sequence, BehaviouralCriterionParser.Assertion assertion,
            String component, String specification, Path artifactRoot) {
        if (specification == null || specification.isBlank()) return Optional.empty();

        // T3-CB: the status must be one the SPECIFICATION supports for this endpoint -- a rule
        // that names the seed, never a status. Where the seed states success statuses for the
        // endpoint, a criterion asserting another is not probed against a resource created to
        // satisfy it; where it states none, success follows from the endpoint being described.
        // Ported from the loop's own implementation (run 2026-09-26_00-42-19, the first run
        // whose goal was a ticket: criterion-status, 277/277).
        SeedStatusSupport.Verdict support = SeedStatusSupport.of(assertion.criterionText(), specification);
        if (support != SeedStatusSupport.Verdict.SUPPORTED) {
            log.info("T3-CB: [{}] asserts a status the specification does not support for its endpoint ({}) "
                    + "-- not grounded", assertion.describe(), support);
            return Optional.empty();
        }

        // — THE GATE THAT KEEPS THE WITHDRAWAL INTACT. The setup below CREATES the resource, so
        // it establishes that the reference resolves. A criterion asserting what happens when it
        // does NOT resolve has the opposite premise, and grounding it this way would assert a 404
        // against an application that correctly answers 200 for an existing member — precisely
        // the manufactured contradiction that got the absent-id grounding withdrawn. Only a
        // success status may be probed against a resource this sequence just created.
        if (assertion.expectedStatus() < 200 || assertion.expectedStatus() > 299) {
            return Optional.empty();
        }
        Matcher m = SINGLE_QUERY_REFERENCE.matcher(component.trim());
        if (!m.matches()) return Optional.empty();
        String parameter = m.group(1);

        Optional<String> collection = collectionDeclaredFor(parameter, specification);
        if (collection.isEmpty()) return Optional.empty();

        // T4-DX: the criterion's own token, so two grounded criteria against one running instance do
        // not create the same resource twice and collide on the specification's uniqueness rule.
        Optional<String> setupBody = DeterministicProbeSynthesizer.derivedBody(
                collection.get(), artifactRoot, assertion.criterionText());
        if (setupBody.isEmpty()) return Optional.empty();

        ProbeRequest asserted = sequence.get(sequence.size() - 1);
        if (asserted.path().contains("?")) return Optional.empty();  // already carries an input
        log.info("T2-FL: grounding {} by creating a resource in {} first",
                component, collection.get());
        return Optional.of(List.of(
                new ProbeRequest("POST", collection.get(), setupBody.get()),
                new ProbeRequest(asserted.method(),
                        asserted.path() + "?" + parameter + "=" + CREATED_REFERENCE,
                        asserted.body())));
    }

    /** {@code name={var}} and nothing else — two components make the subject ambiguous. */
    private static final Pattern SINGLE_QUERY_REFERENCE =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=\\{[^}]*}");

    /** The id of the resource the setup request created, in the sequence runner's own notation. */
    private static final String CREATED_REFERENCE = "$1.id";

    /** A trailing id-suffix, the only naming assumption this file makes. */
    private static final Pattern ID_SUFFIX = Pattern.compile("(?i)(_id|id)$");

    /**
     * The spec-declared collection a reference parameter points at, or empty when the stem
     * matches no declared collection or more than one.
     */
    private static Optional<String> collectionDeclaredFor(String parameter, String specification) {
        String stem = ID_SUFFIX.matcher(parameter).replaceFirst("");
        if (stem.isBlank()) return Optional.empty();
        String wanted = stem.toLowerCase(java.util.Locale.ROOT);

        java.util.Set<String> matches = new java.util.LinkedHashSet<>();
        for (var endpoint : new EndpointSurfaceQuery().declaredInSpec(specification)) {
            String path = endpoint.path();
            if (path.contains("{")) continue;                       // a collection, not a member
            String last = path.substring(path.lastIndexOf('/') + 1);
            String singular = last.endsWith("s") ? last.substring(0, last.length() - 1) : last;
            if (last.equals(wanted) || singular.equals(wanted)) matches.add(path);
        }
        return matches.size() == 1 ? Optional.of(matches.iterator().next()) : Optional.empty();
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
                                                                 BehaviouralCriterionParser.Assertion assertion,
                                                                 String specification,
                                                                 Path artifactRoot) {
        Optional<String> stated = BehaviouralCriterionParser.statedInput(assertion.criterionText());
        if (stated.isEmpty() || sequence.isEmpty()) return Optional.of(sequence);

        String component = stated.get();

        // T2-FL: when the requirement says the input is ABSENT, the exercise is the request
        // without it — which is what the sequence already is. Carrying the component back in
        // here would probe the opposite of what the criterion states, and the pattern refusal
        // below would discard the sequence entirely.
        // Gated on the same client-error status as the shape it protects, so the two cannot
        // disagree: without it, a success-status criterion saying "when memberId is missing" kept
        // a bare request here that `omittedInput` had deliberately refused to build — the same
        // exercise reached by a second route, which is how one gate silently stops meaning
        // anything ([[feedback-one-invariant-one-home]]).
        Optional<String> parameter = BehaviouralCriterionParser.statedParameterName(assertion.criterionText());
        if (parameter.isPresent()
                && assertion.expectedStatus() >= 400 && assertion.expectedStatus() <= 499
                && BehaviouralCriterionParser.statesAbsentInput(assertion.criterionText(), parameter.get())) {
            return Optional.of(sequence);
        }

        if (component.contains("{")) {
            // T2-FL: a pattern the probe CAN ground, by creating the resource it refers to and
            // using that id — the direction the withdrawal below deliberately left open. It is
            // the create-then-reference shape `rejectedInput` already performs for a PATH
            // variable; only the query-borne case was unreachable. Measured across 103 archived
            // trees: 94 distinct criteria state a query pattern, against 521 path ones that other
            // shapes already handle.
            Optional<List<ProbeRequest>> grounded =
                    groundQueryReference(sequence, assertion, component, specification, artifactRoot);
            if (grounded.isPresent()) return grounded;
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
            log.info("T1-FW: [{}] names the input {} as a pattern that cannot be grounded — "
                            + "abstaining rather than exercising a different input",
                    assertion.describe(), component);
            return Optional.empty();
        }
        List<ProbeRequest> out = new ArrayList<>(sequence);
        ProbeRequest last = out.get(out.size() - 1);
        if (last.path().contains("?")) return Optional.of(sequence);   // already carries it
        out.set(out.size() - 1, new ProbeRequest(last.method(), last.path() + "?" + component, last.body()));
        return Optional.of(List.copyOf(out));
    }

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
            ResponseEntity<ChatResponse> r;
            try (var llmPurpose = com.walpro.autobuilder.client.LlmCallContext.scopeIfUnscoped("probe-synthesis")) {
                r = llmClient.ask(messages);
            }
            response = r.getBody() != null ? r.getBody().getFirstContent() : null;
        } catch (Exception e) {
            log.warn("T4-CU: probe synthesis failed for [{}] ({}) — criterion not probed",
                    assertion.describe(), e.getMessage());
            return Optional.empty();
        }
        if (response == null || response.isBlank()) return Optional.empty();

        List<ProbeRequest> parsed = parse(response, assertion);
        if (parsed.isEmpty()) {
            // parse() has already said WHICH refusal this was, naming the criterion. Falling
            // through to the endpoint check would report every one of them as a drifted answer.
            cache.put(key, List.of());
            return Optional.empty();
        }
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
     *
     * <p><b>The refusal NAMES the criterion, 2026-09-08.</b> Read back over the post-fix corpus,
     * the abstention was the only refusal in this class that did not say what it was about, so a
     * log could show fifteen honest abstentions without saying which fifteen criteria went
     * unverified — the census line was the only way to recover it. Worse, an empty sequence then
     * fell through to the endpoint check and was reported as an answer that "does not end on the
     * asserted endpoint", which is a different failure and not the one that happened. Both
     * refusals now state their own reason against the criterion they refused.
     */
    List<ProbeRequest> parse(String raw, BehaviouralCriterionParser.Assertion assertion) {
        String subject = assertion == null ? "an unnamed criterion" : assertion.describe();
        List<ProbeRequest> out = new ArrayList<>();
        Matcher m = REQUEST_LINE.matcher(raw);
        while (m.find()) {
            String path = EndpointPaths.normalizePath(m.group(2));
            if (path == null) continue;
            String body = m.group(3) == null ? "" : m.group(3).strip();
            out.add(new ProbeRequest(m.group(1).toUpperCase(),
                    path,
                    body.isEmpty() || "-".equals(body) ? null : body));
            if (out.size() > MAX_REQUESTS) {
                log.info("T1-FU: the sequence for [{}] needs more than {} requests — abstaining "
                        + "rather than truncating, because a shortened setup tests a different situation",
                        subject, MAX_REQUESTS);
                return List.of();
            }
        }
        if (out.isEmpty()) {
            log.info("T4-CU: the synthesized answer for [{}] contained no request line — not probed",
                    subject);
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
