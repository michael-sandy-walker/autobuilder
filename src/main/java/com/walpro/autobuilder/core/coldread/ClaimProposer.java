package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;
import com.walpro.autobuilder.core.investigate.LogInvestigator;
import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;
import com.walpro.autobuilder.core.merge.CapabilityCut.Declaration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EK: what one capability of a project the loop did not build can be shown to do, proposed by a model reading its
 * code -- as claims a request can check, never as verdicts.
 *
 * <p>The model's answer is a guess about the code; nothing here takes it as true. Every claim carries the requests
 * that check it and the status it says comes back, and {@link ReverseEngineeringService} keeps only the claims the
 * running application bears out AND the application without the capability does not. The question says so, so the
 * model knows a wrong guess costs nothing and a claim about what the framework does on its own will not survive.
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

    private static final Pattern CLAIM = Pattern.compile("(?mi)^\\s*CLAIM:\\s*(.+?)\\s*$");
    private static final Pattern SEND = Pattern.compile("(?mi)^\\s*SEND:\\s*(GET|POST|PUT|PATCH|DELETE)\\s+(\\S+)[ \\t]*(.*?)\\s*$");
    private static final Pattern EXPECT = Pattern.compile("(?mi)^\\s*EXPECT:\\s*(\\d{3})\\b");

    private final LogInvestigator investigator;

    public ClaimProposer(LogInvestigator investigator) {
        this.investigator = investigator;
    }

    /**
     * One claim: the sentence, the requests that check it (the last is the one asserted; {@code $N.field} is a field of
     * response N) and the status the sentence says comes back.
     */
    public record Claim(String id, String capability, String text, List<ProbeRequest> sequence, int expected) {
    }

    /** What the model proposed for one capability, and every probe it made on the way, so a reader can check it. */
    public record Proposal(String capability, List<Claim> claims, List<String> malformed, List<String> probes, String answer) {
    }

    /**
     * @param firstNumber the number the first claim is numbered from; claims are numbered across capabilities so an id
     *                    names one claim in the whole account, and {@code {u}} becomes a value unique to its claim
     */
    public Proposal propose(Path code, Map<String, String> source, Map<String, Declaration> inventory, Capability capability,
                            List<Capability> all, ColdRead coldRead, int firstNumber) {
        String question = question(source, inventory, capability, all, coldRead);
        LogInvestigator.Investigation inv = investigator.investigate(question, Map.of(), code, PROBES, "reverse-engineering");
        List<String> probes = inv.steps().stream().map(LogInvestigator.Step::probe).toList();
        if (!inv.answered()) {
            log.warn("T4-EK: no claims for capability {} after {} probe(s)", capability.name(), probes.size());
            return new Proposal(capability.id(), List.of(), List.of(), probes, null);
        }
        Proposal p = parse(inv.answer(), capability.id(), firstNumber, probes);
        log.warn("T4-EK: capability {} -- {} claim(s) proposed, {} malformed, {} probe(s)", capability.name(),
                p.claims().size(), p.malformed().size(), probes.size());
        return p;
    }

    static String question(Map<String, String> source, Map<String, Declaration> inventory, Capability capability,
                           List<Capability> all, ColdRead coldRead) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                This project was not written by you, and it comes with no description of what it should do. It has \
                been cut into capabilities. Find out what ONE of them -- named below -- does when the running \
                application is called, and state it as claims a request can check.

                Each claim is checked by sending its requests to the running application. It is kept only when \
                the application answers the last request with the status the claim states, AND it no longer does \
                once this capability's code is taken out (its methods made to throw, everything else unchanged). \
                So state what THIS capability's code does -- what it accepts, what it refuses and with which \
                status, what it answers when something is missing or a rule is broken -- not what the framework \
                does on its own. A claim that turns out wrong costs nothing: it is simply dropped.
                """);
        sb.append("\nTHE CAPABILITY: ").append(capability.name()).append(" -- ").append(capability.summary()).append('\n');
        sb.append("Its declarations: ").append(String.join(", ", capability.declarations())).append('\n');
        List<String> others = all.stream().filter(c -> !c.id().equals(capability.id()))
                .map(c -> c.name() + " -- " + c.summary()).toList();
        if (!others.isEmpty()) {
            sb.append("\nTHE OTHER CAPABILITIES (claims about them belong to them, not here):\n");
            others.forEach(o -> sb.append("- ").append(o).append('\n'));
        }
        if (coldRead != null) {
            if (!coldRead.surface().isEmpty()) {
                sb.append("\nTHE REQUESTS THE CODE DECLARES:\n");
                coldRead.surface().forEach(s -> sb.append("  ").append(s).append('\n'));
            }
            if (coldRead.runtime() != null && coldRead.runtime().contextPath() != null) {
                sb.append("(every path sits under ").append(coldRead.runtime().contextPath())
                        .append(" -- write paths WITHOUT it; it is added when the request is sent)\n");
            }
            if (!coldRead.prose().isEmpty()) {
                sb.append("\nWHAT THE PROJECT SAYS ABOUT ITSELF, quoted:\n");
                coldRead.prose().forEach(l -> sb.append("  ").append(l).append('\n'));
            }
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

                Answer with up to %d claims, one block each:

                CLAIM: <one sentence: the request and the status it gets, e.g. "POST /things without a name returns HTTP 400">
                SEND: <METHOD> <path> <JSON body, or nothing>
                SEND: <further requests; the LAST one is the one the claim is about, the ones before it set things up>
                EXPECT: <the HTTP status of the last request>

                In a path or body, $N.field stands for a field of the answer to request N of the same claim \
                (e.g. GET /things/$1.id after a first request created one). {u} stands for a seven-digit number \
                unique to the claim -- use it where a value must not repeat across claims (all claims run, in \
                order, against one running application). A setup request must succeed, or the claim is not judged.
                """.formatted(MAX_CLAIMS));
        return sb.toString();
    }

    static Proposal parse(String answer, String capability, int firstNumber, List<String> probes) {
        List<Claim> claims = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        Matcher m = CLAIM.matcher(answer);
        List<int[]> starts = new ArrayList<>();
        while (m.find()) starts.add(new int[]{m.start(), m.end()});
        for (int i = 0; i < starts.size() && claims.size() < MAX_CLAIMS; i++) {
            String text = answer.substring(starts.get(i)[0], starts.get(i)[1]).replaceFirst("(?i)^\\s*CLAIM:\\s*", "")
                    .replaceAll("^[\"']|[\"']$", "").strip();
            String body = answer.substring(starts.get(i)[1], i + 1 < starts.size() ? starts.get(i + 1)[0] : answer.length());
            int number = firstNumber + claims.size();
            String unique = String.valueOf(1_000_000 + number);
            List<ProbeRequest> sequence = new ArrayList<>();
            Matcher s = SEND.matcher(body);
            while (s.find()) {
                String b = s.group(3).isBlank() ? null : s.group(3).strip().replace("{u}", unique);
                sequence.add(new ProbeRequest(s.group(1).toUpperCase(Locale.ROOT), s.group(2).replace("{u}", unique), b));
            }
            Matcher e = EXPECT.matcher(body);
            if (sequence.isEmpty() || sequence.size() > MAX_REQUESTS || !e.find()) {
                malformed.add(text);
                continue;
            }
            claims.add(new Claim("k" + number, capability, text, List.copyOf(sequence), Integer.parseInt(e.group(1))));
        }
        return new Proposal(capability, claims, malformed, probes, answer);
    }
}
