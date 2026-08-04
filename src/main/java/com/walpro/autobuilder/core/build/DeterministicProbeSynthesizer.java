package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            case 200, 201 -> succeeded(assertion, artifactRoot);
            case 400 -> rejectedInput(assertion, artifactRoot);
            case 404 -> absentResource(assertion, artifactRoot);
            case 409 -> collision(assertion, artifactRoot);
            default -> Optional.empty();
        };
    }

    /**
     * T4-DB: the request the application must ACCEPT. Measured 2026-08-04 over 625 criteria from
     * recent runs, the happy path is the largest unprobed shape by a wide margin — 201 is 17% of
     * all criteria and 200 is 15%, together <b>83% of everything the synthesizer could not build</b>,
     * against 6.6% for the bounded-count 422 this ticket was chasing. The loop could probe REFUSAL
     * (400/404/409) and not SUCCESS, so {@code T4-CU} could never refute the commonest claim of
     * all: that an endpoint works.
     *
     * <p>Two shapes, both expressible with the existing pieces:
     * <ul>
     *   <li>a read of a collection — one request, no body at all;</li>
     *   <li>a read of one resource — create it first, then address it by {@code $1.id}, exactly the
     *       setup {@link #rejectedInput} already performs, including its abstention when the setup
     *       request is refused.</li>
     * </ul>
     *
     * <p><b>A create is deliberately NOT probed, and the field says why.</b> {@code derivedBody}
     * fills placeholders — {@code {"name":"value","email":"value"}}, {@code {"bookId":1}} — which a
     * correct application refuses: run 2026-08-04_14-58-02 shows 53 setup requests declined for
     * exactly that reason. Asserting 201 against such a body would report a contradiction the
     * application never committed, on a spec whose {@code isbn} must match {@code \d{13}}. The
     * whole value of a success probe is destroyed if it cannot tell "my body was invalid" from
     * "your endpoint is broken", and nothing in the criterion supplies a valid body. Covering 200
     * honestly is worth more than covering 201 wrongly.
     */
    private static Optional<List<ProbeRequest>> succeeded(BehaviouralCriterionParser.Assertion a,
                                                          Path artifactRoot) {
        boolean parameterized = isParameterized(a.path());
        if (!carriesBody(a.verb())) {
            if (!parameterized) {
                // Read a collection: nothing to set up, nothing to guess.
                return Optional.of(List.of(new ProbeRequest(a.verb(), a.path(), null)));
            }
            // Read one resource: it has to exist first, or the answer is 404 and says nothing
            // about the criterion.
            Optional<String> setupBody = derivedBody(collectionPath(a.path()), artifactRoot);
            if (setupBody.isEmpty()) return Optional.empty();
            return Optional.of(List.of(
                    new ProbeRequest("POST", collectionPath(a.path()), setupBody.get()),
                    new ProbeRequest(a.verb(), ground(a.path(), CREATED_ID), null)));
        }
        // Body-carrying verbs are left unprobed for success: the only body available is derived
        // from field names and a correct application refuses it, so a 400 answer would be read as
        // a contradiction against a working endpoint.
        return Optional.empty();
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
     * <p>When the path carries no variable the reference lives in the BODY instead, which
     * {@link #absentReferenceInBody} handles — see T4-DB for why that mattered more than it looked.
     *
     * <p><b>Known precision trade.</b> For a verb that carries a body the empty object is sent,
     * because whether the endpoint wants a body cannot be read off the criterion. An endpoint that
     * validates the body before resolving the path would answer 400 and be reported wrongly. The
     * alternative — abstaining on every body-carrying verb — would put a spec-stated requirement
     * permanently out of reach, which reads as "verified" in any readout; that failure mode is the
     * one {@link BehaviouralCriterionParser} was widened to avoid.
     */
    private static Optional<List<ProbeRequest>> absentResource(BehaviouralCriterionParser.Assertion a,
                                                               Path artifactRoot) {
        if (!isParameterized(a.path())) return absentReferenceInBody(a, artifactRoot);
        String body = carriesBody(a.verb()) ? EMPTY_BODY : null;
        return Optional.of(List.of(new ProbeRequest(a.verb(), ground(a.path(), UNUSED_ID), body)));
    }

    /**
     * T4-DB: a reference that resolves to nothing, carried in the request BODY rather than the path.
     *
     * <p><b>Why this exists.</b> Requiring a parameterized path put a whole shape of requirement
     * permanently out of reach, and it was not a rare one. Across the three frozen runs of
     * 2026-07-30 the checks that failed in every single one were a body-borne absent reference
     * twice over and a bounded-count rule once — precisely the shapes this class abstained on —
     * while every check it COULD construct a probe for passed. The loop repairs what it can
     * observe; a requirement it cannot exercise received not one cycle of work in 122.
     *
     * <p><b>How the reference is identified, without knowing anything about the domain.</b> The
     * body is derived from the artifact's own model, so its field names are facts about this
     * project rather than assumptions. A field is treated as the reference under test when the
     * criterion NAMES it — "when POST /loans has non-existent bookId" names {@code bookId}. No
     * naming convention is assumed and no field is guessed at: a criterion that names none of the
     * body's fields yields no probe.
     *
     * <p><b>What the probe can and cannot conclude.</b> Every named reference is pointed at an
     * unused id, so an application answering the asserted status is behaving correctly whichever
     * reference it checked first. It cannot attribute the answer to one particular field, and it
     * does not try to. An application that validates the body's shape before resolving references
     * answers 400 and is reported as contradicting — which is the correct reading, since the
     * requirement asks for the absent-reference status on exactly this request.
     */
    private static Optional<List<ProbeRequest>> absentReferenceInBody(BehaviouralCriterionParser.Assertion a,
                                                                      Path artifactRoot) {
        if (!carriesBody(a.verb())) return Optional.empty();
        Optional<String> derived = derivedBody(a.path(), artifactRoot);
        if (derived.isEmpty()) return Optional.empty();
        return pointNamedReferencesAtNothing(derived.get(), a.criterionText())
                .map(body -> List.of(new ProbeRequest(a.verb(), a.path(), body)));
    }

    /**
     * The derived body with every field the criterion names set to {@link #UNUSED_ID}, or empty
     * when the criterion names none of them — in which case there is no reference under test and
     * guessing one would manufacture a contradiction.
     */
    static Optional<String> pointNamedReferencesAtNothing(String body, String criterionText) {
        if (body == null || criterionText == null || criterionText.isBlank()) return Optional.empty();
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = (ObjectNode) mapper.readTree(body);
            boolean named = false;
            for (String field : List.copyOf(iterable(node.fieldNames()))) {
                if (!namesField(criterionText, field)) continue;
                // Numeric or textual, the id keeps the shape the model declared: a string field
                // given a bare number fails to bind and the endpoint answers 400 for the wrong
                // reason.
                if (node.get(field).isTextual()) node.put(field, UNUSED_ID);
                else node.put(field, Long.parseLong(UNUSED_ID));
                named = true;
            }
            return named ? Optional.of(mapper.writeValueAsString(node)) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Whether {@code criterion} names {@code field} as a whole word. Substring matching would let
     * a criterion mentioning "id" claim every field ending in it.
     */
    private static boolean namesField(String criterion, String field) {
        return Pattern.compile("\\b" + Pattern.quote(field) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(criterion).find();
    }

    private static List<String> iterable(java.util.Iterator<String> it) {
        List<String> out = new java.util.ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
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
