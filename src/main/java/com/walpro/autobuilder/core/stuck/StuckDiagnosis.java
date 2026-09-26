package com.walpro.autobuilder.core.stuck;

import com.walpro.autobuilder.core.build.StuckQuestions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-DY slice B: the questions a stuck observation poses, in the order they are asked, and their
 * answers from STRUCTURE -- the sources as they are now, the sources as they were adopted, the seed.
 *
 * <p>A question is asked again only in the sense that a recurrence after a delivered answer moves to
 * the NEXT question ({@code rung + 1}); the same question is never re-asked as if its answer had not
 * been heard. When a source has no question left, the ledger turns the observation into a diagnosis
 * request for the operator: no authority here could answer it.
 *
 * <p>Pure and Spring-free: every input is handed in, so the ladder can be replayed over an archive.
 */
public final class StuckDiagnosis {

    private StuckDiagnosis() {
    }

    /**
     * One question put to an authority, and what it answered.
     *
     * @param question  the question, in words
     * @param authority who was asked
     * @param answer    the fact, ready for the evolution prompt; null when the authority could not answer
     * @param needsOperator true when the answer itself shows something only the operator may decide
     */
    public record Asked(String question, String authority, String answer, boolean needsOperator) {
        public boolean answered() {
            return answer != null && !answer.isBlank();
        }
    }

    /** The inputs every question may consult. Maps are path relative to the project root -> content. */
    public record Authorities(Map<String, String> currentSources, Map<String, String> adoptedSources, String seed) {
        public Authorities {
            currentSources = currentSources == null ? Map.of() : currentSources;
            adoptedSources = adoptedSources == null ? Map.of() : adoptedSources;
        }
    }

    /** How many questions a source has; a rung at or past this has none left. */
    public static int questionsFor(StuckObservation.Source source) {
        return switch (source) {
            case BUILD -> 2;
            case PROTECTED_WRITE, CLOSURE_REFUSAL -> 1;
            case FLAT_ROOT, EXHAUSTION -> 0;
        };
    }

    /** The {@code rung}-th question for this observation, asked of the authorities; empty when there is none left. */
    public static Optional<Asked> ask(StuckObservation.Source source, String signature, List<String> detail,
                                      int rung, Authorities authorities) {
        if (rung < 0 || rung >= questionsFor(source)) return Optional.empty();
        return Optional.of(switch (source) {
            case BUILD -> rung == 0 ? declared(signature, authorities) : declaringFile(signature, authorities);
            case PROTECTED_WRITE -> protectedFile(signature, authorities);
            case CLOSURE_REFUSAL -> refusedRequirement(signature, detail, authorities);
            case FLAT_ROOT, EXHAUSTION -> throw new IllegalStateException("no question for " + source);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // BUILD, rung 0: what does the code declare for the name the failure names?
    // ---------------------------------------------------------------------------------------------

    static Asked declared(String signature, Authorities a) {
        String question = "What does the code declare for the name this failure is about?";
        String authority = "structure: the current tree and the adopted original";
        Optional<StuckQuestions.Answer> now = StuckQuestions.answer(signature, a.currentSources());
        Optional<StuckQuestions.Answer> adopted = StuckQuestions.answer(signature, a.adoptedSources());
        if (now.isEmpty() && adopted.isEmpty()) return new Asked(question, authority, null, false);
        StringBuilder sb = new StringBuilder();
        if (now.isPresent()) {
            sb.append(render(now.get()));
            if (adopted.isPresent() && !adopted.get().declared().equals(now.get().declared())) {
                sb.append("\n    When the project was adopted it was: ").append(String.join("; ", adopted.get().declared()));
            }
        } else {
            sb.append("Not declared in the current tree. When the project was adopted: ").append(render(adopted.get()));
        }
        return new Asked(question, authority, sb.toString(), false);
    }

    static String render(StuckQuestions.Answer ans) {
        String list = String.join("; ", ans.declared());
        return switch (ans.kind()) {
            case MEMBERS -> ans.type() + " declares: " + list + "."
                    + (ans.meant() != null ? " It has no " + ans.member() + "(); the member that name means is " + ans.meant() + "."
                    : " It has no " + ans.member() + "().");
            case CONSTRUCTOR -> ans.type() + " is constructed only as: " + list + ".";
            case OVERLOADS -> ans.type() + " declares " + ans.member() + " only as: " + list + ".";
            case SYMBOL -> "'" + ans.member() + "' is declared as: " + list + ".";
        };
    }

    // ---------------------------------------------------------------------------------------------
    // BUILD, rung 1: the declared names did not stop it -- what does the declaring file say, whole?
    // ---------------------------------------------------------------------------------------------

    /** Past this a file is not shown: half a file invites the same invention a summary did. */
    static final int WHOLE_FILE_LIMIT = 12_000;

    private static final Pattern TYPE_IN_SIGNATURE = Pattern.compile(
            "(?:fails: ([A-Z]\\w*) has no|in (?:record|class|interface|enum) (?:[\\w]+\\.)*([A-Z]\\w*)|fails: ([A-Z]\\w*)\\.java:)");

    static Asked declaringFile(String signature, Authorities a) {
        String question = "The declared names did not end this failure. What does the file that declares the type say, whole?";
        String authority = "structure: the declaring file, as adopted where it was adopted";
        String type = typeNamed(signature);
        if (type == null) return new Asked(question, authority, null, false);
        for (Map<String, String> tree : List.of(a.adoptedSources(), a.currentSources())) {
            for (Map.Entry<String, String> e : tree.entrySet()) {
                String p = e.getKey().replace('\\', '/');
                if (!(p.endsWith("/" + type + ".java") || p.equals(type + ".java"))) continue;
                String content = e.getValue();
                if (content == null || content.isBlank() || content.length() > WHOLE_FILE_LIMIT) continue;
                String which = tree == a.adoptedSources() ? "as it was adopted (its declarations are kept as they are)"
                        : "as it is now";
                return new Asked(question, authority, p + ", " + which + ":\n```java\n" + content.stripTrailing() + "\n```", false);
            }
        }
        return new Asked(question, authority, null, false);
    }

    static String typeNamed(String signature) {
        if (signature == null) return null;
        Matcher m = TYPE_IN_SIGNATURE.matcher(signature);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) return m.group(g);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // PROTECTED_WRITE: what does the file say that may not be changed?
    // ---------------------------------------------------------------------------------------------

    private static final Pattern PROTECTED_PATH = Pattern.compile("write to (\\S+) refused");

    static Asked protectedFile(String signature, Authorities a) {
        String question = "The file cannot be written. What does it say, so the code can meet it as it is?";
        String authority = "structure: the protected file";
        Matcher m = PROTECTED_PATH.matcher(signature == null ? "" : signature);
        if (!m.find()) return new Asked(question, authority, null, false);
        String path = m.group(1);
        for (Map.Entry<String, String> e : a.currentSources().entrySet()) {
            String p = e.getKey().replace('\\', '/');
            if (!(p.equals(path) || p.endsWith("/" + path) || path.endsWith("/" + p))) continue;
            String content = e.getValue();
            if (content == null || content.isBlank() || content.length() > WHOLE_FILE_LIMIT) break;
            return new Asked(question, authority, path + " is protected by the operator and stays exactly as it is;"
                    + " change the code it uses instead. It says:\n```\n" + content.stripTrailing() + "\n```", false);
        }
        return new Asked(question, authority, null, false);
    }

    // ---------------------------------------------------------------------------------------------
    // CLOSURE_REFUSAL: which requirement, and what evidence would answer it?
    // ---------------------------------------------------------------------------------------------

    /**
     * The first rule for a repeated closure refusal (T4-DY criterion 3): the refusal already carries
     * the requirement (the criteria it refused on) and the evidence that was missing (the gate's
     * reason); what it never said is whether the SEED states that requirement. A criterion naming
     * something the seed never mentions is a requirement only the operator can bind or release
     * (T3-CC) -- the answer says so and asks for the operator.
     */
    static Asked refusedRequirement(String signature, List<String> criteria, Authorities a) {
        String question = "Which requirement keeps the closure refused, and what evidence would answer it?";
        String authority = "structure: the refusal record and the seed";
        StringBuilder sb = new StringBuilder("Closure keeps being refused -- ").append(signature).append('.');
        boolean unstated = false;
        if (criteria != null && !criteria.isEmpty()) {
            sb.append("\n    The requirement(s) it refused on:");
            for (String c : criteria) {
                sb.append("\n      - ").append(c);
                List<String> missing = namesTheSeedLacks(c, a.seed());
                if (!missing.isEmpty()) {
                    unstated = true;
                    sb.append("  [the seed never names: ").append(String.join(", ", missing)).append(']');
                }
            }
            sb.append("\n    The work that answers it is evidence for exactly these requirements, as the refusal states it.");
        }
        return new Asked(question, authority, sb.toString(), unstated);
    }

    private static final Pattern CODE_LIKE = Pattern.compile(
            "`([^`]+)`|\"([^\"]{2,})\"|(/[\\w{}./-]+)|\\b([A-Za-z_]\\w*?(?:[A-Z_]\\w*|\\.[A-Za-z_]\\w*)+)");

    /**
     * The code-like names in a criterion (quoted, back-ticked, a path, a camel-case or dotted name)
     * that the seed does not contain. Only names: whether the seed states a requirement in other
     * words is not decidable here, so plain words are never counted against it.
     */
    static List<String> namesTheSeedLacks(String criterion, String seed) {
        if (criterion == null || seed == null || seed.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher m = CODE_LIKE.matcher(criterion);
        while (m.find()) {
            String name = null;
            for (int g = 1; g <= 4 && name == null; g++) name = m.group(g);
            if (name == null) continue;
            String bare = name.replaceAll("\\(.*$", "").strip();
            if (bare.length() < 3) continue;
            if (!seed.contains(bare)) out.add(bare);
        }
        return new ArrayList<>(out);
    }
}
