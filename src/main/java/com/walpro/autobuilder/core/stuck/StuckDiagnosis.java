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

    /**
     * The inputs every question may consult. Maps are path relative to the project root -> content.
     * {@code observer} is the OBSERVATION authority (T4-DZ's terminal agent), asked only after every
     * structural question for a signature was asked and the signature came back; null when there is none.
     */
    public record Authorities(Map<String, String> currentSources, Map<String, String> adoptedSources, String seed,
                              Observer observer, StuckQuestions.TypeAuthority types) {
        public Authorities {
            currentSources = currentSources == null ? Map.of() : currentSources;
            adoptedSources = adoptedSources == null ? Map.of() : adoptedSources;
            types = types == null ? StuckQuestions.TypeAuthority.platform() : types;
        }

        public Authorities(Map<String, String> currentSources, Map<String, String> adoptedSources, String seed) {
            this(currentSources, adoptedSources, seed, null, null);
        }

        public Authorities(Map<String, String> currentSources, Map<String, String> adoptedSources, String seed,
                           Observer observer) {
            this(currentSources, adoptedSources, seed, observer, null);
        }
    }

    /** An authority that answers by observing -- running something -- rather than by reading declarations. */
    @FunctionalInterface
    public interface Observer {
        /** The answer to what {@code signature} poses, observed; an unanswered {@link Asked} when nothing showed it. */
        Asked observe(String signature, List<String> evidence);
    }

    /** How many questions a source has; a rung at or past this has none left. */
    public static int questionsFor(StuckObservation.Source source) {
        return switch (source) {
            case BUILD -> 4;
            case PROTECTED_WRITE, CLOSURE_REFUSAL -> 1;
            case FLAT_ROOT, EXHAUSTION -> 0;
        };
    }

    /** The {@code rung}-th question for this observation, asked of the authorities; empty when there is none left. */
    public static Optional<Asked> ask(StuckObservation.Source source, String signature, List<String> detail,
                                      int rung, Authorities authorities) {
        if (rung < 0 || rung >= questionsFor(source)) return Optional.empty();
        return Optional.of(switch (source) {
            case BUILD -> switch (rung) {
                case 0 -> declared(signature, authorities);
                case 1 -> failingLine(detail, authorities);
                case 2 -> declaringFile(signature, authorities);
                default -> observed(signature, detail, authorities);
            };
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
        Optional<StuckQuestions.Answer> now = StuckQuestions.answer(signature, a.currentSources(), a.types());
        Optional<StuckQuestions.Answer> adopted = StuckQuestions.answer(signature, a.adoptedSources(), a.types());
        if (now.isEmpty() && adopted.isEmpty()) return new Asked(question, authority, null, false);
        StringBuilder sb = new StringBuilder();
        String meant = meantByImports(signature, now.or(() -> adopted).get(), a);
        if (now.isPresent()) {
            sb.append(render(now.get()));
            if (adopted.isPresent() && !adopted.get().declared().equals(now.get().declared())) {
                sb.append("\n    When the project was adopted it was: ").append(String.join("; ", adopted.get().declared()));
            }
        } else {
            sb.append("Not declared in the current tree. When the project was adopted: ").append(render(adopted.get()));
        }
        if (meant != null) sb.append(' ').append(meant);
        return new Asked(question, authority, sb.toString(), false);
    }

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+)\\.(\\w+|\\*)\\s*;", Pattern.MULTILINE);

    /**
     * T4-DY, the user's decision 2026-09-26: a name declared more than once (java.util.List and
     * java.awt.List) is ambiguous only until one asks which one the code imports. First the failing
     * file itself, as adopted and then as it stands; else the rest of the project, counted. Null when
     * the name is not ambiguous or no import decides it.
     */
    static String meantByImports(String signature, StuckQuestions.Answer ans, Authorities a) {
        if (ans.kind() != StuckQuestions.Kind.SYMBOL || ans.declared().size() < 2) return null;
        List<String> qualified = new ArrayList<>();
        for (String d : ans.declared()) {
            String name = d.replaceAll("\\s*\\(.*$", "");
            if (name.contains(".") && Character.isLowerCase(name.charAt(0))) qualified.add(name);
        }
        if (qualified.size() < 2) return null;
        String file = typeNamed(signature);
        for (Map<String, String> tree : List.of(a.adoptedSources(), a.currentSources())) {
            for (Map.Entry<String, String> e : tree.entrySet()) {
                String p = e.getKey().replace('\\', '/');
                if (file == null || !(p.endsWith("/" + file + ".java") || p.equals(file + ".java"))) continue;
                String hit = importedAmong(e.getValue(), qualified);
                if (hit != null) {
                    return "Of these, " + file + ".java imports " + hit + (tree == a.adoptedSources() ? " as adopted" : "")
                            + " -- that is the one meant; import it.";
                }
            }
        }
        java.util.Map<String, Integer> votes = new java.util.LinkedHashMap<>();
        for (Map<String, String> tree : List.of(a.currentSources(), a.adoptedSources())) {
            for (String content : tree.values()) {
                String hit = importedAmong(content, qualified);
                if (hit != null) votes.merge(hit, 1, Integer::sum);
            }
        }
        if (votes.size() != 1) return null;
        var only = votes.entrySet().iterator().next();
        return "Of these, the project imports " + only.getKey() + " (" + only.getValue() + " file(s)) and never the other"
                + " -- that is the one meant; import it.";
    }

    private static String importedAmong(String source, List<String> qualified) {
        if (source == null) return null;
        String hit = null;
        Matcher m = IMPORT.matcher(source);
        while (m.find()) {
            for (String q : qualified) {
                String pkg = q.substring(0, q.lastIndexOf('.')), simple = q.substring(q.lastIndexOf('.') + 1);
                boolean match = m.group(1).equals(pkg) && (m.group(2).equals(simple) || m.group(2).equals("*"));
                if (!match) continue;
                if (hit != null && !hit.equals(q)) return null; // imports both: no decision
                hit = q;
            }
        }
        return hit;
    }

    // ---------------------------------------------------------------------------------------------
    // BUILD, rung 1: the failure names no type -- what does the failing line construct, and how is it
    // declared, as adopted and as the change declared it?
    // ---------------------------------------------------------------------------------------------

    /**
     * T4-DY, the user's decision 2026-09-26. An inference error ("inference variable E has incompatible
     * bounds", "no instance of E so that List<E> conforms to int") names no type, yet the fact that ends
     * it is a declaration: the change had redeclared the record the failing line constructs. The
     * detector carries the change's line and its declarations ({@code line=}, {@code constructs=},
     * {@code declared-by-change=}); the answer sets them beside the declaration as adopted, else as the
     * tree has it now.
     */
    static Asked failingLine(List<String> evidence, Authorities a) {
        String question = "What does the failing line construct, and how is that declared -- as adopted, and by the change?";
        String authority = "structure: the change's own line, the adopted tree and the current tree";
        String line = null;
        List<String> types = new ArrayList<>(), byChange = new ArrayList<>();
        for (String e : evidence == null ? List.<String>of() : evidence) {
            if (e.startsWith("line=")) line = e.substring(5);
            else if (e.startsWith("constructs=")) types.add(e.substring(11));
            else if (e.startsWith("declared-by-change=")) byChange.add(e.substring(19));
        }
        if (line == null || types.isEmpty()) return new Asked(question, authority, null, false);
        StringBuilder sb = new StringBuilder("The failing line is: ").append(line);
        boolean any = false;
        for (String type : types) {
            List<String> adopted = constructorsInTree(type, a.adoptedSources());
            List<String> now = adopted.isEmpty() ? constructorsInTree(type, a.currentSources()) : adopted;
            if (now.isEmpty()) continue;
            any = true;
            List<String> changed = byChange.stream().filter(d -> d.startsWith(type + "(")).toList();
            sb.append("\n    It constructs ").append(type).append(", declared ").append(adopted.isEmpty() ? "now" : "as adopted")
                    .append(" as: ").append(String.join("; ", now)).append('.');
            if (!changed.isEmpty() && !changed.equals(now)) {
                sb.append("\n    The change redeclared it as: ").append(String.join("; ", changed))
                        .append(". Keep the declaration as ").append(adopted.isEmpty() ? "it is" : "adopted")
                        .append(" and build the line against it.");
            }
        }
        return any ? new Asked(question, authority, sb.toString(), false)
                : new Asked(question, authority, null, false);
    }

    private static List<String> constructorsInTree(String type, Map<String, String> tree) {
        for (String content : tree.values()) {
            if (content == null || !content.contains(type)) continue;
            List<String> c = StuckQuestions.constructorsIn(content, type);
            if (!c.isEmpty()) return c;
        }
        return List.of();
    }

    // ---------------------------------------------------------------------------------------------
    // BUILD, rung 3: structure has said all it can and the failure came back -- observe.
    // ---------------------------------------------------------------------------------------------

    /**
     * T4-DZ: the model proposes which fact would let the move succeed and the commands that would show
     * it; the commands run in a read-only copy and their output is the answer -- an observation, never
     * the model's reading. Unanswered when no observer is wired.
     */
    static Asked observed(String signature, List<String> evidence, Authorities a) {
        if (a.observer() == null) {
            return new Asked("Which fact would let this move succeed? (no observer to run commands)", "observation", null, false);
        }
        Asked asked = a.observer().observe(signature, evidence == null ? List.of() : evidence);
        return asked == null ? new Asked("Which fact would let this move succeed?", "observation", null, false) : asked;
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
    // BUILD, rung 2: neither the names nor the line ended it -- what does the declaring file say, whole?
    // ---------------------------------------------------------------------------------------------

    /** Past this a file is not shown: half a file invites the same invention a summary did. */
    static final int WHOLE_FILE_LIMIT = 12_000;

    private static final Pattern TYPE_IN_SIGNATURE = Pattern.compile(
            "(?:fails: ([A-Z]\\w*) has no|in (?:record|class|interface|enum) (?:[\\w]+\\.)*([A-Z]\\w*)|fails: ([A-Z]\\w*)\\.java:)");

    static Asked declaringFile(String signature, Authorities a) {
        String question = "Neither the declared names nor the failing line ended this failure. What does the file that declares the type say, whole?";
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
