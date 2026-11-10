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
 * itself is quoted above the lines, as it stands; the model does not paraphrase it.
 *
 * <p>The text this returns is what the operator sees and edits: feature headers ({@code Name:}), quoted prose
 * ({@code > ...}), cited lines ({@code - ... [k3, k7]}). {@link #lines} reads it back.
 */
@Slf4j
@Component
public class SeedComposer {

    private static final Pattern CITED = Pattern.compile("^\\s*-\\s*(.+?)\\s*\\[\\s*(k\\d+(?:\\s*,\\s*k\\d+)*)\\s*]\\s*$");
    /** A feature header; the model sometimes adds the kind it was shown ("Main (command-style):"). */
    private static final Pattern HEADER = Pattern.compile("^\\s*([A-Za-z_$][\\w.$]*)\\s*(?:\\([^)]*\\))?\\s*:\\s*$");

    private final LLMClient llmClient;

    public SeedComposer(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * One line of a seed as read back: a feature header, a quote, a line resting on claims, or a line resting on none
     * (which, from the operator, is a requirement nothing has earned yet).
     */
    public record Line(String kind, String text, String feature, List<String> cites) {
        public static final String HEADER = "header", QUOTE = "quote", CITED = "cited", UNCITED = "uncited";
    }

    /** The composed seed, and the lines the model wrote that were refused (with why). */
    public record Composed(String text, List<String> refused) {
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
        conversation.add(new Message("user", "These lines cite no kept claim, or a claim id that is not listed, and "
                + "cannot stay:\n" + String.join("\n", first.refused())
                + "\n\nWrite the whole specification again in the same form; every line must end with the ids of "
                + "claims listed above that show it."));
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

    /** The model's answer checked line by line; the text returned holds the project's quoted prose and the lines kept. */
    static Composed check(String answer, Set<String> kept, Account account) {
        StringBuilder text = new StringBuilder();
        if (account.coldRead() != null && !account.coldRead().prose().isEmpty()) {
            text.append("In its own words:\n");
            account.coldRead().prose().forEach(p -> text.append("> ").append(p).append('\n'));
            text.append('\n');
        }
        List<String> refused = new ArrayList<>();
        for (String raw : answer.split("\\R")) {
            String l = raw.strip();
            if (l.isEmpty() || l.startsWith("```")) continue;
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
        return new Composed(text.toString().strip() + "\n", refused);
    }

    /** A seed text read back line by line, as the operator confirmed it. */
    public static List<Line> lines(String text, Set<String> features) {
        List<Line> out = new ArrayList<>();
        String feature = null;
        for (String raw : text.split("\\R")) {
            String l = raw.strip();
            if (l.isEmpty()) continue;
            Matcher h = HEADER.matcher(l);
            if (h.matches() && features.contains(h.group(1))) {
                feature = h.group(1);
                out.add(new Line(Line.HEADER, h.group(1), feature, List.of()));
                continue;
            }
            if (l.startsWith(">") || l.equalsIgnoreCase("In its own words:")) {
                out.add(new Line(Line.QUOTE, l, null, List.of()));
                continue;
            }
            Matcher m = CITED.matcher(l);
            if (m.matches()) {
                out.add(new Line(Line.CITED, m.group(1).strip(), feature, ids(m.group(2))));
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
