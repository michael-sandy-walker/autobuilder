package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
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

    /** Cache keyed by (specification digest, criterion text digest). */
    private final Map<String, List<ProbeRequest>> cache = new ConcurrentHashMap<>();

    /**
     * The request sequence that exercises {@code assertion}, or empty when no trustworthy sequence
     * could be obtained (model failure, unparseable answer, or a final request that does not match
     * the criterion's own endpoint). Empty means "do not probe this criterion".
     */
    public Optional<List<ProbeRequest>> synthesize(String specification,
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

    /** Numbered request lines, in order. A body of {@code -} (or absent) becomes null. */
    List<ProbeRequest> parse(String raw) {
        List<ProbeRequest> out = new ArrayList<>();
        Matcher m = REQUEST_LINE.matcher(raw);
        while (m.find() && out.size() < MAX_REQUESTS) {
            String path = EndpointSurfaceQuery.normalizePath(m.group(2));
            if (path == null) continue;
            String body = m.group(3) == null ? "" : m.group(3).strip();
            out.add(new ProbeRequest(m.group(1).toUpperCase(),
                    path,
                    body.isEmpty() || "-".equals(body) ? null : body));
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

    private static String digest(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
