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

    /**
     * The input this requirement names beyond the path it addresses — the component identity
     * normalization drops. Empty when the requirement names none.
     *
     * <p>Reading it is separate from deciding what to do with it. {@link #exercisable} decides
     * whether the requirement can be probed at all, and the probe synthesizer carries the
     * component into the request it builds. Both derive it from HERE rather than re-deriving it,
     * which is how the two would drift apart.
     */
    public static Optional<String> statedInput(String criterion) {
        if (criterion == null) return Optional.empty();
        Matcher em = ENDPOINT.matcher(criterion);
        while (em.find()) {
            Optional<String> stated = EndpointSurfaceQuery.discardedInput(em.group(2));
            if (stated.isPresent()) return stated;
        }
        return Optional.empty();
    }

    /**
     * The assertion a probe can actually be built from — {@link #parse}, minus the requirements
     * whose stated input cannot be grounded.
     *
     * <p><b>Why this is separate from {@link #parse}.</b> Parsing READS a criterion; it is right
     * that it reads a pattern like {@code GET /loans?memberId={id}} and reports the endpoint the
     * requirement addresses. Exercisability is a different question with a different answer, and
     * before T1-FW three callers asked the reading question while meaning the exercise one:
     * {@code CriteriaExecutabilityReadout} counted such a criterion as executable and reported it
     * in {@code criteria_executable_pct}, and {@code BehaviouralClosureVerifier} put it in the
     * list that buys a package and a boot before anything is probed. Both then found that the
     * synthesizer abstains on it, having already spent the run's time saying so.
     *
     * <p><b>What cannot be grounded, and why it is the pattern rather than the status.</b> A
     * component stated as a VALUE ({@code ?memberId=1}) rides into the request unchanged. One
     * stated as a PATTERN needs a resource created first and the collection to create it in read
     * off the handler's own parameter declaration — the capability {@code T1-FV} names and this
     * project does not have. Grounding a pattern with an unused id was built and then WITHDRAWN on
     * measurement: it is safe only where the requirement asserts the reference resolves to
     * nothing, and the specification never said that — see the synthesizer, which holds that
     * finding in full.
     */
    public static Optional<Assertion> exercisable(String criterion) {
        Optional<Assertion> parsed = parse(criterion);
        if (parsed.isEmpty()) return parsed;
        return statedInput(criterion).filter(c -> c.contains("{")).isPresent()
                ? Optional.empty()
                : parsed;
    }

    /** Verb-qualified path mention; same shape the endpoint surface query reads out of spec prose. */
    /**
     * Whether the criterion mentions the served surface at all — a verb-qualified path, without the
     * stricter conditions {@link #parse} imposes (exactly one endpoint, a status, a groundable
     * input).
     *
     * <p>Exists so `ExercisableCriterionQuery` can answer "addresses the surface" for both target
     * shapes in one place. `CriteriaExecutabilityReadout` previously carried its own copy of this
     * regex, which put HTTP vocabulary in an observability class and gave one question two homes.
     * The ENDPOINT pattern below is that question's real owner.
     */
    public static boolean mentionsSurface(String criterion) {
        return criterion != null && ENDPOINT.matcher(criterion).find();
    }

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
            // T1-FW: the path is normalized for IDENTITY here, which discards any input
            // component the requirement named. That is correct for saying WHICH endpoint this
            // criterion addresses, and wrong for exercising it — a criterion about
            // /loans?memberId={id} exercised as bare /loans tests a different input, and the
            // artifact's correct refusal of it was recorded as a failure in 12 of 29 archived
            // runs across 190 refused closure attempts. The component is not lost: statedInput
            // reads it back, exercisable() decides whether it can be grounded, and the probe
            // synthesizer carries it into the request. Nothing is decided in this loop.
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
