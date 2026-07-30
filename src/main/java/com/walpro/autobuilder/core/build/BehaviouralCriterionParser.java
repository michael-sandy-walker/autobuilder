package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-CU: the deterministic half of executable criterion verification — reads an acceptance
 * criterion and answers "which request, and which response status, does this assert?".
 *
 * <p>The defect it serves (run {@code 2026-07-27_16-41-48}): the Books subgoal carried the
 * SPEC-bound criterion {@code "POST /books with invalid input returns HTTP 400 with JSON body
 * {"message":"..."}"}, the evaluator read the sources and answered {@code ✓}, and the subgoal
 * closed 62 s in at score 0.83 with no constraint anywhere in the artifact. The unmet-criterion
 * veto in {@code SubGoalFulfillmentHeuristic} never fired because nothing ever contradicted the
 * model. Criteria of this shape are mechanically checkable against a running artifact; this parser
 * is what turns the prose into the request to make and the status to expect.
 *
 * <p><b>Abstain-heavy by construction.</b> A criterion produces an assertion only when all of the
 * following hold; anything else yields empty, and empty always means "no opinion", never "unmet":
 * <ul>
 *   <li>exactly ONE verb-qualified endpoint is mentioned — two make the subject ambiguous;</li>
 *   <li>exactly ONE response status is stated, and it is introduced by an explicit marker
 *       ({@code HTTP 400}, {@code status 404}, {@code status code 409}). A bare three-digit number
 *       is never a status: the same specs carry {@code max 120 chars} and {@code \d{13}}, and
 *       reading those as response codes would manufacture assertions the criterion never made;</li>
 *   <li>the path MAY carry path variables. It is then a pattern, and grounding it to a concrete
 *       id is the synthesizer's job — a literal unused id for an absent-resource expectation, or
 *       a value captured from an earlier response for one about a resource that must exist. A
 *       sequence whose final request does not match this pattern is rejected.</li>
 * </ul>
 *
 * <p><b>Why path variables were excluded at first, and why that changed.</b> The original cut
 * abstained on any {@code {}} path, because an id the probe never created cannot be supplied
 * honestly and guessing one turns a correct artifact into a false failure. Measured against the
 * run corpus, that exclusion put <b>4 of the 11 requirements this project's specification states
 * </b> permanently out of reach — among them {@code POST /loans/{id}/return → 404}, an observed
 * smoke failure. A permanently unverifiable requirement reads as "unverified", which in a readout
 * is indistinguishable from "fine". The grounding problem is real, so it moved to where the
 * evidence is (the synthesized sequence) rather than being avoided by silence.
 *
 * <p>Nothing here knows what any particular status <em>means</em>. The criterion supplies the
 * expectation and the running artifact supplies the answer; this class only reads.
 */
public final class BehaviouralCriterionParser {

    private BehaviouralCriterionParser() {
    }

    /**
     * One executable assertion: send {@code verb} to {@code path}, expect {@code expectedStatus}.
     * {@code criterionText} is carried verbatim so a failure can name the criterion in the words
     * the planner wrote, not a paraphrase.
     */
    public record Assertion(String criterionText, String verb, String path, int expectedStatus) {
        /** e.g. {@code POST /books → 400}. */
        public String describe() {
            return verb + " " + path + " → " + expectedStatus;
        }
    }

    /** Verb-qualified path mention; same shape the endpoint surface query reads out of spec prose. */
    private static final Pattern ENDPOINT = Pattern.compile(
            "\\b(GET|POST|PUT|PATCH|DELETE)\\s+(/[A-Za-z0-9_\\-{}/.?=&]*)");

    /**
     * A response status must be introduced by a marker. {@code HTTP 201}, {@code status 404} and
     * {@code status code 409} count; {@code 120} in "max 120 chars" does not.
     *
     * <p>T4-DB: a RESPONSE VERB is also a marker, because the loop's own criteria very often write
     * the status without the word "HTTP" — {@code POST /loans returns 404 Not Found when book or
     * member does not exist} is verbatim from a real run, and it is smoke check {@code [14]}, one
     * of the three that failed in every frozen run of 2026-07-30. Measured over those three runs'
     * goal trees, 13 criteria named a verb, a path AND a status and still did not parse, all for
     * this reason; the whole chain downstream — probe synthesis, contradiction, repair — was
     * gated on a word the writer had no reason to use.
     *
     * <p>The marker is what keeps this safe. "max 120 chars" is in this very specification, and
     * 120 is a three-digit number: it does not follow a response verb, so it is not read as a
     * status. The range check excludes the rest. Where a number in the 2xx range does slip
     * through, nothing follows from it — {@link DeterministicProbeSynthesizer} declines success
     * statuses anyway.
     */
    private static final Pattern STATUS = Pattern.compile(
            "\\b(?:HTTP|status(?:\\s+code)?|returns?|answers?|responds?\\s+with|→)\\s+(\\d{3})\\b",
            Pattern.CASE_INSENSITIVE);

    /** Outside this range a three-digit number is not a response status whatever precedes it. */
    private static boolean isResponseStatus(int code) {
        return code >= 100 && code <= 599;
    }

    /**
     * The assertion this criterion makes, or empty when the criterion is not a single unambiguous
     * statement about one endpoint's response status. Empty is "no opinion" — callers must never
     * treat it as a failed criterion.
     */
    public static Optional<Assertion> parse(String criterion) {
        if (criterion == null || criterion.isBlank()) return Optional.empty();

        Set<String> endpoints = new LinkedHashSet<>();
        String verb = null;
        String path = null;
        Matcher em = ENDPOINT.matcher(criterion);
        while (em.find()) {
            String normalized = EndpointSurfaceQuery.normalizePath(em.group(2));
            if (normalized == null) continue;
            String key = em.group(1).toUpperCase() + " " + normalized;
            if (endpoints.add(key)) {
                verb = em.group(1).toUpperCase();
                path = normalized;
            }
        }
        if (endpoints.size() != 1) return Optional.empty();

        Set<Integer> codes = new LinkedHashSet<>();
        Matcher sm = STATUS.matcher(criterion);
        while (sm.find()) {
            int code = Integer.parseInt(sm.group(1));
            if (isResponseStatus(code)) codes.add(code);
        }
        if (codes.size() != 1) return Optional.empty();

        return Optional.of(new Assertion(criterion.strip(), verb, path, codes.iterator().next()));
    }
}
