package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * T4-CY: builds a criterion's probe sequence WITHOUT a model call.
 *
 * <p><b>Why this exists.</b> The behavioural self-check worked — run {@code 2026-07-28_23-16-15}
 * observed the running artifact twice and found two real defects. It had to be switched off the
 * next day because it cost <b>29.5 and 14.9 minutes</b> of a 90-minute run, and neither
 * shortening the requirement list nor capping it touched the cause: the time sat in probe
 * SYNTHESIS, which called the same local model the loop itself was using, synchronously inside a
 * cycle, and {@link com.walpro.autobuilder.client.LLMClient} escalates its read timeout across
 * retries. A mid-run observation cannot afford a call that can block for minutes.
 *
 * <p>Most of what the specifications actually state needs no model. Every response status written
 * into this project's spec is an error status, and three of the four correspond to an input
 * derivable from the artifact's own sources: an empty object violates any required-field
 * constraint ({@code 400}), an id that was never created is absent ({@code 404}), and a valid body
 * sent twice collides with itself ({@code 409}). Bodies come from {@link RequestBodySynthesizer},
 * which reads the artifact's own model fields. The whole path costs milliseconds and cannot time
 * out; measured over the live specification it renders <b>8 of 11</b> stated requirements
 * executable, where every one of the eleven previously cost a synthesis call.
 *
 * <p><b>Abstain-heavy, like everything else on this chain.</b> Empty means "no probe" and the
 * criterion keeps whatever status it had. A shape this class cannot construct honestly is left
 * alone rather than approximated — a false contradiction costs the loop real cycles chasing a
 * defect that is not there.
 *
 * <p><b>The one thing it does not attempt: success statuses.</b> A {@code 2xx} probe needs field
 * values that satisfy constraints stated in prose (a 13-digit code, a bounded length), and
 * placeholder values derived from field TYPES do not satisfy them — such a probe would report a
 * correct artifact as broken. Success statuses are also the ones no specification here states, so
 * abstaining costs nothing measurable today. If that changes, the honest answer is grounded values,
 * not a looser guess.
 *
 * <p><b>The second thing it does not attempt: a business rule whose precondition the criterion does
 * not state.</b> Measured over the live specification, an empty-body probe on
 * {@code POST /loans → 422 (a member may hold at most 3 active loans)} would be refused as invalid
 * input long before any limit was reached, and reporting that {@code 400} as a missing {@code 422}
 * is a false contradiction — the harm class that had two steering changes reverted in July. Reaching
 * that state needs four valid loans and a limit stated only in prose, so it abstains. Only
 * {@code 400} is treated as "the input itself is refused"; the specification says so in as many
 * words, and a specification that assigns a different code to malformed input loses a probe rather
 * than gaining a wrong one.
 */
public final class DeterministicProbeSynthesizer {

    /**
     * The id used for an absent resource. Any value works provided nothing creates it, and ids are
     * server-assigned from a fresh store on every boot, so a value far above the count of requests
     * a probe sequence makes is provably unused.
     */
    static final String UNUSED_ID = "999999999";

    /** Resolved by the executor against the first response — see {@code BootGate.resolveReferences}. */
    private static final String CREATED_ID = "$1.id";

    /** The empty object: the minimal body that is valid JSON and satisfies no required field. */
    private static final String EMPTY_BODY = "{}";

    private DeterministicProbeSynthesizer() {
    }

    /**
     * The request sequence that exercises {@code assertion}, or empty when no sequence can be built
     * without guessing. {@code artifactRoot} is the tree whose model sources supply request bodies.
     */
    public static Optional<List<ProbeRequest>> synthesize(BehaviouralCriterionParser.Assertion assertion,
                                                         Path artifactRoot) {
        if (assertion == null) return Optional.empty();
        // More than one variable would need more than one resource created in a known order, and
        // the criterion does not say which collection each belongs to. Left unprobed.
        if (variableCount(assertion.path()) > 1) return Optional.empty();
        return switch (assertion.expectedStatus()) {
            case 400 -> rejectedInput(assertion, artifactRoot);
            case 404 -> absentResource(assertion);
            case 409 -> collision(assertion, artifactRoot);
            default -> Optional.empty();
        };
    }

    /**
     * Input the application must refuse: send the asserted request with an empty body, which
     * satisfies no required field whatever those fields are.
     *
     * <p>When the path is parameterized the resource is created first, so the refusal is
     * attributable to the body rather than to a missing resource — an application that checks
     * existence before validating would otherwise answer 404 and read as a contradiction. That
     * setup needs a body good enough to succeed, so a criterion whose collection has no derivable
     * model is left unprobed instead of being probed against a resource that was never created.
     */
    private static Optional<List<ProbeRequest>> rejectedInput(BehaviouralCriterionParser.Assertion a,
                                                              Path artifactRoot) {
        if (!carriesBody(a.verb())) return Optional.empty(); // nothing to make invalid
        if (!isParameterized(a.path())) {
            return Optional.of(List.of(new ProbeRequest(a.verb(), a.path(), EMPTY_BODY)));
        }
        String collection = collectionPath(a.path());
        Optional<String> setupBody = derivedBody(collection, artifactRoot);
        if (setupBody.isEmpty()) return Optional.empty();
        return Optional.of(List.of(
                new ProbeRequest("POST", collection, setupBody.get()),
                new ProbeRequest(a.verb(), ground(a.path(), CREATED_ID), EMPTY_BODY)));
    }

    /**
     * A resource that does not exist: address it by an id nothing created. One request, no setup —
     * the most reliable shape available, and the one that produced the observed
     * {@code GET /members/{} → 500 where the spec wants 404}.
     *
     * <p>A non-parameterized path is not probed: "this collection answers 404" is a claim about a
     * route rather than about a resource, and the request that would test it is not derivable from
     * the criterion.
     *
     * <p><b>Known precision trade.</b> For a verb that carries a body the empty object is sent,
     * because whether the endpoint wants a body cannot be read off the criterion. An endpoint that
     * validates the body before resolving the path would answer 400 and be reported wrongly. The
     * alternative — abstaining on every body-carrying verb — would put a spec-stated requirement
     * permanently out of reach, which reads as "verified" in any readout; that failure mode is the
     * one {@link BehaviouralCriterionParser} was widened to avoid.
     */
    private static Optional<List<ProbeRequest>> absentResource(BehaviouralCriterionParser.Assertion a) {
        if (!isParameterized(a.path())) return Optional.empty();
        String body = carriesBody(a.verb()) ? EMPTY_BODY : null;
        return Optional.of(List.of(new ProbeRequest(a.verb(), ground(a.path(), UNUSED_ID), body)));
    }

    /**
     * A collision with existing state: create the resource, then create the identical one again.
     * The second request is the asserted one, so whatever uniqueness or availability rule the
     * application enforces is exercised by the body colliding with itself.
     *
     * <p>Requires a creating verb on a non-parameterized path and a body derived from the model —
     * two empty objects would be refused as invalid input long before anything could collide, so
     * without a derivable body this abstains rather than manufacture a 400 and report it as a
     * missing 409.
     */
    private static Optional<List<ProbeRequest>> collision(BehaviouralCriterionParser.Assertion a,
                                                          Path artifactRoot) {
        if (!"POST".equals(a.verb()) || isParameterized(a.path())) return Optional.empty();
        Optional<String> body = derivedBody(a.path(), artifactRoot);
        if (body.isEmpty()) return Optional.empty();
        return Optional.of(List.of(
                new ProbeRequest("POST", a.path(), body.get()),
                new ProbeRequest("POST", a.path(), body.get())));
    }

    /** Whether requests with this verb carry a request body at all. */
    static boolean carriesBody(String verb) {
        return "POST".equals(verb) || "PUT".equals(verb) || "PATCH".equals(verb);
    }

    static boolean isParameterized(String path) {
        return variableCount(path) > 0;
    }

    static int variableCount(String path) {
        if (path == null) return 0;
        int count = 0;
        for (int i = path.indexOf("{}"); i >= 0; i = path.indexOf("{}", i + 2)) count++;
        return count;
    }

    /**
     * A body built from the artifact's own model fields, or empty when none could be derived.
     * {@link RequestBodySynthesizer} always appends {@code &#123;&#125;} as its last candidate, so a
     * single-element result means only that fallback was available — which is not a body that can
     * create anything.
     */
    static Optional<String> derivedBody(String path, Path artifactRoot) {
        List<String> candidates = RequestBodySynthesizer.candidateBodies(path, artifactRoot);
        return candidates.size() > 1 ? Optional.of(withoutAssignedIdentifier(candidates.get(0))) : Optional.empty();
    }

    /**
     * Drops the field the server assigns, which a client creating a resource does not supply. It is
     * the same field the executor reads back to ground a later request ({@code $1.id}), so sending
     * a value for it invites the created resource and the reference to disagree — and on a collision
     * probe it would collide on the supplied value rather than on the rule under test.
     */
    static String withoutAssignedIdentifier(String body) {
        if (body == null || !body.contains("\"id\"")) return body;
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = (ObjectNode) mapper.readTree(body);
            node.remove("id");
            return node.isEmpty() ? body : mapper.writeValueAsString(node);
        } catch (Exception e) {
            return body; // an unparseable body is the synthesizer's business, not this method's
        }
    }

    /** The path up to (excluding) its first variable segment: {@code /loans/{}/return} → {@code /loans}. */
    static String collectionPath(String path) {
        int variable = path.indexOf("/{}");
        return variable <= 0 ? path : path.substring(0, variable);
    }

    /**
     * Replaces the FIRST variable segment with {@code value}; later ones are left as they are.
     *
     * <p>The replacement is quoted: {@code $1.id} is a legal value here and {@code $1} would
     * otherwise be read as a regex group reference, which throws rather than substituting.
     */
    static String ground(String path, String value) {
        return path.replaceFirst("\\{}", Matcher.quoteReplacement(value));
    }
}
