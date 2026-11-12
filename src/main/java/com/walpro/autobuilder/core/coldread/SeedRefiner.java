package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCallContext;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Account;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.JudgedClaim;
import com.walpro.autobuilder.core.coldread.entry.EntryKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * T4-EQ stage 1: a composed seed, arranged and worded the way the project is USED.
 *
 * <p>{@link SeedComposer} writes one line per thing the kept claims show, under the type that declares the ways in.
 * A person specifying the project would not have written it so: a program with one entry type has one heading for
 * everything, nothing says what kind of thing it is or how it is reached, and a line says "prints an error message"
 * where the message was observed. This step changes three things and adds no statement of its own:
 *
 * <ul>
 * <li><b>Sections by use.</b> Each line goes under the way its claims EXERCISED the project -- the command word of a
 * run, the resource of a request, the method a test calls -- read from the claims, no model. A use that carries
 * fewer than two lines has no section of its own.</li>
 * <li><b>A line of context</b>, from structure: the kind of way in and the uses exercised. Never a criterion.</li>
 * <li><b>Wording from what was observed.</b> The model rewrites each line with the claim's exercise and the answer
 * the project gave beside it. A rewritten line replaces the composed one only when it carries exactly the same
 * claim ids; any other line of the answer is dropped and the composed wording stays.</li>
 * </ul>
 *
 * <p>So every line of the result cites the claims its composed line cited, and no kept claim loses its line: the
 * arrangement is mechanical and the wording falls back line by line. No line is merged or generalised here -- a
 * line that says more than its claims showed is stage 2's question.
 */
@Slf4j
@Component
public class SeedRefiner {

    /** Starts the line of context; read back as {@link SeedComposer.Line#CONTEXT}. */
    public static final String CONTEXT = "How it is used:";
    /** The section of the uses that carry fewer than two lines, and of lines whose claims name no use. */
    static final String OTHER = "Other uses";
    static final int OBSERVED_CHARS = 300;

    private final LLMClient llmClient;
    private final boolean enabled;

    public SeedRefiner(LLMClient llmClient,
                       // Whether a reverse-engineered seed is arranged by how the project is used and worded from what was observed, before the operator sees it (T4-EQ).
                       @Value("${autobuilder.reverse-engineering.seed-refinement.enabled:true}") boolean enabled) {
        this.llmClient = llmClient;
        this.enabled = enabled;
    }

    /** One composed line: its words and the claims it rests on. */
    record Cited(String text, List<String> cites) {
        String render() {
            return "- " + text + " [" + String.join(", ", cites) + "]";
        }
    }

    /** The composed seed refined; the composed text itself when it is off or holds no cited line. */
    public String refine(Account account, String composed) {
        if (!enabled || composed == null || composed.isBlank()) return composed;
        Map<String, List<Cited>> sections = sections(account, composed);
        if (sections.isEmpty()) return composed;
        Map<String, List<Cited>> worded = sections;
        try {
            String answer = ask(prompt(account, sections));
            if (answer != null) {
                int[] replaced = new int[1];
                worded = reworded(sections, answer, replaced);
                int total = sections.values().stream().mapToInt(List::size).sum();
                log.warn("T4-EQ: seed refined -- {} section(s) by use, {} of {} line(s) reworded from what was observed",
                        sections.size(), replaced[0], total);
            }
        } catch (RuntimeException e) {
            log.warn("T4-EQ: the seed's lines keep their composed wording -- {}", e.getMessage());
        }
        return render(purposeLine(composed), contextLine(account, sections), worded);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Arrangement: mechanical.
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The use a claim exercised the project through: a run's command word, a request's resource (the first segment
     * of the last request's path), the method a test's judged call reaches. Null when the claim names none (a run
     * with no arguments).
     */
    static String useOf(JudgedClaim j) {
        ClaimProposer.Claim k = j.claim();
        if (k.kind() == EntryKind.COMMAND_STYLE) {
            return k.run() == null || k.run().args() == null || k.run().args().isEmpty() ? null : k.run().args().get(0);
        }
        if (k.kind() == EntryKind.SERVICE_STYLE) {
            if (k.sequence() == null || k.sequence().isEmpty()) return null;
            String path = k.sequence().get(k.sequence().size() - 1).path();
            if (path == null) return null;
            path = path.replaceAll("[?#].*$", "");
            int second = path.indexOf('/', 1);
            return second < 0 ? path : path.substring(0, second);
        }
        return j.byFeature() == null ? null : j.byFeature().entry();
    }

    /**
     * The composed seed's cited lines by use, in the order each use first appears; a line belongs to the use most of
     * its claims went through (the first of them on a tie). Uses with fewer than two lines are gathered last.
     */
    static Map<String, List<Cited>> sections(Account account, String composed) {
        Map<String, JudgedClaim> byId = new LinkedHashMap<>();
        account.claims().forEach(j -> byId.put(j.claim().id(), j));
        Map<String, List<Cited>> byUse = new LinkedHashMap<>();
        for (String raw : composed.split("\\R")) {
            Matcher m = SeedComposer.CITED.matcher(raw.strip());
            if (!m.matches()) continue;
            List<String> cites = new ArrayList<>();
            for (String id : m.group(2).split(",")) cites.add(id.strip());
            Map<String, Integer> votes = new LinkedHashMap<>();
            for (String id : cites) {
                JudgedClaim j = byId.get(id);
                String use = j == null ? null : useOf(j);
                if (use != null) votes.merge(use, 1, Integer::sum);
            }
            String use = OTHER;
            int best = votes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            for (Map.Entry<String, Integer> v : votes.entrySet()) {
                if (v.getValue() == best) {
                    use = v.getKey();
                    break;
                }
            }
            byUse.computeIfAbsent(use, u -> new ArrayList<>()).add(new Cited(m.group(1).strip(), cites));
        }
        Map<String, List<Cited>> out = new LinkedHashMap<>();
        List<Cited> other = new ArrayList<>();
        byUse.forEach((use, lines) -> {
            if (use.equals(OTHER) || lines.size() < 2) other.addAll(lines);
            else out.put(use, lines);
        });
        if (!other.isEmpty()) out.put(OTHER, other);
        return out;
    }

    /** The composed seed's line on what the project is, as written (with its README lines cited); null without one. */
    static String purposeLine(String composed) {
        for (String raw : composed.split("\\R")) {
            if (raw.strip().regionMatches(true, 0, "What it is:", 0, "What it is:".length())) return raw.strip();
        }
        return null;
    }

    /** The kind of way in, in the words a person is shown for it, and the uses that have a section. */
    static String contextLine(Account account, Map<String, List<Cited>> sections) {
        EntryKind kind = null;
        for (EntryKind k : EntryKind.values()) if (k.label().equals(account.kind())) kind = k;
        if (kind == null) return null;
        List<String> uses = sections.keySet().stream().filter(u -> !u.equals(OTHER)).toList();
        return CONTEXT + " " + kind.label() + " -- " + kind.definition() + "."
                + (uses.isEmpty() ? "" : " Exercised here through: " + String.join(", ", uses) + ".");
    }

    static String render(String purpose, String context, Map<String, List<Cited>> sections) {
        StringBuilder sb = new StringBuilder();
        if (purpose != null) sb.append(purpose).append('\n');
        if (context != null) sb.append(context).append('\n');
        sections.forEach((use, lines) -> {
            sb.append('\n').append(use).append(":\n");
            lines.forEach(l -> sb.append(l.render()).append('\n'));
        });
        return sb.toString().strip() + "\n";
    }

    // ------------------------------------------------------------------------------------------------------------
    // Wording: the model's, line by line, kept only where the claim ids are exactly the composed line's.
    // ------------------------------------------------------------------------------------------------------------

    static String prompt(Account account, Map<String, List<Cited>> sections) {
        Map<String, JudgedClaim> byId = new LinkedHashMap<>();
        account.claims().forEach(j -> byId.put(j.claim().id(), j));
        StringBuilder sb = new StringBuilder("""
                Below are the lines of a specification of an existing project, grouped by how the project is used. \
                Under each line are the checks it rests on: how the project was exercised, and what it answered when \
                it was.

                Rewrite each line so that a person who has to BUILD this project from the specification is told \
                exactly what to build: name the use the way it was exercised (the command with its arguments as \
                placeholders, the request, the call), and state the answer as it was observed -- the exit status, the \
                status code, and the exact words of a message where one was printed, with a value that belongs to \
                the one check written as a placeholder ("no such file: <path>", not the path one check used). Say \
                where a message goes only if the line already says it.

                One line out for each line in: do not merge lines, drop none, add none, and state nothing the checks \
                under a line do not show. Every line keeps exactly the ids it has now, in brackets at its end, and \
                stays under its heading.

                Answer in exactly this form, nothing else:
                <heading>:
                - <the line, rewritten> [<its ids>]
                - ...

                """);
        sections.forEach((use, lines) -> {
            sb.append(use).append(":\n");
            for (Cited l : lines) {
                sb.append(l.render()).append('\n');
                for (String id : l.cites()) {
                    JudgedClaim j = byId.get(id);
                    if (j == null) continue;
                    sb.append("    ").append(id).append(" exercised: ").append(exercised(j.claim()));
                    String seen = observed(j);
                    if (seen != null) sb.append("\n    ").append(id).append(" answered: ").append(seen);
                    sb.append('\n');
                }
            }
            sb.append('\n');
        });
        return sb.toString();
    }

    private static String exercised(ClaimProposer.Claim k) {
        String form = SeedComposer.exercised(k);
        if (form != null) return form;
        return k.test() == null ? "(a test)" : cut(k.test().strip().replaceAll("\\s+", " "));
    }

    /** What the project answered on the tree as it is: the status and the output, cut; null when nothing was kept. */
    static String observed(JudgedClaim j) {
        if (j.observations() == null) return null;
        for (ReverseEngineeringService.Observation o : j.observations()) {
            if (!"as-is".equals(o.variant()) || o.status() == null) continue;
            String body = o.body() == null || o.body().isBlank() ? "(no output)" : cut(o.body().strip().replace("\n", "\\n"));
            return (j.claim().kind() == EntryKind.COMMAND_STYLE ? "exit " : "status ") + o.status() + ", output: " + body;
        }
        return null;
    }

    private static String cut(String s) {
        return s.length() <= OBSERVED_CHARS ? s : s.substring(0, OBSERVED_CHARS) + " ...";
    }

    /**
     * The sections with each line's wording taken from {@code answer} where the answer has a line carrying exactly
     * that line's ids (the n-th such line for the n-th composed line with them); every other line as composed.
     */
    static Map<String, List<Cited>> reworded(Map<String, List<Cited>> sections, String answer, int[] replaced) {
        Map<String, List<String>> byCites = new LinkedHashMap<>();
        for (String raw : answer.split("\\R")) {
            Matcher m = SeedComposer.CITED.matcher(raw.strip());
            if (!m.matches() || m.group(1).isBlank()) continue;
            List<String> cites = new ArrayList<>();
            for (String id : m.group(2).split(",")) cites.add(id.strip());
            byCites.computeIfAbsent(String.join(",", cites), k -> new ArrayList<>()).add(m.group(1).strip());
        }
        Map<String, List<Cited>> out = new LinkedHashMap<>();
        sections.forEach((use, lines) -> {
            List<Cited> now = new ArrayList<>();
            for (Cited l : lines) {
                List<String> offered = byCites.get(String.join(",", l.cites()));
                if (offered == null || offered.isEmpty()) {
                    now.add(l);
                    continue;
                }
                now.add(new Cited(offered.remove(0), l.cites()));
                if (replaced != null) replaced[0]++;
            }
            out.put(use, now);
        });
        return out;
    }

    private String ask(String prompt) {
        try (var scope = LlmCallContext.scope("seed-refiner", "reverse-engineering")) {
            ResponseEntity<ChatResponse> response = llmClient.ask(List.of(
                    new Message("system", "You reword the lines of a software specification from recorded evidence. "
                            + "You state only what the evidence shows."),
                    new Message("user", prompt)));
            return response == null || response.getBody() == null ? null : response.getBody().getFirstContent();
        }
    }
}
