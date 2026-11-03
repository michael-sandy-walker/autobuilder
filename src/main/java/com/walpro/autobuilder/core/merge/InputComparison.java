package com.walpro.autobuilder.core.merge;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EC slice 5: the questions a landing puts to a model, each checked offline against real answers before any code
 * read it (the rules and the answers: {@code ~/.autobuilder/replay5}). A question that gets no usable answer answers
 * nothing: nothing is raised, nothing is held, and the work goes on as before.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InputComparison {

    private static final String TYPES_SYSTEM = "You compare the types of two independently written software projects."
            + " Answer only in the format asked for.";
    private static final String DIFFERENCES_SYSTEM = "You compare a part of one software project with a part of another"
            + " that may denote the same thing. You name where they would behave differently. Answer only in the format asked for.";
    private static final String DECIDE_SYSTEM = "You decide how a merge of two software projects treats a difference"
            + " between them. Answer only in the format asked for.";
    private static final String CLAIM_SYSTEM = "You judge what happens to a requirement of one software project when that"
            + " project's code is brought into another project. Answer only in the format asked for.";

    private static final String OPTIONS_SYSTEM = "You help a person decide a conflict that arose when one software project"
            + " was brought into another. You lay out the ways it can be resolved and what each changes; the person decides."
            + " Answer only in the format asked for.";

    private static final String USE_SYSTEM = "You read what a person asks of a software project. Answer only in the format asked for.";

    /**
     * For a question whose answer is a complete list. At the run's "none" one named nothing for an intent saying "own
     * reader", and the other stopped at the first line of four stating a rule: the three left stood, closure refused the
     * decided 200 for them, and the loop put the 120 back (field128). At "low" both are exact.
     */
    private static final String LIST_EFFORT = "low";

    private static final Pattern SAME = Pattern.compile("(?m)^\\s*SAME:\\s*(\\S+)");
    private static final Pattern DIFFERENCE = Pattern.compile("(?m)^\\s*DIFFERENCE:\\s*(.+?)\\s*\\|\\s*RECEIVING:\\s*(.+?)\\s*\\|\\s*OTHER:\\s*(.+?)\\s*$");

    private final LLMClient llmClient;

    public record Difference(String what, String receiving, String other) {
    }

    /**
     * T4-ED: one way a conflict can be resolved, as it would be written down, and what it changes; at most one of a
     * conflict's options is {@code recommended}, with {@code why} in one sentence.
     */
    public record Option(String resolution, String effect, boolean recommended, String why) {
        public Option(String resolution, String effect) {
            this(resolution, effect, false, null);
        }
    }

    private static final Pattern OPTION = Pattern.compile(
            "(?m)^\\s*OPTION:\\s*(.+?)\\s*\\|\\s*EFFECT:\\s*(.+?)\\s*(\\|\\s*RECOMMENDED\\s*)?$");
    private static final Pattern WHY = Pattern.compile("(?m)^\\s*WHY:\\s*(.+?)\\s*$");

    /**
     * T4-ED: the ways a held conflict can be resolved -- either side as it is, and what a combination would be -- each
     * with what it changes, for the operator to choose from or rewrite. Advice only: nothing is decided here.
     *
     * @param kind what the conflict is about: a rule both projects have, a file both have, or a requirement the
     *             brought project carried that may not hold here
     */
    public List<Option> options(String specification, String intent, String kind, String subject, String receiving,
                                String other) {
        String answer = ask(OPTIONS_SYSTEM, "THE RECEIVING PROJECT IS FOR:\n" + specification
                + "\n\nANOTHER PROJECT WAS BROUGHT INTO IT, FOR THIS:\n" + intent
                + "\n\nTHE CONFLICT (" + kind + "): " + subject + "\nTHE RECEIVING PROJECT: " + receiving
                + "\nTHE BROUGHT PROJECT: " + other
                + "\n\nList two to four ways to resolve it: keeping the receiving project's side, taking the brought"
                + " project's, and any combination of the two that makes sense here. Write each resolution as the rule or"
                + " instruction the merged project should follow, in one sentence, then what choosing it changes for the"
                + " merged project. One line each, in this form:\n\nOPTION: <the resolution> | EFFECT: <what it changes>"
                + "\n\nMark exactly one option as the one you recommend for this project and the reason it was brought"
                + " in, by ending its line with | RECOMMENDED. After the options, write one line:\n\nWHY: <one sentence>");
        Matcher why = WHY.matcher(answer);
        String reason = why.find() ? why.group(1) : null;
        List<Option> out = new ArrayList<>();
        boolean marked = false;
        Matcher m = OPTION.matcher(answer);
        while (m.find() && out.size() < 4) {
            // Only the first mark counts: "exactly one" asked, more than one answered is one recommendation.
            boolean recommended = m.group(3) != null && !marked;
            marked |= recommended;
            out.add(new Option(m.group(1), m.group(2), recommended, recommended ? reason : null));
        }
        return out;
    }

    /** The receiving types that deal with the same concept or rule as {@code source}. */
    public List<String> sameConcept(String specification, List<String> receivingTypes, String source) {
        String answer = ask(TYPES_SYSTEM, "THE RECEIVING PROJECT'S SPECIFICATION:\n" + specification
                + "\n\nTHE RECEIVING PROJECT'S TYPES:\n" + String.join("\n", receivingTypes)
                + "\n\nA TYPE OF ANOTHER PROJECT:\n" + source
                + "\n\nWhich receiving types deal with the same concept or the same rule as this type? Name each on one"
                + " line, or write NONE. Do not explain.\n\nSAME: <receiving type>");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = SAME.matcher(answer);
        while (m.find()) {
            String name = m.group(1).substring(m.group(1).lastIndexOf('/') + 1);
            if (!name.equalsIgnoreCase("NONE")) out.add(name);
        }
        return List.copyOf(out);
    }

    /** Where the two would give a different result for the same input. Each side must carry what it refers to. */
    public List<Difference> differences(String specification, String receiving, String other) {
        String answer = ask(DIFFERENCES_SYSTEM, "THE RECEIVING PROJECT'S SPECIFICATION:\n" + specification
                + "\n\nTHE RECEIVING PROJECT'S CODE:\n" + receiving + "\n\nTHE OTHER PROJECT'S CODE:\n" + other
                + "\n\nList every rule or limit on which the two would give a different result for the same input: one"
                + " line each, in this form. Only list a rule the receiving project actually has. If it has no rule on"
                + " this, that is not a difference. Do not list differences of style, naming or structure. If there are"
                + " none, write NONE.\n\nDIFFERENCE: <what> | RECEIVING: <its rule> | OTHER: <its rule>");
        List<Difference> out = new ArrayList<>();
        Matcher m = DIFFERENCE.matcher(answer);
        while (m.find()) out.add(new Difference(m.group(1), m.group(2), m.group(3)));
        return out;
    }

    /** True when the operator's intent names this rule, deciding it for the input. */
    public boolean settledByIntent(String intent, Difference d) {
        String answer = ask(DECIDE_SYSTEM, "WHAT THE OPERATOR SAID THE OTHER PROJECT IS BROUGHT IN FOR:\n" + intent
                + "\n\nA DIFFERENCE BETWEEN THE TWO PROJECTS:\n" + d.what() + ". RECEIVING: " + d.receiving() + ". OTHER: "
                + d.other() + ".\n\nDoes what the operator said decide this difference in the other project's favour?"
                + " SETTLED only when it names this rule; OPEN otherwise.\n\nANSWER: SETTLED or OPEN");
        return answer.contains("SETTLED") && !answer.contains("OPEN");
    }

    /** True when a claim the input carried cannot be true of the merged project. */
    public boolean contradicts(String specification, String intent, String claim) {
        String answer = ask(CLAIM_SYSTEM, "THE RECEIVING PROJECT IS FOR:\n" + specification
                + "\n\nANOTHER PROJECT'S CODE WAS BROUGHT INTO IT, FOR THIS:\n" + intent
                + "\n\nA REQUIREMENT THE BROUGHT PROJECT HAD SATISFIED ON ITS OWN:\n" + claim
                + "\n\nCan this requirement still be true of the merged project? Answer APPLIES when it is about code or"
                + " behaviour the merged project can have alongside the receiving project's purpose, even if that code"
                + " needs work. Answer CONTRADICTS only when it cannot be true of the merged project, because it"
                + " conflicts with what the receiving project is.\n\nVERDICT: APPLIES or CONTRADICTS\nREASON: one sentence");
        return answer.contains("VERDICT: CONTRADICTS");
    }

    /** The requirements, of {@code lines}, that state the receiving rule of {@code d}. */
    public List<String> stating(List<String> lines, Difference d) {
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) numbered.append(i + 1).append(". ").append(lines.get(i)).append('\n');
        String answer = ask(DECIDE_SYSTEM, LIST_EFFORT, "THE RECEIVING PROJECT'S REQUIREMENTS:\n" + numbered + "\nA DIFFERENCE: "
                + d.what() + ". RECEIVING: " + d.receiving() + ". OTHER: " + d.other()
                + ".\n\nWhich requirements state the receiving rule of this difference? Give their numbers, or NONE."
                + "\n\nLINES: <numbers separated by commas>");
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(answer.substring(Math.max(0, answer.lastIndexOf("LINES:"))));
        while (m.find()) {
            int n = Integer.parseInt(m.group());
            if (n >= 1 && n <= lines.size() && !out.contains(lines.get(n - 1))) out.add(lines.get(n - 1));
        }
        return out;
    }

    /**
     * The types, of {@code types}, the intent requires to be used rather than written again; only names on the list
     * count. Replayed on the intent, not its obligations: the generated lines lose "use", and never name a type.
     */
    public List<String> typesToUse(String label, String intent, List<String> types) {
        String answer = ask(USE_SYSTEM, LIST_EFFORT, "A project is being brought into another one, with this intent:\n\n" + intent
                + "\n\nTHE TYPES THE BROUGHT PROJECT, " + label + ", CONTAINS:\n" + String.join("\n", types)
                + "\n\nWhich of these types does the intent require the receiving project's code to call or use, rather"
                + " than writing the same thing itself? Name a type only if the intent asks for what it does to come from"
                + " the brought project. A type whose work the intent does not ask to reuse is not named."
                + "\n\nTYPES: <names separated by commas, or NONE>");
        Set<String> said = new LinkedHashSet<>(List.of(answer.substring(answer.lastIndexOf("TYPES:") + 1).split("\\W+")));
        return types.stream().filter(said::contains).toList();
    }

    private String ask(String system, String user) {
        return ask(system, null, user);
    }

    private String ask(String system, String effort, String user) {
        try (var purpose = com.walpro.autobuilder.client.LlmCallContext.scopeIfUnscoped("project-input")) {
            ResponseEntity<ChatResponse> r = llmClient.ask(List.of(new Message("system", system), new Message("user", user)), effort);
            return r == null || r.getBody() == null || r.getBody().getFirstContent() == null ? "" : r.getBody().getFirstContent();
        } catch (Exception e) {
            log.warn("T4-EC: a comparison call failed ({}) -- it answers nothing", e.getMessage());
            return "";
        }
    }
}
