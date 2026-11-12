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
 * arrangement is mechanical and the wording falls back line by line.
 *
 * <p><b>Stages 2 and 3 (one step, after the user's "describing things without naming the word"): the word for what
 * several lines describe.</b> Six lines on too few and too many arguments circle a rule nobody named. The model
 * names it and states it once ({@link #mergePrompt}); the code decides what may stand ({@link #merged}): a rule
 * replaces two or more WHOLE lines, all answered with one status, and cites every claim they cited. Which uses a
 * rule holds for is never the model's statement -- its heading is computed from the claims, so "all uses" can only
 * be said by claims that went through every use, and a rule shown for two of three stands under those two.
 * A rule can also say LESS than a line it replaced; each replaced line is therefore set beside its rule and the
 * model is asked whether the rule states all of it ({@link #saidPrompt}) -- anything but a yes brings the line
 * back as its own, out of the rule. That verdict is a model's; it can only restore a line, never remove one.
 * Whether a rule's sentence says more than its lines on a dimension the uses do not enumerate is not decidable
 * here; its claims stay cited and the operator confirms the seed.
 */
@Slf4j
@Component
public class SeedRefiner {

    /** Starts the line of context; read back as {@link SeedComposer.Line#CONTEXT}. */
    public static final String CONTEXT = "How it is used:";
    /** The section of the uses that carry fewer than two lines, and of lines whose claims name no use. */
    static final String OTHER = "Other uses";
    /** T4-EQ stage 2: the section of a rule whose claims went through every use that has a section. */
    static final String ALL = "All uses";
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
        String context = contextLine(account, sections);
        Map<String, List<Cited>> named = worded;
        try {
            String answer = ask(mergePrompt(worded));
            if (answer != null) {
                int[] rules = new int[1];
                named = merged(account, worded, answer, rules);
                // A rule may say LESS than a line it replaced (clitarget, 2026-11-12: "reported with the physical
                // line where the row begins" vanished into "a malformed row is reported"). Each replaced line is
                // set beside its rule; one the rule does not state in full comes back as its own line.
                List<Replaced> pairs = replacedLines(worded, named);
                if (!pairs.isEmpty()) {
                    String verdicts = ask(saidPrompt(pairs));
                    java.util.Set<Integer> said = verdicts == null ? java.util.Set.of() : saidIn(verdicts);
                    List<String> keep = new ArrayList<>();
                    for (int i = 0; i < pairs.size(); i++) {
                        if (said.contains(i + 1)) continue;
                        keep.add(String.join(",", pairs.get(i).line().cites()));
                    }
                    if (!keep.isEmpty()) {
                        named = merged(account, worded, answer, rules = new int[1], new java.util.HashSet<>(keep));
                        log.warn("T4-EQ: {} replaced line(s) said something their rule does not -- kept as their own lines", keep.size());
                    }
                }
                log.warn("T4-EQ: {} rule(s) named, each for two or more lines -- {} line(s) now, {} before",
                        rules[0], named.values().stream().mapToInt(List::size).sum(),
                        worded.values().stream().mapToInt(List::size).sum());
            }
        } catch (RuntimeException e) {
            log.warn("T4-EQ: no rule was named; every line stays as it is -- {}", e.getMessage());
        }
        return render(purposeLine(composed), context, named);
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

    // ------------------------------------------------------------------------------------------------------------
    // Stage 2 and 3: the word for what several lines describe. The model names and states the rule; which lines
    // it may replace, and which uses it holds for, are decided here from the claims.
    // ------------------------------------------------------------------------------------------------------------

    static String mergePrompt(Map<String, List<Cited>> sections) {
        StringBuilder sb = new StringBuilder("""
                Below are the lines of a specification of an existing project, under the ways the project is used. \
                Each line was checked on the project; the ids at its end name the checks.

                Some of these lines describe one and the same rule from different sides without ever naming it -- \
                a line per case, where a person specifying the project would have stated the rule once. Find those \
                rules. For each, write ONE line: first the name of the rule in a few plain words -- the word a person \
                would use for it -- then a colon, then the rule in one or two sentences a person understands at once, \
                with the exact messages and statuses the lines give. The line ends with ALL the ids of the lines it \
                replaces.

                - Lines under different headings may be one rule. Then do not say which uses it holds for: that is \
                added from the ids.
                - Only lines that end with the same exit status or status code can be one rule.
                - A rule says no more than the lines it replaces share. A line that states a case of its own -- a \
                special input, an exception, a different answer -- is not replaced: leave its ids out.
                - A rule replaces at least two lines, and replaces whole lines: take all the ids of a line or none.

                Answer with the new lines only, nothing else, each in this form:
                - <name of the rule>: <the rule> [<all ids of the lines it replaces>]

                """);
        sections.forEach((use, lines) -> {
            sb.append(use).append(":\n");
            lines.forEach(l -> sb.append(l.render()).append('\n'));
            sb.append('\n');
        });
        return sb.toString();
    }

    /**
     * The sections with every rule of {@code answer} that may stand put in the place of the lines it replaces. A
     * rule may stand when its ids are exactly those of two or more whole lines no earlier rule replaced, and the
     * claims behind them were all answered with one status. It stands under the use its lines stood under; under a
     * heading naming the uses when they are several; under {@link #ALL} when they are every use that has a section.
     */
    static Map<String, List<Cited>> merged(Account account, Map<String, List<Cited>> sections, String answer, int[] rules) {
        return merged(account, sections, answer, rules, java.util.Set.of());
    }

    /** A line a rule took the place of, beside that rule. */
    record Replaced(Cited rule, Cited line) {
    }

    /** Every line of {@code before} that is no longer in {@code after}, with the rule there that cites its claims. */
    static List<Replaced> replacedLines(Map<String, List<Cited>> before, Map<String, List<Cited>> after) {
        List<Cited> now = after.values().stream().flatMap(List::stream).toList();
        List<Replaced> out = new ArrayList<>();
        for (List<Cited> lines : before.values()) {
            for (Cited l : lines) {
                if (now.stream().anyMatch(n -> n == l)) continue;
                now.stream().filter(n -> n.cites().containsAll(l.cites())).findFirst().ifPresent(r -> out.add(new Replaced(r, l)));
            }
        }
        return out;
    }

    static String saidPrompt(List<Replaced> pairs) {
        StringBuilder sb = new StringBuilder("""
                Each numbered pair below is a RULE of a specification and one LINE the rule is meant to replace. \
                For each pair decide: does the rule state everything the line states? The use the line names \
                (which command, request or call) does not count -- that is kept elsewhere. Everything else does: a \
                condition, a special input, what is printed, where, in which order, how something is counted.

                Answer one line per pair, nothing else:
                <number>: yes
                or
                <number>: no -- <what the line states that the rule does not>

                """);
        for (int i = 0; i < pairs.size(); i++) {
            sb.append(i + 1).append(".\n  RULE: ").append(pairs.get(i).rule().text())
                    .append("\n  LINE: ").append(pairs.get(i).line().text()).append("\n");
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern SAID = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*[:.)]\\s*yes\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The pairs answered yes; a pair with any other answer, or none, counts as not said. */
    static java.util.Set<Integer> saidIn(String verdicts) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (String raw : verdicts.split("\\R")) {
            Matcher m = SAID.matcher(raw);
            if (m.find()) out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    /** As {@link #merged(Account, Map, String, int[])}, with the lines in {@code keep} (by their ids, comma-joined) never replaced. */
    static Map<String, List<Cited>> merged(Account account, Map<String, List<Cited>> sections, String answer, int[] rules,
                                           java.util.Set<String> keep) {
        Map<String, Integer> statusOf = new LinkedHashMap<>();
        for (JudgedClaim j : account.claims()) {
            if (j.observations() == null) continue;
            for (ReverseEngineeringService.Observation o : j.observations()) {
                if ("as-is".equals(o.variant()) && o.status() != null) statusOf.put(j.claim().id(), o.status());
            }
        }
        List<String> uses = sections.keySet().stream().filter(u -> !u.equals(OTHER)).toList();
        Map<Cited, String> useOfLine = new java.util.IdentityHashMap<>();
        sections.forEach((use, lines) -> lines.forEach(l -> useOfLine.put(l, use)));
        Map<Cited, Cited> replacedBy = new java.util.IdentityHashMap<>();
        Map<Cited, List<String>> rulesUses = new java.util.IdentityHashMap<>();
        for (String raw : answer.split("\\R")) {
            Matcher m = SeedComposer.CITED.matcher(raw.strip());
            if (!m.matches() || m.group(1).isBlank()) continue;
            java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
            for (String id : m.group(2).split(",")) wanted.add(id.strip());
            // A line kept for what it says of its own leaves the rule; the rule stands for the lines that remain.
            for (List<Cited> lines : sections.values()) {
                for (Cited l : lines) {
                    if (keep.contains(String.join(",", l.cites())) && wanted.containsAll(l.cites())) l.cites().forEach(wanted::remove);
                }
            }
            List<Cited> taken = new ArrayList<>();
            java.util.Set<String> covered = new java.util.LinkedHashSet<>();
            for (List<Cited> lines : sections.values()) {
                for (Cited l : lines) {
                    if (replacedBy.containsKey(l) || keep.contains(String.join(",", l.cites())) || !wanted.containsAll(l.cites())) continue;
                    taken.add(l);
                    covered.addAll(l.cites());
                }
            }
            if (taken.size() < 2 || !covered.equals(wanted)) continue;
            java.util.Set<Integer> statuses = new java.util.HashSet<>();
            for (String id : covered) if (statusOf.containsKey(id)) statuses.add(statusOf.get(id));
            if (statuses.size() > 1) continue;
            Cited rule = new Cited(m.group(1).strip(), List.copyOf(covered));
            List<String> through = new ArrayList<>();
            for (Cited l : taken) {
                replacedBy.put(l, rule);
                String use = useOfLine.get(l);
                if (!use.equals(OTHER) && !through.contains(use)) through.add(use);
            }
            rulesUses.put(rule, uses.stream().filter(through::contains).toList());
            if (rules != null) rules[0]++;
        }
        Map<String, List<Cited>> single = new LinkedHashMap<>();
        Map<String, List<Cited>> several = new LinkedHashMap<>();
        List<Cited> all = new ArrayList<>();
        List<Cited> other = new ArrayList<>();
        java.util.Set<Cited> placed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        sections.forEach((use, lines) -> {
            for (Cited l : lines) {
                Cited rule = replacedBy.get(l);
                if (rule == null) {
                    (use.equals(OTHER) ? other : single.computeIfAbsent(use, u -> new ArrayList<>())).add(l);
                    continue;
                }
                if (!placed.add(rule)) continue;
                List<String> through = rulesUses.get(rule);
                if (through.isEmpty()) other.add(rule);
                else if (through.size() == 1) single.computeIfAbsent(through.get(0), u -> new ArrayList<>()).add(rule);
                else if (through.size() == uses.size()) all.add(rule);
                else several.computeIfAbsent(String.join(", ", through), u -> new ArrayList<>()).add(rule);
            }
        });
        Map<String, List<Cited>> out = new LinkedHashMap<>();
        for (String use : uses) if (single.containsKey(use)) out.put(use, single.get(use));
        out.putAll(several);
        if (!all.isEmpty()) out.put(ALL, all);
        if (!other.isEmpty()) out.put(OTHER, other);
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
