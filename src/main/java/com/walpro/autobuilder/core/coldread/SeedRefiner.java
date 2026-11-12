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
 * <p><b>Stages 2 and 3 (one step, after the user's "describing things without naming the word"): one answer,
 * stated once.</b> Six lines on too few and too many arguments circle a rule nobody named. Which lines belong
 * together is not asked of a model: it is read from what the project was RECORDED to answer ({@link #answerOf} --
 * the status and the output, with the exercise's own values taken out). Lines whose claims were all answered
 * alike are one group ({@link #groups}); the model gives the group its name and says, per line, WHEN that answer
 * comes ({@link #groupPrompt}); lines given the same condition are put together ({@link #grouped}). So the answer
 * and its message are stated once and from the record, every condition a line stated is still there, and a line
 * never loses a claim. Which uses a group holds for is computed from its claims -- "all uses" only when they went
 * through every use.
 *
 * <p>Tried first and replaced (2026-11-12): asking the model which lines to merge. Unchecked it swallowed lines
 * that stated a case of their own; checked line by line it restored them, and with them lines that differed only
 * in a detail the composer happened to write for one and not another ("to stderr").
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
    record Cited(String text, List<String> cites, boolean nested) {
        Cited(String text, List<String> cites) {
            this(text, cites, false);
        }

        String render() {
            return (nested ? "  - " : "- ") + text + " [" + String.join(", ", cites) + "]";
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
            List<Group> groups = groups(account, worded);
            if (!groups.isEmpty()) {
                String answer = ask(groupPrompt(groups));
                log.debug("T4-EQ: groups asked --\n{}\nanswered --\n{}", groupPrompt(groups), answer);
                Named said = named(groups, answer == null ? "" : answer);
                // A condition given to several lines at once may say less than one of them did (a special input, how a
                // number comes about). Each line is set beside what now stands for it; one that is not stated in full
                // keeps its own words, as its own case.
                List<Cited> asked = new ArrayList<>();   // in the groups' own order: the verdicts are numbered by it
                for (Group g : groups) for (Cited l : g.lines()) if (said.conditionOf().containsKey(l)) asked.add(l);
                if (!asked.isEmpty()) {
                    String verdicts = ask(saidPrompt(groups, said, asked));
                    log.debug("T4-EQ: verdicts --\n{}", verdicts);
                    java.util.Set<Integer> yes = verdicts == null ? java.util.Set.of() : saidIn(verdicts);
                    Map<Integer, String> missing = verdicts == null ? Map.of() : missingIn(verdicts);
                    List<Cited> thin = new ArrayList<>();
                    List<String> why = new ArrayList<>();
                    for (int i = 0; i < asked.size(); i++) {
                        if (yes.contains(i + 1)) continue;
                        thin.add(asked.get(i));
                        why.add(missing.getOrDefault(i + 1, "something the line states"));
                    }
                    // A line whose condition was too thin is asked for once more, alone, with what was missing said;
                    // the new condition is checked like the first, and only a yes keeps it.
                    int back = thin.size();
                    if (!thin.isEmpty()) {
                        String again = ask(againPrompt(groups, said, thin, why));
                        log.debug("T4-EQ: conditions again --\n{}", again);
                        Map<Integer, String> fuller = again == null ? Map.of() : numbered(again);
                        List<Cited> retried = new ArrayList<>();
                        for (int i = 0; i < thin.size(); i++) {
                            String c = fuller.get(i + 1);
                            if (c == null || c.isBlank()) {
                                said.conditionOf().remove(thin.get(i));
                                continue;
                            }
                            said.conditionOf().put(thin.get(i), c.replaceFirst("(?i)^when\\s+", ""));
                            retried.add(thin.get(i));
                        }
                        if (!retried.isEmpty()) {
                            String second = ask(saidPrompt(groups, said, retried));
                            log.debug("T4-EQ: verdicts on them --\n{}", second);
                            java.util.Set<Integer> yes2 = second == null ? java.util.Set.of() : saidIn(second);
                            for (int i = 0; i < retried.size(); i++) {
                                if (yes2.contains(i + 1)) back--;
                                else said.conditionOf().remove(retried.get(i));
                            }
                        }
                        log.warn("T4-EQ: {} of {} line(s) said something their condition did not -- {} given a fuller condition, {} kept in their own words",
                                thin.size(), asked.size(), thin.size() - back, back);
                    }
                }
                named = grouped(worded, groups, said);
                log.warn("T4-EQ: {} answer(s) the project gives to several lines stated once -- {} line(s) under them, {} line(s) in all, {} before",
                        groups.size(), groups.stream().mapToInt(g -> g.lines().size()).sum(),
                        named.values().stream().mapToInt(List::size).sum(), worded.values().stream().mapToInt(List::size).sum());
            }
        } catch (RuntimeException e) {
            log.warn("T4-EQ: no answer was stated once; every line stays as it is -- {}", e.getMessage());
        }
        try {
            List<Marked> marked = marked(account, worded);
            if (!marked.isEmpty()) {
                String answer = ask(termPrompt(marked));
                log.debug("T4-EQ: terms asked --\n{}\nanswered --\n{}", termPrompt(marked), answer);
                int before = named.values().stream().mapToInt(List::size).sum();
                named = withTerms(worded, named, marked, answer == null ? "" : answer);
                log.warn("T4-EQ: {} set(s) of lines share something only their input has -- {} named as a rule of the input",
                        marked.size(), named.values().stream().mapToInt(List::size).sum() - before);
            }
        } catch (RuntimeException e) {
            log.warn("T4-EQ: no rule of the input was named -- {}", e.getMessage());
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
                the one check written as a placeholder ("no such file: <path>", not the path one check used). Where \
                the answer says which stream something was printed to, say it in those words (standard output, \
                standard error); where it does not, say where a message goes only if the line already says it.

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
            String stream = streamOf(o);
            return (j.claim().kind() == EntryKind.COMMAND_STYLE ? "exit " : "status ") + o.status() + ", "
                    + (stream == null ? "output" : "printed to " + stream) + ": " + body;
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
    // Stage 2 and 3: one answer, stated once. Which lines belong together is read from what the project was
    // RECORDED to answer; the model gives the group its name and says, per line, when that answer comes.
    // ------------------------------------------------------------------------------------------------------------

    /**
     * What the project answered, with what belongs to the one exercise taken out: the status, and the output's
     * distinct lines with the exercise's own argument and file names and every number written as placeholders.
     * Two claims with the same answer were answered alike, whatever words their lines use for it.
     */
    record Answer(int status, List<String> pattern, boolean command, String stream) {
        /** The answer in words, from the record alone. */
        String phrase() {
            String first = command ? "exits with status " + status : "answers with status " + status;
            if (pattern.isEmpty()) return first + (command ? " and prints nothing" : "");
            return first + (command ? " and prints " + (stream == null ? "" : "to " + stream + " ") : ", ")
                    + "\"" + String.join("\" / \"", pattern) + "\"";
        }
    }

    static final String OUT = "standard output", ERR = "standard error";

    /** Where a program run printed: one stream, both, or null when the streams were not read apart or nothing was printed. */
    static String streamOf(ReverseEngineeringService.Observation o) {
        if (o.error() == null || o.body() == null || o.body().isBlank()) return null;
        if (o.error().isBlank()) return OUT;
        return o.error().strip().equals(o.body().strip()) ? ERR : OUT + " and " + ERR;
    }

    static final String ARGUMENT = "<argument>", NUMBER = "<n>";

    /** The answer a claim was observed with on the tree as it is; null when none was recorded (a test). */
    static Answer answerOf(JudgedClaim j) {
        if (j.observations() == null) return null;
        ClaimProposer.Claim k = j.claim();
        for (ReverseEngineeringService.Observation o : j.observations()) {
            if (!"as-is".equals(o.variant()) || o.status() == null) continue;
            if (k.kind() == EntryKind.LIBRARY_STYLE) return null;
            List<String> own = new ArrayList<>();
            if (k.run() != null) {
                if (k.run().args() != null) own.addAll(k.run().args().subList(Math.min(1, k.run().args().size()), k.run().args().size()));
                if (k.run().files() != null) own.addAll(k.run().files().keySet());
            }
            own.removeIf(v -> v == null || v.length() < 2);
            own.sort((a, b) -> b.length() - a.length());
            List<String> pattern = new ArrayList<>();
            for (String line : (o.body() == null ? "" : o.body()).split("\\R")) {
                String l = line.strip();
                if (l.isEmpty()) continue;
                for (String v : own) l = l.replace(v, ARGUMENT);
                l = l.replaceAll("\\d+", NUMBER);
                if (!pattern.contains(l)) pattern.add(l);
            }
            return new Answer(o.status(), pattern, k.kind() == EntryKind.COMMAND_STYLE, streamOf(o));
        }
        return null;
    }

    /** Lines that all rest on one answer; {@code raw} is what each line's claims printed, for the cases a number tells apart. */
    record Group(Answer answer, List<Cited> lines, Map<Cited, String> useOf, Map<Cited, String> raw) {
    }

    /**
     * The groups of two or more lines whose claims were ALL answered alike, in the order their first line stands. A
     * line resting on claims answered differently belongs to none.
     */
    static List<Group> groups(Account account, Map<String, List<Cited>> sections) {
        Map<String, JudgedClaim> byId = new LinkedHashMap<>();
        account.claims().forEach(j -> byId.put(j.claim().id(), j));
        Map<Answer, Group> byAnswer = new LinkedHashMap<>();
        sections.forEach((use, lines) -> {
            for (Cited l : lines) {
                Answer common = null;
                boolean one = true;
                java.util.Set<String> printed = new java.util.LinkedHashSet<>();
                for (String id : l.cites()) {
                    JudgedClaim j = byId.get(id);
                    Answer a = j == null ? null : answerOf(j);
                    if (a == null || (common != null && !common.equals(a))) {
                        one = false;
                        break;
                    }
                    common = a;
                    for (ReverseEngineeringService.Observation o : j.observations()) {
                        if ("as-is".equals(o.variant()) && o.body() != null && !o.body().isBlank()) {
                            printed.add(o.body().strip().replace("\n", " / "));
                        }
                    }
                }
                if (!one || common == null) continue;
                Group g = byAnswer.computeIfAbsent(common, a -> new Group(a, new ArrayList<>(),
                        new java.util.IdentityHashMap<>(), new java.util.IdentityHashMap<>()));
                g.lines().add(l);
                g.useOf().put(l, use);
                g.raw().put(l, String.join(" ; ", printed));
            }
        });
        return byAnswer.values().stream().filter(g -> g.lines().size() >= 2).toList();
    }

    static String groupPrompt(List<Group> groups) {
        StringBuilder sb = new StringBuilder("""
                Below are groups of lines from a specification of an existing project. Every line of a group ends \
                in the SAME answer of the project; that answer was recorded and is given above the group. What \
                differs between the lines of a group is only WHEN the answer comes.

                For each group write three things.
                1. Its name: the word, or the few plain words, a person would use for this rule.
                2. The answer as a phrase: the same status and the same words of the message as recorded; you may \
                only rename a placeholder (<argument>, <n>) to say what it stands for.
                3. For each line, its condition alone: WHEN this answer comes, as short as it can be said -- not the \
                use (which command, request or call), and not the answer. Where two lines state the same condition, \
                use exactly the same words for both; where they differ in anything, say what differs. If a number \
                in the answer depends on the condition (a count, a position), say how.

                Answer in exactly this form, nothing else:
                GROUP <number>
                name: <the name>
                answer: <the answer as a phrase>
                - <condition> [<the ids of that line, exactly>]
                - ...

                """);
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            sb.append("GROUP ").append(i + 1).append("\nrecorded answer: ").append(g.answer().phrase()).append('\n');
            for (Cited l : g.lines()) {
                sb.append("- (").append(g.useOf().get(l)).append(") ").append(l.text()).append(" [")
                        .append(String.join(", ", l.cites())).append("]");
                String raw = g.raw().get(l);
                if (raw != null && !raw.isBlank() && g.answer().pattern().toString().contains(NUMBER)) {
                    sb.append("   printed here: ").append(cut(raw));
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern GROUP = java.util.regex.Pattern.compile("^GROUP\\s+(\\d+)\\s*:?\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern FIELD = java.util.regex.Pattern.compile("^(name|answer)\\s*:\\s*(.+)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Whether a phrase the model wrote for an answer still carries the record: the status and the message's own words, in order. */
    static boolean carries(String phrase, Answer answer) {
        if (phrase == null || !phrase.matches(".*\\b" + answer.status() + "\\b.*")) return false;
        if (answer.stream() != null && !answer.pattern().isEmpty() && !phrase.contains(answer.stream())) return false;
        String rest = phrase;
        for (String line : answer.pattern()) {
            for (String literal : line.split("<[^>]*>")) {
                String lit = literal.strip();
                if (lit.isEmpty()) continue;
                int at = rest.indexOf(lit);
                if (at < 0) return false;
                rest = rest.substring(at + lit.length());
            }
        }
        return true;
    }

    private static String key(String condition) {
        return condition.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9<>]+", " ").strip();
    }

    /**
     * The sections with each group stated once: a line naming the group and its answer, citing every claim of the
     * group, and under it one line per CONDITION -- lines given the same condition put together. A group with one
     * condition is a single line. The answer is the record's ({@link Answer#phrase}) unless the model's phrase
     * still {@link #carries} it; a condition is the model's when it came back with exactly a line's ids, else the
     * line as it was. A group stands under the use its lines stood under, a heading naming the uses when several,
     * {@link #ALL} when every use that has a section; a condition shown for only some of a group's uses says which.
     */
    static Map<String, List<Cited>> grouped(Map<String, List<Cited>> sections, List<Group> groups, String answer) {
        return grouped(sections, groups, named(groups, answer));
    }

    /** What the model said of the groups: per group its name and its phrase for the answer, per line its condition. */
    record Named(Map<Integer, String> names, Map<Integer, String> phrases, Map<Cited, String> conditionOf) {
    }

    /**
     * The model's answer read: a condition is a line's when it came back with exactly that line's ids, or with the
     * ids of several whole lines of the group -- the model saying they state one condition.
     */
    static Named named(List<Group> groups, String answer) {
        Map<Integer, String> names = new LinkedHashMap<>(), phrases = new LinkedHashMap<>();
        Map<Cited, String> conditionOf = new java.util.IdentityHashMap<>();
        int current = 0;
        for (String raw : answer.split("\\R")) {
            String l = raw.strip();
            Matcher g = GROUP.matcher(l);
            if (g.matches()) {
                current = Integer.parseInt(g.group(1));
                continue;
            }
            if (current < 1 || current > groups.size()) continue;
            Matcher f = FIELD.matcher(l);
            if (f.matches()) {
                (f.group(1).equalsIgnoreCase("name") ? names : phrases).putIfAbsent(current, f.group(2).strip());
                continue;
            }
            Matcher c = SeedComposer.CITED.matcher(l);
            if (!c.matches() || c.group(1).isBlank()) continue;
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            for (String id : c.group(2).split(",")) ids.add(id.strip());
            List<Cited> whole = new ArrayList<>();
            java.util.Set<String> covered = new java.util.LinkedHashSet<>();
            for (Cited line : groups.get(current - 1).lines()) {
                if (conditionOf.containsKey(line) || !ids.containsAll(line.cites())) continue;
                whole.add(line);
                covered.addAll(line.cites());
            }
            if (whole.isEmpty() || !covered.equals(ids)) continue;
            String text = c.group(1).strip().replaceFirst("(?i)^when\\s+", "");
            whole.forEach(line -> conditionOf.put(line, text));
        }
        return new Named(names, phrases, conditionOf);
    }

    static String saidPrompt(List<Group> groups, Named named, List<Cited> asked) {
        StringBuilder sb = new StringBuilder("""
                Each numbered pair below is a RULE of a specification and one LINE the rule is meant to stand for. \
                For each pair decide: does the rule state everything the line states? The use the line names (which \
                command, request or call) does not count -- that is kept elsewhere. Everything else does: a special \
                input, what exactly is printed for it, in which order, how a number in the answer comes about.

                Answer one line per pair, nothing else:
                <number>: yes
                or
                <number>: no -- <what the line states that the rule does not>

                """);
        for (int i = 0; i < asked.size(); i++) {
            Cited line = asked.get(i);
            int gi = 0;
            for (int g = 0; g < groups.size(); g++) if (groups.get(g).lines().stream().anyMatch(l -> l == line)) gi = g;
            Answer a = groups.get(gi).answer();
            String phrase = carries(named.phrases().get(gi + 1), a) ? named.phrases().get(gi + 1) : a.phrase();
            sb.append(i + 1).append(".\n  RULE: ").append(phrase).append(" -- when ").append(named.conditionOf().get(line))
                    .append("\n  LINE: ").append(line.text()).append("\n");
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern SAID = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*[:.)]\\s*yes\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern MISSING = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*[:.)]\\s*no\\b\\W*(.*)$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern NUMBERED = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*[:.)]\\s*(.+)$");

    /** What each pair answered no was said to lack, by its number. */
    static Map<Integer, String> missingIn(String verdicts) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (String raw : verdicts.split("\\R")) {
            Matcher m = MISSING.matcher(raw);
            if (m.find() && !m.group(2).isBlank()) out.put(Integer.parseInt(m.group(1)), m.group(2).strip());
        }
        return out;
    }

    /** The lines of an answer that start with a number, by it. */
    static Map<Integer, String> numbered(String answer) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (String raw : answer.split("\\R")) {
            Matcher m = NUMBERED.matcher(raw);
            if (m.find()) out.putIfAbsent(Integer.parseInt(m.group(1)), m.group(2).strip());
        }
        return out;
    }

    static String againPrompt(List<Group> groups, Named named, List<Cited> thin, List<String> why) {
        StringBuilder sb = new StringBuilder("""
                Each numbered item below is one LINE of a specification, the ANSWER of the project it ends in (which \
                is stated once, elsewhere, for several lines), and a CONDITION that was written for the line and \
                found to leave something out.

                Write each condition again: WHEN the answer comes, and whatever the line states about the answer in \
                this case that the answer alone does not say (which line number is reported, in which order, what is \
                left out) -- so that the answer and your condition together state everything the line states. Not \
                the use (which command, request or call), and do not repeat the answer's own words.

                Answer one line per item, nothing else:
                <number>: <the condition>

                """);
        for (int i = 0; i < thin.size(); i++) {
            Cited line = thin.get(i);
            int gi = 0;
            for (int g = 0; g < groups.size(); g++) if (groups.get(g).lines().stream().anyMatch(l -> l == line)) gi = g;
            Answer a = groups.get(gi).answer();
            String phrase = carries(named.phrases().get(gi + 1), a) ? named.phrases().get(gi + 1) : a.phrase();
            sb.append(i + 1).append(".\n  LINE: ").append(line.text()).append("\n  ANSWER: ").append(phrase)
                    .append("\n  CONDITION: ").append(named.conditionOf().get(line))
                    .append("\n  LEFT OUT: ").append(why.get(i)).append("\n");
        }
        return sb.toString();
    }

    /** The pairs answered yes; a pair with any other answer, or none, counts as not said. */
    static java.util.Set<Integer> saidIn(String verdicts) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (String raw : verdicts.split("\\R")) {
            Matcher m = SAID.matcher(raw);
            if (m.find()) out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    static Map<String, List<Cited>> grouped(Map<String, List<Cited>> sections, List<Group> groups, Named named) {
        Map<Integer, String> names = named.names(), phrases = named.phrases();
        List<String> uses = sections.keySet().stream().filter(u -> !u.equals(OTHER)).toList();
        Map<Cited, Integer> groupOf = new java.util.IdentityHashMap<>();
        for (int i = 0; i < groups.size(); i++) for (Cited l : groups.get(i).lines()) groupOf.put(l, i);
        Map<Integer, List<Cited>> blocks = new LinkedHashMap<>();
        Map<Integer, List<String>> through = new LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            List<String> groupUses = uses.stream().filter(u -> g.useOf().containsValue(u)).toList();
            through.put(i, groupUses);
            // conditions, lines given the same one put together, in the order of their first line
            Map<String, List<Cited>> same = new LinkedHashMap<>();
            Map<String, String> words = new LinkedHashMap<>();
            for (Cited l : g.lines()) {
                String cond = named.conditionOf().get(l);
                String text = cond == null ? l.text() : cond;
                String k = cond == null ? "line:" + System.identityHashCode(l) : key(text);
                same.computeIfAbsent(k, x -> new ArrayList<>()).add(l);
                words.putIfAbsent(k, text);
            }
            String name = names.get(i + 1);
            String phrase = carries(phrases.get(i + 1), g.answer()) ? phrases.get(i + 1).replaceFirst("[.\\s]+$", "") : g.answer().phrase();
            String head = (name == null || name.isBlank() ? "" : name.replaceFirst("[.:\\s]+$", "") + ": ") + phrase;
            List<String> all = g.lines().stream().flatMap(l -> l.cites().stream()).distinct().toList();
            List<Cited> block = new ArrayList<>();
            boolean numbered = g.answer().pattern().toString().contains(NUMBER);
            List<Cited> cases = new ArrayList<>();
            for (Map.Entry<String, List<Cited>> e : same.entrySet()) {
                List<String> ids = e.getValue().stream().flatMap(l -> l.cites().stream()).distinct().toList();
                List<String> shownFor = uses.stream().filter(u -> e.getValue().stream().anyMatch(l -> u.equals(g.useOf().get(l)))).toList();
                String text = words.get(e.getKey());
                if (groupUses.size() > 1 && shownFor.size() < groupUses.size()) text = "(" + String.join(", ", shownFor) + ") " + text;
                if (numbered) {
                    java.util.Set<String> printed = new java.util.LinkedHashSet<>();
                    e.getValue().forEach(l -> { if (g.raw().get(l) != null && !g.raw().get(l).isBlank()) printed.add(g.raw().get(l)); });
                    if (!printed.isEmpty()) text += " -- printed: " + cut(String.join(" ; ", printed));
                }
                cases.add(new Cited(text, ids, true));
            }
            if (cases.size() == 1) {
                block.add(new Cited(head + " -- when " + cases.get(0).text(), all));
            } else {
                block.add(new Cited(head + ", in each of these cases", all));
                block.addAll(cases);
            }
            blocks.put(i, block);
        }
        Map<String, List<Cited>> single = new LinkedHashMap<>(), several = new LinkedHashMap<>();
        List<Cited> allUses = new ArrayList<>(), other = new ArrayList<>();
        java.util.Set<Integer> placed = new java.util.HashSet<>();
        sections.forEach((use, lines) -> {
            for (Cited l : lines) {
                Integer gi = groupOf.get(l);
                if (gi == null) {
                    (use.equals(OTHER) ? other : single.computeIfAbsent(use, u -> new ArrayList<>())).add(l);
                    continue;
                }
                if (!placed.add(gi)) continue;
                List<String> t = through.get(gi);
                List<Cited> target = t.isEmpty() ? other
                        : t.size() == 1 ? single.computeIfAbsent(t.get(0), u -> new ArrayList<>())
                        : t.size() == uses.size() ? allUses
                        : several.computeIfAbsent(String.join(", ", t), u -> new ArrayList<>());
                target.addAll(blocks.get(gi));
            }
        });
        Map<String, List<Cited>> out = new LinkedHashMap<>();
        for (String use : uses) if (single.containsKey(use)) out.put(use, single.get(use));
        out.putAll(several);
        if (!allUses.isEmpty()) out.put(ALL, allUses);
        if (!other.isEmpty()) out.put(OTHER, other);
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------
    // A rule of the INPUT: lines that do not end in one answer and still belong together, because their inputs
    // share something no other line's input has (a quote character, say). Which lines: read from the inputs. What
    // they show together, and its name: the model's, added ABOVE the lines -- none of them is replaced.
    // ------------------------------------------------------------------------------------------------------------

    /** Lines whose claims' inputs are the only ones to contain {@code symbols}. */
    record Marked(String symbols, List<Cited> lines, List<String> uses) {
    }

    /** What a claim handed the project to read: its files' contents and its requests' bodies. */
    static String inputOf(ClaimProposer.Claim k) {
        StringBuilder sb = new StringBuilder();
        if (k.run() != null && k.run().files() != null) k.run().files().values().forEach(v -> sb.append(v).append('\n'));
        if (k.sequence() != null) k.sequence().forEach(r -> { if (r.body() != null) sb.append(r.body()).append('\n'); });
        return sb.toString();
    }

    /**
     * The sets of two or more lines -- at most a third of all -- that some symbol of the inputs picks out: every
     * claim of those lines was handed it, no claim of any other line was. Symbols picking out the same lines are one
     * set.
     */
    static List<Marked> marked(Account account, Map<String, List<Cited>> sections) {
        Map<String, JudgedClaim> byId = new LinkedHashMap<>();
        account.claims().forEach(j -> byId.put(j.claim().id(), j));
        List<Cited> all = sections.values().stream().flatMap(List::stream).toList();
        Map<Cited, String> useOfLine = new java.util.IdentityHashMap<>();
        sections.forEach((use, lines) -> lines.forEach(l -> useOfLine.put(l, use)));
        Map<Cited, java.util.Set<Character>> inAll = new java.util.IdentityHashMap<>(), inAny = new java.util.IdentityHashMap<>();
        java.util.Set<Character> symbols = new java.util.TreeSet<>();
        for (Cited l : all) {
            java.util.Set<Character> every = null, any = new java.util.HashSet<>();
            for (String id : l.cites()) {
                JudgedClaim j = byId.get(id);
                java.util.Set<Character> own = new java.util.HashSet<>();
                if (j != null) for (char c : inputOf(j.claim()).toCharArray()) if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) own.add(c);
                any.addAll(own);
                if (every == null) every = own;
                else every.retainAll(own);
            }
            inAll.put(l, every == null ? java.util.Set.of() : every);
            inAny.put(l, any);
            symbols.addAll(any);
        }
        Map<List<Cited>, StringBuilder> bySet = new LinkedHashMap<>();
        for (char c : symbols) {
            List<Cited> with = all.stream().filter(l -> inAll.get(l).contains(c)).toList();
            boolean elsewhere = all.stream().anyMatch(l -> !inAll.get(l).contains(c) && inAny.get(l).contains(c));
            if (elsewhere || with.size() < 2 || with.size() * 3 > all.size()) continue;
            bySet.computeIfAbsent(with, w -> new StringBuilder()).append(c);
        }
        List<String> uses = sections.keySet().stream().filter(u -> !u.equals(OTHER)).toList();
        List<Marked> out = new ArrayList<>();
        bySet.forEach((lines, chars) -> out.add(new Marked(chars.toString(), lines,
                uses.stream().filter(u -> lines.stream().anyMatch(l -> u.equals(useOfLine.get(l)))).toList())));
        return out;
    }

    static String termPrompt(List<Marked> marked) {
        StringBuilder sb = new StringBuilder("""
                Below are sets of lines from a specification of an existing project. The lines of a set are the \
                only lines whose INPUT contains the characters named above the set.

                For each set decide: do its lines together show one rule about how the input is READ -- a piece of \
                the input's syntax a person would define once, before the lines that depend on it? If so, give it \
                its name and state it in one or two sentences, saying only what these lines show, with one short \
                example taken from them. If the characters are incidental to what the lines say, answer NONE.

                Answer in exactly this form, nothing else:
                SET <number>
                name: <the name>     (or: NONE)
                rule: <the rule>

                """);
        for (int i = 0; i < marked.size(); i++) {
            sb.append("SET ").append(i + 1).append("\nonly these lines' input contains: ").append(marked.get(i).symbols()).append('\n');
            marked.get(i).lines().forEach(l -> sb.append("- ").append(l.text()).append('\n'));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern SET = java.util.regex.Pattern.compile("^SET\\s+(\\d+)\\s*:?\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TERM = java.util.regex.Pattern.compile("^(name|rule)\\s*:\\s*(.+)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * {@code named} with one line added per set the model gave a name and a rule: it cites every claim of the set's
     * lines and stands first under the uses those lines went through. Nothing is taken away.
     */
    static Map<String, List<Cited>> withTerms(Map<String, List<Cited>> sections, Map<String, List<Cited>> named,
                                              List<Marked> marked, String answer) {
        Map<Integer, String> names = new LinkedHashMap<>(), rules = new LinkedHashMap<>();
        int current = 0;
        for (String raw : answer.split("\\R")) {
            String l = raw.strip();
            Matcher st = SET.matcher(l);
            if (st.matches()) {
                current = Integer.parseInt(st.group(1));
                continue;
            }
            Matcher t = TERM.matcher(l);
            if (current < 1 || current > marked.size() || !t.matches()) continue;
            (t.group(1).equalsIgnoreCase("name") ? names : rules).putIfAbsent(current, t.group(2).strip());
        }
        List<String> uses = sections.keySet().stream().filter(u -> !u.equals(OTHER)).toList();
        Map<String, List<Cited>> out = new LinkedHashMap<>();
        named.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        for (int i = 0; i < marked.size(); i++) {
            String name = names.get(i + 1), rule = rules.get(i + 1);
            if (name == null || rule == null || name.toUpperCase(java.util.Locale.ROOT).startsWith("NONE") || rule.isBlank()) continue;
            Marked m = marked.get(i);
            List<String> ids = m.lines().stream().flatMap(l -> l.cites().stream()).distinct().toList();
            String heading = m.uses().isEmpty() ? OTHER : m.uses().size() == 1 ? m.uses().get(0)
                    : m.uses().size() == uses.size() ? ALL : String.join(", ", m.uses());
            Cited term = new Cited(name.replaceFirst("[.:\\s]+$", "") + ": " + rule, ids);
            if (out.containsKey(heading)) {
                out.get(heading).add(0, term);
                continue;
            }
            // a heading of its own goes before the first of the gathering headings, after the uses themselves
            Map<String, List<Cited>> again = new LinkedHashMap<>();
            boolean put = false;
            for (Map.Entry<String, List<Cited>> e : out.entrySet()) {
                if (!put && !uses.contains(e.getKey())) {
                    again.put(heading, new ArrayList<>(List.of(term)));
                    put = true;
                }
                again.put(e.getKey(), e.getValue());
            }
            if (!put) again.put(heading, new ArrayList<>(List.of(term)));
            out.clear();
            out.putAll(again);
        }
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
