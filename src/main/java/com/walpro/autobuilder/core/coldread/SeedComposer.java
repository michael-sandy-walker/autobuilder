package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCallContext;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Account;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Feature;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.JudgedClaim;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EK slice (b): the seed of a project the loop did not build, written from what was EARNED on it.
 *
 * <p>The model writes it -- a seed is prose a person reads and confirms -- but only from the account: per feature, its
 * ways in, what it was observed to need, and its kept claims. Every line it writes ends with the ids of the claims it
 * rests on, and a line that cites none, or cites one that is not a kept claim, is refused here, by rule: a seed line no
 * claim supports would be the loop specifying the project it then grades itself against. What the project says about
 * itself (its README) is not part of it, quoted or paraphrased: nothing checked it (T1-GK).
 *
 * <p>The text this returns is what the operator sees and edits: at most one line on what the project is, in its
 * README's words ({@code What it is: ... [r1, r3]}, its README lines cited and checked to exist; never a criterion),
 * feature headers ({@code Name:}) and cited lines ({@code - ... [k3, k7]}), whose wording may use the README's terms.
 * What the README says the project does that no claim shows is OFFERED beside it ({@link Offered}), not written into
 * it. {@link #lines} reads it back -- an operator's own quoted ({@code > ...}) or uncited lines included, theirs to add.
 */
@Slf4j
@Component
public class SeedComposer {

    static final Pattern CITED = Pattern.compile("^\\s*-\\s*(.+?)\\s*\\[\\s*(k\\d+(?:\\s*,\\s*k\\d+)*)\\s*]\\s*$");
    /** A feature header; the model sometimes adds the kind it was shown ("Main (command-style):"). */
    private static final Pattern HEADER = Pattern.compile("^\\s*([A-Za-z_$][\\w.$]*)\\s*(?:\\([^)]*\\))?\\s*:\\s*$");

    /** T1-GK: what the project is, in the README's words, citing the README lines it comes from. */
    private static final Pattern WHAT = Pattern.compile("^\\s*What it is\\s*:\\s*(.+?)\\s*\\[\\s*(r\\d+(?:\\s*,\\s*r\\d+)*)\\s*]\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** T1-GK: a line of the README-only section -- something it says that no claim shows. */
    private static final Pattern README_CITED = Pattern.compile("^\\s*-\\s*(.+?)\\s*\\[\\s*(r\\d+(?:\\s*,\\s*r\\d+)*)\\s*]\\s*$");
    static final String NOT_SHOWN = "Not shown by any claim:";

    private final LLMClient llmClient;

    public SeedComposer(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * One line of a seed as read back: a feature header, a quote, a line resting on claims, or a line resting on none
     * (which, from the operator, is a requirement nothing has earned yet).
     */
    public record Line(String kind, String text, String feature, List<String> cites) {
        /** T4-EK slice (c): a line saying HOW work on the project is done -- not part of the seed, never checked. */
        public static final String RULE = "rule";
        public static final String HEADER = "header", QUOTE = "quote", CITED = "cited", UNCITED = "uncited",
                PURPOSE = "purpose";
        /** T4-EQ: a heading naming a way the project is used; the lines under it belong to the features their claims do. */
        public static final String SECTION = "section";
        /** T4-EQ: how the project is used, read from structure -- context like {@link #PURPOSE}, never a criterion or open work. */
        public static final String CONTEXT = "context";
        /** T4-EQ: a cited line standing under the cited line above it -- one of the cases in which that line's answer comes. */
        public static final String CASE = "case";
    }

    /**
     * T1-GK: something the README says the project does that no kept claim shows -- offered to the operator, never put
     * in the seed by the composer. Ticked, it joins the seed as the operator's own line: open work the loop checks.
     *
     * @param readme the README lines it comes from (r1 = the first line of the README's prose)
     */
    public record Offered(String text, List<String> readme) {
    }

    /**
     * The composed seed, the lines the model wrote that were refused (with why), and what the README says that no
     * claim shows (offered, not in the seed).
     */
    public record Composed(String text, List<String> refused, List<Offered> offered) {
        public Composed(String text, List<String> refused) {
            this(text, refused, List.of());
        }
    }

    public Composed compose(Account account) {
        Set<String> kept = keptIds(account);
        if (kept.isEmpty()) return new Composed("", List.of("no claim was kept, so no line can rest on one"));
        String prompt = prompt(account);
        List<Message> conversation = new ArrayList<>();
        conversation.add(new Message("system", "You write the specification of an existing software project from "
                + "evidence about what it does. You state only what the evidence shows."));
        conversation.add(new Message("user", prompt));
        String answer = ask(conversation);
        if (answer == null) return new Composed("", List.of("the model gave no answer"));
        Composed first = check(answer, kept, account);
        if (first.refused().isEmpty()) return first;
        conversation.add(new Message("assistant", answer));
        conversation.add(new Message("user", "These lines cite no kept claim, or an id that is not listed, and "
                + "cannot stay:\n" + String.join("\n", first.refused())
                + "\n\nWrite the whole specification again in the same form; every feature line must end with the ids of "
                + "claims listed above that show it, and every line taken from the README with the r-ids of its lines."));
        String again = ask(conversation);
        Composed second = again == null ? first : check(again, kept, account);
        log.warn("T4-EK: seed composed -- {} line(s) refused after a second ask", second.refused().size());
        return second;
    }

    static String prompt(Account account) {
        Map<String, JudgedClaim> byId = new LinkedHashMap<>();
        account.claims().forEach(j -> byId.put(j.claim().id(), j));
        StringBuilder sb = new StringBuilder("""
                Below is what was established about an existing project by running it: its features (the parts that \
                declare its ways in), what each was observed to depend on, and the claims about each that HELD on \
                the project and stopped holding with that feature taken out.

                Write the project's specification from this, feature by feature, in the dependency order given: one \
                line per thing the project does, in plain words a person reads. Name a way in the way its claims \
                EXERCISE it, with the query or the arguments they use -- "GET /loans?memberId={id}", not "GET /loans", \
                when that is how it was called; "count <file>" for a run; "Labeler.next" for a method. A reader of \
                the line (a person, or the loop checking it later) has to exercise it the same way the claims did, or \
                the line states something nobody checked. Write an id or a value a claim made up for itself as a \
                placeholder ("POST /loans/{id}/return", not "POST /loans/9999999/return"). Merge claims that say the \
                same thing into one line. Every line MUST end with the ids of the claims that show it, in brackets, e.g. [k3, k7]. \
                Write nothing no claim shows -- no intentions, no guesses.

                Answer in exactly this form, nothing else:
                <Feature name>:
                - <what it does> [<claim ids>]
                - ...
                <next feature name>:
                - ...

                """);
        List<String> readme = account.coldRead() == null ? List.of() : account.coldRead().prose();
        if (!readme.isEmpty()) {
            sb.append("""
                    The project's README is below, its lines numbered r1, r2, ... Nobody checked what it says; use it so:
                    - Where its words name what a claim shows (its terms, its roles, its names for things), use them in \
                    that line -- the line still ends with the ids of the claims that show it.
                    - Before the first feature, write ONE line saying what the project is and who uses it for what, in \
                    the README's words -- shortened to that, without what it is built with -- ending with the r-ids of the \
                    README lines it comes from; only if the README says it:
                      What it is: <one sentence> [r1, r3]
                    - After the last feature, under the heading "Not shown by any claim:", list what the README says a \
                    PERSON can do with the project, or what it does for them, that no claim above shows -- one line each, \
                    ending with its r-ids. What it is BUILT with is not something it does: leave out every line about a \
                    framework, library, language, database, view technology, architecture, layering or configuration, \
                    and how to install, build, run, test or deploy it, its CI, licence and badges. Leave the heading out \
                    when there is nothing.

                    README:
                    """);
            for (int i = 0; i < readme.size(); i++) sb.append('r').append(i + 1).append(": ").append(readme.get(i)).append('\n');
            sb.append('\n');
        }
        for (Feature f : ordered(account.features())) {
            sb.append("FEATURE ").append(f.name());
            if (f.kind() != null) sb.append(" (").append(f.kind()).append(")");
            sb.append("\n  ways in: ").append(String.join(", ", f.entryPoints()));
            sb.append("\n  depends on: ").append(f.dependsOn().isEmpty() ? "nothing" : String.join(", ", f.dependsOn()));
            sb.append("\n  claims that held:\n");
            for (String id : f.claims()) {
                JudgedClaim j = byId.get(id);
                if (j == null) continue;
                sb.append("    ").append(id).append(": ").append(j.claim().text());
                String exercised = exercised(j.claim());
                if (exercised != null) sb.append("\n        exercised as: ").append(exercised);
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** How a claim was exercised, in one line: its last request, or its run; null for a test (its method names it). */
    static String exercised(ClaimProposer.Claim k) {
        if (k.sequence() != null && !k.sequence().isEmpty()) {
            var last = k.sequence().get(k.sequence().size() - 1);
            return last.method() + " " + last.path().replaceAll("\\$\\d+\\.(\\w+)", "{$1}")
                    + " -> " + k.expected();
        }
        if (k.run() != null) return "run " + String.join(" ", k.run().args()) + " -> exit " + k.expected();
        return null;
    }

    /** The features in observed dependency order: a feature after what it depends on; otherwise by name. */
    static List<Feature> ordered(List<Feature> features) {
        Map<String, Feature> byName = new LinkedHashMap<>();
        features.stream().sorted(java.util.Comparator.comparing(Feature::name)).forEach(f -> byName.put(f.name(), f));
        List<Feature> out = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        while (placed.size() < byName.size()) {
            boolean progress = false;
            for (Feature f : byName.values()) {
                if (placed.contains(f.name())) continue;
                boolean ready = f.dependsOn().stream().allMatch(d -> placed.contains(d) || !byName.containsKey(d));
                if (ready) {
                    out.add(f);
                    placed.add(f.name());
                    progress = true;
                }
            }
            if (!progress) {   // a cycle: the rest by name
                byName.values().stream().filter(f -> !placed.contains(f.name())).forEach(f -> {
                    out.add(f);
                    placed.add(f.name());
                });
            }
        }
        return out;
    }

    /**
     * The model's answer checked line by line; the text returned holds the feature headers and the lines kept -- each
     * citing a kept claim. T1-GK: the project's README is NOT quoted into it: what a README says is unchecked
     * self-description (setup steps, badges, "production-oriented"), and the seed is the project's first input, which
     * the planner reads as specification -- every line of it rests on claims the running code bore out. The README
     * stays where it is context: the claim proposer reads it, the page shows it beside the seed.
     */
    static Composed check(String answer, Set<String> kept, Account account) {
        StringBuilder text = new StringBuilder();
        String what = null;
        List<String> refused = new ArrayList<>();
        List<Offered> offered = new ArrayList<>();
        int readmeLines = account.coldRead() == null ? 0 : account.coldRead().prose().size();
        boolean notShown = false;
        for (String raw : answer.split("\\R")) {
            String l = raw.strip();
            if (l.isEmpty() || l.startsWith("```")) continue;
            Matcher w = WHAT.matcher(l);
            if (w.matches()) {
                List<String> bad = outside(ids(w.group(2)), readmeLines);
                if (!bad.isEmpty()) refused.add(l + "  -- cites " + String.join(", ", bad) + ", not a README line");
                else if (what == null) what = "What it is: " + w.group(1).strip() + " [" + String.join(", ", ids(w.group(2))) + "]";
                continue;
            }
            if (l.equalsIgnoreCase(NOT_SHOWN)) {
                notShown = true;
                continue;
            }
            if (notShown) {
                Matcher r = README_CITED.matcher(l);
                if (!r.matches()) {
                    refused.add(l + "  -- cites no README line");
                    continue;
                }
                List<String> bad = outside(ids(r.group(2)), readmeLines);
                if (!bad.isEmpty()) refused.add(l + "  -- cites " + String.join(", ", bad) + ", not a README line");
                else offered.add(new Offered(r.group(1).strip(), ids(r.group(2))));
                continue;
            }
            if (HEADER.matcher(l).matches()) {
                text.append('\n').append(l).append('\n');
                continue;
            }
            Matcher m = CITED.matcher(l);
            if (!m.matches()) {
                refused.add(l + "  -- cites no claim");
                continue;
            }
            List<String> cites = ids(m.group(2));
            List<String> unknown = cites.stream().filter(c -> !kept.contains(c)).toList();
            if (!unknown.isEmpty()) {
                refused.add(l + "  -- cites " + String.join(", ", unknown) + ", not a kept claim");
                continue;
            }
            text.append("- ").append(m.group(1).strip()).append(" [").append(String.join(", ", cites)).append("]\n");
        }
        String body = text.toString().strip();
        return new Composed((what == null ? "" : what + "\n\n") + body + "\n", refused, offered);
    }

    /** The r-ids that name no line of a README of {@code lines} lines. */
    private static List<String> outside(List<String> rIds, int lines) {
        return rIds.stream().filter(r -> {
            int n = Integer.parseInt(r.substring(1));
            return n < 1 || n > lines;
        }).toList();
    }

    /** A seed text read back line by line, as the operator confirmed it. */
    public static List<Line> lines(String text, Set<String> features) {
        List<Line> out = new ArrayList<>();
        String feature = null;
        for (String raw : text.split("\\R")) {
            String l = raw.strip();
            if (l.isEmpty()) continue;
            // T4-EK slice (c): rules and the note above them are read before anything else -- a rule is never a seed
            // line, and above all never an operator's statement to be checked against the code.
            if (l.regionMatches(true, 0, ObservedRules.HEADING, 0, ObservedRules.HEADING.length())) continue;
            if (l.regionMatches(true, 0, ObservedRules.MARKER, 0, ObservedRules.MARKER.length())) {
                String rule = l.substring(ObservedRules.MARKER.length()).strip();
                if (!rule.isEmpty()) out.add(new Line(Line.RULE, rule, null, List.of()));
                continue;
            }
            Matcher h = HEADER.matcher(l);
            if (h.matches() && features.contains(h.group(1))) {
                feature = h.group(1);
                out.add(new Line(Line.HEADER, h.group(1), feature, List.of()));
                continue;
            }
            Matcher w = WHAT.matcher(l);
            if (w.matches() || l.regionMatches(true, 0, "What it is:", 0, "What it is:".length())) {
                // The project's purpose in its README's words: context for the seed, never a criterion or open work.
                String t = w.matches() ? w.group(1).strip() : l.substring("What it is:".length()).strip();
                out.add(new Line(Line.PURPOSE, t, null, List.of()));
                continue;
            }
            if (l.startsWith(">") || l.equalsIgnoreCase("In its own words:")) {
                out.add(new Line(Line.QUOTE, l, null, List.of()));
                continue;
            }
            if (l.regionMatches(true, 0, SeedRefiner.CONTEXT, 0, SeedRefiner.CONTEXT.length())) {
                out.add(new Line(Line.CONTEXT, l.substring(SeedRefiner.CONTEXT.length()).strip(), null, List.of()));
                continue;
            }
            // T4-EQ: a heading that is not a feature's name states nothing: it names a use, and ends the feature above.
            if (!l.startsWith("-") && l.endsWith(":") && l.length() > 1) {
                feature = null;
                out.add(new Line(Line.SECTION, l.substring(0, l.length() - 1).strip(), null, List.of()));
                continue;
            }
            Matcher m = CITED.matcher(l);
            if (m.matches()) {
                boolean nested = !raw.isEmpty() && Character.isWhitespace(raw.charAt(0));
                out.add(new Line(nested ? Line.CASE : Line.CITED, m.group(1).strip(), feature, ids(m.group(2))));
            } else {
                out.add(new Line(Line.UNCITED, l.replaceFirst("^-\\s*", ""), feature, List.of()));
            }
        }
        return out;
    }

    static Set<String> keptIds(Account account) {
        Set<String> kept = new LinkedHashSet<>();
        for (JudgedClaim j : account.claims()) {
            if (j.byFeature() != null && "anchored".equals(j.byFeature().verdict())) kept.add(j.claim().id());
        }
        return kept;
    }

    private static List<String> ids(String group) {
        List<String> out = new ArrayList<>();
        for (String id : group.split(",")) out.add(id.strip());
        return out;
    }

    private String ask(List<Message> conversation) {
        try (var scope = LlmCallContext.scope("seed-composer", "reverse-engineering")) {
            ResponseEntity<ChatResponse> response = llmClient.ask(List.copyOf(conversation));
            return response == null || response.getBody() == null ? null : response.getBody().getFirstContent();
        } catch (RuntimeException e) {
            log.warn("T4-EK: the seed could not be composed -- {}", e.getMessage());
            return null;
        }
    }
}
