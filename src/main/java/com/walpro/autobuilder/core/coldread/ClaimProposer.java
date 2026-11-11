package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;
import com.walpro.autobuilder.core.coldread.entry.EntryKind;
import com.walpro.autobuilder.core.coldread.entry.EntryPoint;
import com.walpro.autobuilder.core.investigate.LogInvestigator;
import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;
import com.walpro.autobuilder.core.merge.CapabilityCut.Declaration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EK: what one capability of a project the loop did not build can be shown to do, proposed by a model reading its
 * code -- as claims an exercise can check, never as verdicts.
 *
 * <p>The model's answer is a guess about the code; nothing here takes it as true. Every claim carries the exercise that
 * checks it -- requests to a running service, a run of a command, or a test that calls the code -- and
 * {@link ReverseEngineeringService} keeps only the claims the project bears out AND the project without the claim's
 * feature does not. The question says so, so the model knows a wrong guess costs nothing and a claim about what the
 * platform does on its own will not survive.
 */
@Slf4j
@Component
public class ClaimProposer {

    static final int PROBES = 10;
    static final int MAX_CLAIMS = 12;
    /**
     * Twelve, not the six a criterion's probe may take: a rule about a limit needs the limit reached first. "A fourth
     * loan is refused" is nine requests (four books, a member, four loans), and at six all four such claims of the
     * first run were thrown away as malformed.
     */
    static final int MAX_REQUESTS = 12;
    /** How much of the capability's own source is shown up front, so probes go to what it uses rather than to it. */
    static final int SOURCE_BUDGET_CHARS = 24_000;
    static final int MAX_ENTRY_POINTS_LISTED = 60;

    private static final Pattern CLAIM = Pattern.compile("(?mi)^\\s*CLAIM:\\s*(.+?)\\s*$");
    private static final Pattern SEND = Pattern.compile("(?mi)^\\s*SEND:\\s*(GET|POST|PUT|PATCH|DELETE)\\s+(\\S+)[ \\t]*(.*?)\\s*$");
    private static final Pattern EXPECT_STATUS = Pattern.compile("(?mi)^\\s*EXPECT:\\s*(\\d{3})\\b");
    private static final Pattern TEST = Pattern.compile("(?mi)^\\s*TEST:\\s*$");
    private static final Pattern RUN = Pattern.compile("(?mi)^\\s*RUN:[ \\t]*(.*?)\\s*$");
    private static final Pattern EXPECT_EXIT = Pattern.compile("(?mi)^\\s*EXPECT:\\s*(?:exit\\s*)?(-?\\d{1,3})\\b");
    private static final Pattern OUTPUT = Pattern.compile("(?mi)^\\s*OUTPUT:[ \\t]*(.+?)\\s*$");
    private static final Pattern FILE = Pattern.compile("(?mi)^\\s*FILE:\\s*(\\S+)\\s*$");
    private static final Pattern DIRECTIVE = Pattern.compile("(?i)^\\s*(FILE|RUN|EXPECT|OUTPUT|CLAIM|SEND|TEST|END)\\b");

    private final LogInvestigator investigator;

    public ClaimProposer(LogInvestigator investigator) {
        this.investigator = investigator;
    }

    /**
     * One claim: the sentence, its kind, and the exercise that checks it.
     *
     * @param sequence service-style: the requests (the last is the one asserted; {@code $N.field} is a field of
     *                 response N), with {@code expected} the status it states
     * @param expected service-style: the status; command-style: the exit status; library-style: unused
     * @param test     library-style: the body of a test method that passes when the claim holds
     * @param run      command-style: the run
     */
    public record Claim(String id, String capability, String text, EntryKind kind, List<ProbeRequest> sequence, int expected,
                        String test, CommandRun run) {

        /** A service-style claim, as slice (a) wrote them. */
        public Claim(String id, String capability, String text, List<ProbeRequest> sequence, int expected) {
            this(id, capability, text, EntryKind.SERVICE_STYLE, sequence, expected, null, null);
        }
    }

    /**
     * A command-style exercise: the files written beside it first (path -> content), the arguments, and what its output
     * must contain besides the exit status ({@code null}: the exit status alone).
     */
    public record CommandRun(Map<String, String> files, List<String> args, String outputContains) {
    }

    /** One way a tree is used: its entry points of that kind, and how a claim of that kind is written. */
    public record Way(List<EntryPoint> points, String form) {
    }

    /** What the model proposed for one capability, and every probe it made on the way, so a reader can check it. */
    public record Proposal(String capability, List<Claim> claims, List<String> malformed, List<String> probes, String answer) {
    }

    /**
     * @param ways        the tree's ways in, by kind, each with how a claim of that kind is written
     * @param firstNumber the number the first claim is numbered from; claims are numbered across capabilities so an id
     *                    names one claim in the whole account, and {@code {u}} becomes a value unique to its claim
     */
    public Proposal propose(Path code, Map<String, String> source, Map<String, Declaration> inventory, Capability capability,
                            List<Capability> all, ColdRead coldRead, Map<EntryKind, Way> ways, int firstNumber) {
        return propose(code, source, inventory, capability, all, coldRead, ways, firstNumber, null);
    }

    /**
     * @param startsWith T4-EO: what is known of the project as an exercise meets it -- what exists each time it is
     *                   started (what its services are filled with, or that they are empty) and what it was observed to
     *                   answer before any use -- in words, each part under its own heading; null when nothing is known
     */
    public Proposal propose(Path code, Map<String, String> source, Map<String, Declaration> inventory, Capability capability,
                            List<Capability> all, ColdRead coldRead, Map<EntryKind, Way> ways, int firstNumber,
                            String startsWith) {
        String question = question(source, inventory, capability, all, coldRead, ways, startsWith);
        LogInvestigator.Investigation inv = investigator.investigate(question, Map.of(), code, PROBES, "reverse-engineering");
        List<String> probes = inv.steps().stream().map(LogInvestigator.Step::probe).toList();
        if (!inv.answered()) {
            log.warn("T4-EK: no claims for capability {} after {} probe(s)", capability.name(), probes.size());
            return new Proposal(capability.id(), List.of(), List.of(), probes, null);
        }
        Proposal p = parse(inv.answer(), capability.id(), firstNumber, probes, ways.keySet());
        log.warn("T4-EK: capability {} -- {} claim(s) proposed, {} malformed, {} probe(s)", capability.name(),
                p.claims().size(), p.malformed().size(), probes.size());
        return p;
    }

    static String question(Map<String, String> source, Map<String, Declaration> inventory, Capability capability,
                           List<Capability> all, ColdRead coldRead, Map<EntryKind, Way> ways) {
        return question(source, inventory, capability, all, coldRead, ways, null);
    }

    static String question(Map<String, String> source, Map<String, Declaration> inventory, Capability capability,
                           List<Capability> all, ColdRead coldRead, Map<EntryKind, Way> ways, String startsWith) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                This project was not written by you, and it comes with no description of what it should do. It has \
                been cut into capabilities. Find out what ONE of them -- named below -- does when the project is used \
                from outside, and state it as claims an exercise can check.

                Each claim is checked by exercising the project the way it is used. It is kept only when the project \
                behaves as the claim states, AND no longer does once the code the exercise reaches is taken out (its \
                methods made to throw, everything else unchanged). So state what THIS project's code does -- what it \
                accepts, what it refuses and how, what it returns or answers, what it does when something is missing \
                or a rule is broken -- not what the language or a framework does on its own. A claim that turns out \
                wrong costs nothing: it is simply dropped.
                """);
        sb.append("\nTHE CAPABILITY: ").append(capability.name()).append(" -- ").append(capability.summary()).append('\n');
        sb.append("Its declarations: ").append(String.join(", ", capability.declarations())).append('\n');
        List<String> others = all.stream().filter(c -> !c.id().equals(capability.id()))
                .map(c -> c.name() + " -- " + c.summary()).toList();
        if (!others.isEmpty()) {
            sb.append("\nTHE OTHER CAPABILITIES (claims about them belong to them, not here):\n");
            others.forEach(o -> sb.append("- ").append(o).append('\n'));
        }
        for (Map.Entry<EntryKind, Way> w : ways.entrySet()) {
            EntryKind kind = w.getKey();
            List<EntryPoint> points = w.getValue().points();
            sb.append("\nHOW THIS PROJECT IS USED: ").append(kind.label()).append(" -- ").append(kind.definition())
                    .append(".\nIts ways in:\n");
            points.stream().limit(MAX_ENTRY_POINTS_LISTED).forEach(e -> sb.append("  ").append(e.label()).append('\n'));
            if (points.size() > MAX_ENTRY_POINTS_LISTED) {
                sb.append("  (and ").append(points.size() - MAX_ENTRY_POINTS_LISTED).append(" more)\n");
            }
            sb.append("A claim of this kind is written:\n").append(w.getValue().form().strip()).append('\n');
            String contextPath = coldRead == null || coldRead.runtime() == null ? null : coldRead.runtime().contextPath();
            if (kind == EntryKind.SERVICE_STYLE && contextPath != null && !contextPath.isBlank() && !contextPath.equals("/")) {
                sb.append("(every path sits under ").append(contextPath)
                        .append(" -- write paths WITHOUT it; it is added when the request is sent)\n");
            }
        }
        if (startsWith != null && !startsWith.isBlank()) {
            sb.append('\n').append(startsWith.strip()).append('\n');
        }
        if (coldRead != null && !coldRead.prose().isEmpty()) {
            sb.append("\nWHAT THE PROJECT SAYS ABOUT ITSELF, quoted:\n");
            coldRead.prose().forEach(l -> sb.append("  ").append(l).append('\n'));
        }
        Set<String> files = new LinkedHashSet<>();
        for (String id : capability.declarations()) {
            Declaration d = inventory.get(id);
            if (d != null) files.add(d.path());
        }
        StringBuilder code = new StringBuilder();
        for (String f : files) {
            String text = source.get(f);
            if (text == null || code.length() + text.length() > SOURCE_BUDGET_CHARS) continue;
            code.append("--- ").append(f).append('\n').append(text.strip()).append('\n');
        }
        if (!code.isEmpty()) {
            sb.append("\nTHE CAPABILITY'S OWN FILES (probe the others it uses):\n").append(code);
        }
        sb.append("""

                Answer with up to %d claims, each starting with a line CLAIM: <one sentence> and followed by its \
                exercise in the form above. {u} stands for a seven-digit number unique to the claim -- use it where a \
                value must not repeat across claims (all claims of a kind are exercised, in order, on one copy of the \
                project). What exists when the project starts is there for every claim after yours: a claim that \
                changes or removes something creates its own first, and leaves what it found as it was.
                """.formatted(MAX_CLAIMS));
        return sb.toString();
    }

    /** Parses every kind; a claim of a kind not in {@code kinds} is malformed (the tree has no such way in). */
    static Proposal parse(String answer, String capability, int firstNumber, List<String> probes, Set<EntryKind> kinds) {
        List<Claim> claims = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        Matcher m = CLAIM.matcher(answer);
        List<int[]> starts = new ArrayList<>();
        while (m.find()) starts.add(new int[]{m.start(), m.end()});
        for (int i = 0; i < starts.size() && claims.size() < MAX_CLAIMS; i++) {
            String text = answer.substring(starts.get(i)[0], starts.get(i)[1]).replaceFirst("(?i)^\\s*CLAIM:\\s*", "")
                    .replaceAll("^[\"']|[\"']$", "").strip();
            String body = answer.substring(starts.get(i)[1], i + 1 < starts.size() ? starts.get(i + 1)[0] : answer.length());
            String id = "k" + (firstNumber + claims.size());
            String unique = String.valueOf(1_000_000 + firstNumber + claims.size());
            Claim c = TEST.matcher(body).find() ? library(id, capability, text, body, unique)
                    : RUN.matcher(body).find() ? command(id, capability, text, body, unique)
                    : service(id, capability, text, body, unique);
            if (c == null || !kinds.contains(c.kind())) {
                malformed.add(text);
                continue;
            }
            claims.add(c);
        }
        return new Proposal(capability, claims, malformed, probes, answer);
    }

    /** As above, for a tree whose only way in is service-style (slice (a)'s form). */
    static Proposal parse(String answer, String capability, int firstNumber, List<String> probes) {
        return parse(answer, capability, firstNumber, probes, Set.of(EntryKind.SERVICE_STYLE));
    }

    private static Claim service(String id, String capability, String text, String body, String unique) {
        List<ProbeRequest> sequence = new ArrayList<>();
        Matcher s = SEND.matcher(body);
        while (s.find()) {
            String b = s.group(3).isBlank() ? null : s.group(3).strip().replace("{u}", unique);
            sequence.add(new ProbeRequest(s.group(1).toUpperCase(Locale.ROOT), s.group(2).replace("{u}", unique), b));
        }
        Matcher e = EXPECT_STATUS.matcher(body);
        if (sequence.isEmpty() || sequence.size() > MAX_REQUESTS || !e.find()) return null;
        return new Claim(id, capability, text, EntryKind.SERVICE_STYLE, List.copyOf(sequence), Integer.parseInt(e.group(1)),
                null, null);
    }

    /** The lines after {@code TEST:} up to an {@code END} line (or the block's end), code fences dropped. */
    private static Claim library(String id, String capability, String text, String body, String unique) {
        Matcher t = TEST.matcher(body);
        if (!t.find()) return null;
        StringBuilder code = new StringBuilder();
        for (String line : body.substring(t.end()).split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.equalsIgnoreCase("END")) break;
            if (trimmed.startsWith("```")) continue;
            code.append(line).append('\n');
        }
        String test = code.toString().strip().replace("{u}", unique);
        return test.isEmpty() ? null : new Claim(id, capability, text, EntryKind.LIBRARY_STYLE, List.of(), 0, test, null);
    }

    /** {@code FILE:} name with {@code | } content lines, {@code RUN:} arguments, {@code EXPECT:} exit, optional {@code OUTPUT:}. */
    private static Claim command(String id, String capability, String text, String body, String unique) {
        Map<String, String> files = new LinkedHashMap<>();
        String[] lines = body.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher f = FILE.matcher(lines[i]);
            if (!f.find()) continue;
            // The content runs to the next directive line, its "| " taken off where it has one. Ending it at the first
            // line WITHOUT "| " cut files short: a value continued on the next line was written without it, and every
            // such file of the first command-style run lost its second half (an unclosed quote in place of a two-line row).
            List<String> content = new ArrayList<>();
            int j = i + 1;
            for (; j < lines.length; j++) {
                if (DIRECTIVE.matcher(lines[j]).find()) break;
                String l = lines[j].stripLeading();
                content.add(l.startsWith("|") ? (l.length() > 1 && l.charAt(1) == ' ' ? l.substring(2) : l.substring(1)) : lines[j]);
            }
            while (!content.isEmpty() && content.get(content.size() - 1).isBlank()
                    && !lines[i + content.size()].stripLeading().startsWith("|")) {
                content.remove(content.size() - 1);   // blank lines before the next directive are layout, not content
            }
            String written = content.isEmpty() ? "" : String.join("\n", content) + "\n";
            files.put(f.group(1).replace("{u}", unique), written.replace("{u}", unique));
            i = j - 1;
        }
        Matcher r = RUN.matcher(body);
        Matcher e = EXPECT_EXIT.matcher(body);
        if (!r.find() || !e.find()) return null;
        Matcher o = OUTPUT.matcher(body);
        String output = o.find() ? o.group(1).replaceAll("^[\"']|[\"']$", "").replace("{u}", unique) : null;
        List<String> args = arguments(r.group(1).replace("{u}", unique));
        return new Claim(id, capability, text, EntryKind.COMMAND_STYLE, List.of(), Integer.parseInt(e.group(1)), null,
                new CommandRun(files, args, output));
    }

    /** Space-separated, with "double quotes" keeping spaces inside one argument. */
    static List<String> arguments(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any) out.add(cur.toString());
        return out;
    }
}
