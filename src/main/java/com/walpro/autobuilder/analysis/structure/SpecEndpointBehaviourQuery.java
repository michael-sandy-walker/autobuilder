package com.walpro.autobuilder.analysis.structure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2-EV: the response behaviours a specification states for each endpoint it names.
 *
 * <p><b>The gap this fills.</b> {@link EndpointSurfaceQuery} answers "does this endpoint exist?"
 * and the closure-edge coverage pass injects a work item whose done-when is exactly that — "a
 * request mapping for POST /members exists in the application sources". That phrasing is
 * deliberate (a done-when that embeds a defect statement becomes unconfirmable the moment the work
 * lands), but it means an endpoint that arrives through the coverage path acquires <em>no
 * behavioural requirement at all</em>. Measured in run {@code 2026-07-27_20-33-22}: the only two
 * criteria mentioning {@code /members} in the entire run were the two mapping-exists items — no
 * 201, no 400, no 409 anywhere. Members was never planned as real work, so once the mapping
 * existed it was finished, and {@code POST /members} with a missing name has failed the behaviour
 * smoke in three of three scored runs with a different wrong status each time.
 *
 * <p><b>Two sources of a stated status, because the specification uses both.</b>
 * <ul>
 *   <li><b>Adjacent</b> — stated inside the endpoint's own clause: {@code GET /members/{id} (404
 *       if absent)}. The clause runs from the endpoint mention to the next one, and never crosses
 *       a paragraph boundary — without that bound the last endpoint of the document swallows every
 *       trailing sentence and inherits codes that belong to no endpoint.</li>
 *   <li><b>Cross-cutting</b> — stated once for the whole document in a paragraph that names no
 *       endpoint: {@code Invalid input returns HTTP 400 with JSON body}. This is the form the
 *       planner reliably drops, and the one behind the {@code [10]} failure.</li>
 * </ul>
 *
 * <p>A cross-cutting status is bound only to endpoints whose own clause states an input
 * constraint. That restraint is load-bearing: {@code POST /loans/{id}/return} carries no request
 * body, and demanding a 400 of it would mint a requirement the artifact can never satisfy — the
 * exact shape that walls a run.
 *
 * <p><b>Why a membership test rather than a marker.</b> Specification prose writes codes bare
 * ("404 if absent"), so the {@code HTTP}-marker discipline used for acceptance criteria does not
 * apply here. Instead a candidate must be a real response status: that alone rejects the {@code
 * 120} of "max 120 chars" without any prose heuristics. The status set and the constraint words
 * are declarative data rows, the same standing the request-verb table already has in
 * {@link EndpointSurfaceQuery} (see T4-BM — discovery, not frozen literals, is the strategic
 * answer).
 *
 * <p>Erring toward silence throughout: a specification that states no codes, or names no
 * endpoints, produces an empty map — never a default expectation.
 *
 * <p>T5-BR: a plain class, registered as a bean in {@code AppConfig} rather than annotated, so the
 * rules that read a specification's statuses (T3-CB) can be lifted out with it as a small module
 * that carries no Spring.
 */
public class SpecEndpointBehaviourQuery {

    /** Verb-qualified path mention; the one grammar, held in {@link EndpointPaths}. */
    private static final Pattern ENDPOINT = EndpointPaths.MENTION;

    /** Blank-line paragraph separator, tolerating the trailing whitespace real specs carry. */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");

    private static final Pattern THREE_DIGITS = Pattern.compile("\\b(\\d{3})\\b");

    /**
     * Response statuses a specification can plausibly state. Membership is the discriminator that
     * keeps quantities out: "max 120 chars" and "at most 3 active loans" contribute nothing.
     */
    private static final Set<Integer> RESPONSE_STATUSES = Set.of(
            200, 201, 202, 203, 204, 205, 206,
            300, 301, 302, 303, 304, 307, 308,
            400, 401, 402, 403, 404, 405, 406, 408, 409, 410, 412, 413, 415, 422, 423, 428, 429,
            500, 501, 502, 503, 504);

    /** Words that mark an endpoint clause as stating an input constraint. */
    private static final Pattern CONSTRAINT = Pattern.compile(
            "\\b(required|must|matches|match|unique|at most|at least|no more than|max|min|maximum|minimum)\\b",
            Pattern.CASE_INSENSITIVE);

    /** One stated behaviour: the status, and the specification's own words for when it applies. */
    public record StatedBehaviour(int status, String condition) {
        @Override
        public String toString() {
            return condition == null || condition.isBlank()
                    ? String.valueOf(status)
                    : status + " " + condition;
        }
    }

    /**
     * Statuses the specification states per endpoint, in document order. Empty map = no opinion
     * (no endpoints named, or no statuses stated anywhere).
     */
    public Map<Endpoint, List<StatedBehaviour>> statedBehaviours(String specText) {
        Map<Endpoint, List<StatedBehaviour>> out = new LinkedHashMap<>();
        if (specText == null || specText.isBlank()) return out;

        List<int[]> paragraphs = paragraphSpans(specText);
        List<int[]> mentions = new ArrayList<>();
        List<Endpoint> endpoints = new ArrayList<>();
        Matcher m = ENDPOINT.matcher(specText);
        while (m.find()) {
            String path = EndpointPaths.normalizePath(m.group(2));
            if (path == null) continue;
            mentions.add(new int[]{m.start()});
            endpoints.add(new Endpoint(m.group(1).toUpperCase(), path));
        }
        if (endpoints.isEmpty()) return out;

        List<StatedBehaviour> crossCutting = crossCuttingBehaviours(specText, paragraphs);

        for (int i = 0; i < endpoints.size(); i++) {
            int start = mentions.get(i)[0];
            int nextMention = i + 1 < mentions.size() ? mentions.get(i + 1)[0] : specText.length();
            int clauseEnd = Math.min(nextMention, paragraphEndFor(paragraphs, start, specText.length()));
            String clause = specText.substring(start, Math.max(start, clauseEnd));

            List<StatedBehaviour> stated = new ArrayList<>(behavioursIn(clause));
            if (CONSTRAINT.matcher(clause).find()) {
                for (StatedBehaviour cc : crossCutting) {
                    if (stated.stream().noneMatch(b -> b.status() == cc.status())) stated.add(cc);
                }
            }
            if (stated.isEmpty()) continue;
            out.computeIfAbsent(endpoints.get(i), k -> new ArrayList<>()).addAll(stated);
        }
        return out;
    }

    /** Statuses stated in paragraphs that name no endpoint — the document-wide rules. */
    private List<StatedBehaviour> crossCuttingBehaviours(String specText, List<int[]> paragraphs) {
        List<StatedBehaviour> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (int[] span : paragraphs) {
            String paragraph = specText.substring(span[0], span[1]);
            if (ENDPOINT.matcher(paragraph).find()) continue;
            // A document-wide rule states its condition BEFORE the code ("Invalid input returns
            // HTTP 400 with JSON body …"), unlike a clause-local one ("409 on duplicate"), so the
            // sentence is the readable unit here. Taking the text after the code instead yields a
            // phrase truncated mid-token, which would then be relayed into a criterion.
            for (String sentence : paragraph.split("(?<=\\.)\\s+")) {
                Matcher digits = THREE_DIGITS.matcher(sentence);
                while (digits.find()) {
                    int code = Integer.parseInt(digits.group(1));
                    if (!RESPONSE_STATUSES.contains(code) || !seen.add(code)) continue;
                    out.add(new StatedBehaviour(code, condense(sentence)));
                }
            }
        }
        return out;
    }

    /**
     * Statuses in one span, each carried with the specification's own condition wording — the text
     * from the code up to the next clause break. Relaying the spec's words keeps the resulting
     * criterion legible to a reader and actionable by an agent, instead of a bare number.
     */
    private List<StatedBehaviour> behavioursIn(String text) {
        List<StatedBehaviour> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        Matcher m = THREE_DIGITS.matcher(text);
        while (m.find()) {
            int code = Integer.parseInt(m.group(1));
            if (!RESPONSE_STATUSES.contains(code) || !seen.add(code)) continue;
            out.add(new StatedBehaviour(code, conditionAfter(text, m.end())));
        }
        return out;
    }

    /** The condition phrase following a stated code, trimmed at the next clause break. */
    private static String conditionAfter(String text, int from) {
        int end = text.length();
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ';' || c == ')' || c == '\n' || c == '.') { end = i; break; }
            if (c == ',') { end = i; break; }
        }
        String phrase = text.substring(from, end).strip();
        // Drop a leading connective left over from "returns HTTP 400 with JSON body".
        phrase = phrase.replaceFirst("^(with|and|or)\\b\\s*", "").strip();
        return phrase.length() > 80 ? phrase.substring(0, 80).strip() : phrase;
    }

    /** Collapses whitespace and clips a sentence to a length a criterion can carry. */
    private static String condense(String sentence) {
        String s = sentence.replaceAll("\\s+", " ").strip();
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1).strip();
        return s.length() > 90 ? s.substring(0, 90).strip() : s;
    }

    private static List<int[]> paragraphSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        int cursor = 0;
        Matcher m = PARAGRAPH_BREAK.matcher(text);
        while (m.find()) {
            spans.add(new int[]{cursor, m.start()});
            cursor = m.end();
        }
        spans.add(new int[]{cursor, text.length()});
        return spans;
    }

    private static int paragraphEndFor(List<int[]> spans, int position, int fallback) {
        for (int[] span : spans) {
            if (position >= span[0] && position < span[1]) return span[1];
        }
        return fallback;
    }

    /**
     * The behaviours rendered as acceptance criteria for {@code endpoint}, phrased so the
     * executable-criterion parser can read them back (one endpoint, one status, an explicit
     * {@code HTTP} marker) and so a reader sees the specification's own condition wording.
     */
    public List<String> criteriaFor(Endpoint endpoint, List<StatedBehaviour> behaviours) {
        List<String> out = new ArrayList<>();
        if (endpoint == null || behaviours == null) return out;
        for (StatedBehaviour b : behaviours) {
            String condition = b.condition() == null || b.condition().isBlank() ? "" : " — " + b.condition();
            out.add(endpoint.verb() + " " + endpoint.path() + " returns HTTP " + b.status() + condition);
        }
        return out;
    }
}
