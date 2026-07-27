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
 *   <li>the path carries no path variable — an id the probe never created cannot be supplied
 *       honestly, and guessing one turns a correct artifact into a false failure.</li>
 * </ul>
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
     */
    private static final Pattern STATUS = Pattern.compile(
            "\\b(?:HTTP|status(?:\\s+code)?)\\s+(\\d{3})\\b", Pattern.CASE_INSENSITIVE);

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
        if (path.contains("{}")) return Optional.empty(); // an id we never created — cannot probe honestly

        Set<Integer> codes = new LinkedHashSet<>();
        Matcher sm = STATUS.matcher(criterion);
        while (sm.find()) {
            codes.add(Integer.parseInt(sm.group(1)));
        }
        if (codes.size() != 1) return Optional.empty();

        return Optional.of(new Assertion(criterion.strip(), verb, path, codes.iterator().next()));
    }
}
