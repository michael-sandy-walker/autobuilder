package com.walpro.autobuilder.core.build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-DD item 2: the deterministic reader for criteria whose observable outcome is an EXIT STATUS
 * rather than a response status — the sibling of {@link BehaviouralCriterionParser}, and the half
 * that target had none of.
 *
 * <p>The gap it closes, measured on the first non-web target (2026-08-25): the loop built a fully
 * correct command-line tool and its verifier could execute NOTHING, because a criterion could only
 * ever be read as {@code verb + path + status}. Every criterion abstained, so the run was scored
 * by a yardstick outside the loop while the loop itself worked blind.
 *
 * <p><b>The shape turned out to mirror the web case exactly, which is why this is a parser and
 * not a discovery subsystem.</b> Read over the 61 criteria that run authored, the invocation is
 * STATED IN THE CRITERION, the same way an endpoint is: <i>"Running {@code `<launch> unknown`}
 * prints … and exits with code 3"</i>. So there is nothing to discover — there is prose to read.
 *
 * <p><b>Abstain-heavy by construction</b>, on the same terms as its sibling; empty always means
 * "no opinion", never "unmet":
 * <ul>
 *   <li>the criterion must name ONE invocation of the artifact — none makes it not about running
 *       the tool, and two make the subject ambiguous;</li>
 *   <li>it must state exactly ONE exit status. Two or more is either a disjunction
 *       (<i>"exits 0 or 2"</i>) or a negation (<i>"exits 0 or 2, never 5"</i>), and both were
 *       present in the field corpus. Reading either as a single expectation would manufacture
 *       contradictions against a correct tool;</li>
 *   <li>every argument must be LITERAL. A criterion naming {@code <file>} or
 *       {@code <nonexistent-path>} describes a fixture this parser cannot conjure, and an
 *       invented one tests a different input from the one the requirement describes — the
 *       failure T1-FW is about. Grounding such arguments is a later slice; until it exists the
 *       honest answer is silence.</li>
 * </ul>
 *
 * <p>Nothing here knows what any particular status MEANS, and nothing here names an ecosystem:
 * which tokens introduce an invocation is read from the gate's configured launch command, which
 * is config data ([[feedback-no-ecosystem-jargon-in-framework-code]]).
 */
public final class CommandCriterionParser {

    private CommandCriterionParser() {
    }

    /**
     * One executable assertion: invoke the artifact with {@code args}, expect it to terminate with
     * {@code expectedExitStatus}. {@code criterionText} is carried verbatim so a contradiction can
     * name the criterion in the words its author wrote.
     */
    public record CommandAssertion(String criterionText, List<String> args, int expectedExitStatus) {
        /** e.g. {@code check missing.csv → exit 1}. */
        public String describe() {
            return (args.isEmpty() ? "(no arguments)" : String.join(" ", args))
                    + " → exit " + expectedExitStatus;
        }
    }

    /**
     * An exit status is introduced by a marker, never read as a bare number. The corpus this was
     * built from carries "exits 3", "exits with code 3", "exit code is 4" and "exits with code 5"
     * — and also "expected 3 fields, found 2", where reading 3 as a status would be a fabricated
     * expectation about a message format. The marker is what separates them.
     */
    private static final Pattern EXIT_STATUS = Pattern.compile(
            "\\bexit(?:s|ed|ing)?\\s+(?:with\\s+)?(?:status\\s+|code\\s+)?(?:is\\s+)?(\\d{1,3})\\b"
                    + "|\\bexit\\s+(?:status|code)\\s+(?:is\\s+)?(\\d{1,3})\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A stated exit status that is NOT a single expectation. Both forms are in the field corpus
     * verbatim — <i>"exits 0 or 2 (never 5)"</i> — and both defeat a plain count of the statuses
     * mentioned: the disjunction states a second status the status pattern does not reach on its
     * own, and the negation states one the artifact must NOT produce. Read either way round, a
     * correct tool would be recorded as contradicting its own requirement.
     */
    private static final Pattern DISJUNCTION = Pattern.compile(
            "\\bexit\\w*\\s+(?:with\\s+)?(?:status\\s+|code\\s+)?(?:is\\s+)?\\d{1,3}\\s+or\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATION = Pattern.compile("\\bnever\\b", Pattern.CASE_INSENSITIVE);

    /** An argument the criterion described rather than gave — a fixture, not a value. */
    private static final Pattern PLACEHOLDER = Pattern.compile("[<>{}\\[\\]]|\\.\\.\\.");

    /**
     * The assertion this criterion makes, or empty when it is not a single unambiguous statement
     * about one invocation's exit status.
     *
     * @param launchTokens the fixed leading tokens of the gate's configured launch command (for
     *                     {@code java -jar {artifact}} that is {@code [java, -jar]}); the token
     *                     standing in for the artifact itself is consumed positionally after them,
     *                     so the arguments are whatever the criterion wrote beyond it.
     */
    public static Optional<CommandAssertion> parse(String criterion, List<String> launchTokens) {
        if (criterion == null || criterion.isBlank() || launchTokens == null || launchTokens.isEmpty()) {
            return Optional.empty();
        }

        if (DISJUNCTION.matcher(criterion).find() || NEGATION.matcher(criterion).find()) {
            return Optional.empty();
        }

        Set<Integer> codes = new LinkedHashSet<>();
        Matcher sm = EXIT_STATUS.matcher(criterion);
        while (sm.find()) {
            String g = sm.group(1) != null ? sm.group(1) : sm.group(2);
            codes.add(Integer.parseInt(g));
        }
        if (codes.size() != 1) return Optional.empty();

        Set<List<String>> invocations = new LinkedHashSet<>();
        for (String span : candidateSpans(criterion)) {
            argumentsOf(span, launchTokens).ifPresent(invocations::add);
        }
        if (invocations.size() != 1) return Optional.empty();

        List<String> args = invocations.iterator().next();
        for (String a : args) {
            if (PLACEHOLDER.matcher(a).find()) return Optional.empty();
        }
        return Optional.of(new CommandAssertion(criterion.strip(), args, codes.iterator().next()));
    }

    /**
     * Spans the invocation could be written in. Backticks and quotes are how the corpus writes it
     * ("Running {@code `<launch> check`} …"), but a bare mention is common too, so the whole
     * criterion is offered as a span of last resort.
     */
    private static final Pattern QUOTED = Pattern.compile("[`\"']([^`\"']+)[`\"']");

    private static List<String> candidateSpans(String criterion) {
        List<String> spans = new ArrayList<>();
        Matcher m = QUOTED.matcher(criterion);
        while (m.find()) spans.add(m.group(1));
        if (spans.isEmpty()) spans.add(criterion);
        return spans;
    }

    /**
     * The argument vector inside one span, or empty when the span does not invoke the artifact.
     * The launch tokens must appear in order, immediately followed by the artifact's own token;
     * everything after that is the argument vector — including the empty vector, which is a real
     * invocation ("running with no arguments … exits 3") and not an absence.
     */
    private static Optional<List<String>> argumentsOf(String span, List<String> launchTokens) {
        List<String> tokens = new ArrayList<>();
        for (String t : span.trim().split("\\s+")) {
            if (!t.isBlank()) tokens.add(t);
        }
        for (int i = 0; i + launchTokens.size() < tokens.size(); i++) {
            boolean matched = true;
            for (int j = 0; j < launchTokens.size(); j++) {
                if (!tokens.get(i + j).equalsIgnoreCase(launchTokens.get(j))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                // + 1 consumes the artifact token itself, whatever the criterion called it.
                return Optional.of(List.copyOf(tokens.subList(i + launchTokens.size() + 1, tokens.size())));
            }
        }
        return Optional.empty();
    }

    /**
     * The fixed leading tokens of a configured launch command — everything before the placeholder
     * that stands for the artifact. {@code java -jar {artifact}} yields {@code [java, -jar]}.
     */
    public static List<String> launchTokensOf(String commandTemplate) {
        if (commandTemplate == null || commandTemplate.isBlank()) return List.of();
        List<String> fixed = new ArrayList<>();
        for (String t : commandTemplate.trim().split("\\s+")) {
            if (t.contains("{artifact}")) break;
            if (!t.isBlank() && !t.contains("{")) fixed.add(t);
        }
        return List.copyOf(fixed);
    }
}
