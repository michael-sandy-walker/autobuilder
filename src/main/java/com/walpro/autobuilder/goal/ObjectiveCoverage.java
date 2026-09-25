package com.walpro.autobuilder.goal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-CQ: does a split of a specification into obligations still carry the specification's own
 * OBJECTIVE?
 *
 * <p>Every adopted seed states what finishing means in one sentence opened by {@code OBJECTIVE:}
 * ("every test in RolesSpecTest passes, and the whole suite passes"). The obligations file is
 * written by a model, and sampled offline on the change seeds it once left that sentence out
 * entirely: closure would then never ask the one question the operator wrote down as the goal.
 * This checks the split deterministically, so the generator can ask again or add the clause in the
 * seed's own words instead of trusting the sample.
 *
 * <p><b>Why not {@code SeedRequirements}' overlap.</b> That one decides whether a banked criterion
 * already covers a requirement at the closure edge, where calling a requirement covered SKIPS a
 * question -- so it must stay strict, and it compares words exactly. Here the error runs the other
 * way: a clause wrongly called covered is the defect being caught, and a clause wrongly called
 * missing only adds a line in the seed's own words. The comparison therefore tolerates plural and
 * third-person endings ("the tests pass" states "every test passes"), which the closure-edge proxy
 * must not.
 */
public final class ObjectiveCoverage {

    /** The operator's own marker for the sentence that says what finishing means. */
    static final String OBJECTIVE_MARKER = "OBJECTIVE:";

    private static final Pattern OBJECTIVE = Pattern.compile("(?s)" + Pattern.quote(OBJECTIVE_MARKER) + "\\s*(.*?)(?:\\.\\s|\\.$|\\n\\s*\\n|$)");

    /** Words that quantify or point rather than name what must hold. */
    private static final Set<String> STOP = Set.of(
            "every", "each", "above", "below", "that", "this", "these", "those", "with", "from", "their",
            "there", "they", "have", "been", "also", "which", "into", "then", "when", "must", "should");

    private ObjectiveCoverage() {
    }

    /** The objective's clauses, in the seed's words; empty when the seed states no objective. */
    public static List<String> clausesOf(String seedText) {
        List<String> out = new ArrayList<>();
        if (seedText == null) return out;
        Matcher m = OBJECTIVE.matcher(seedText);
        if (!m.find()) return out;
        String objective = m.group(1).replaceAll("\\s+", " ").strip();
        for (String part : SeedRequirements.splitOutsideBracketsAndQuotes(objective, ",;")) {
            String clause = part.strip().replaceAll("[,;.]$", "").replaceFirst("(?i)^and\\s+", "").strip();
            if (!clause.isEmpty()) out.add(clause);
        }
        return out;
    }

    /**
     * The objective clauses no obligation line carries. A clause is carried by a line sharing at
     * least half of the clause's distinctive words, and never fewer than two of them.
     */
    public static List<String> missing(String seedText, List<String> obligationLines) {
        List<String> missing = new ArrayList<>();
        for (String clause : clausesOf(seedText)) {
            Set<String> want = words(clause);
            if (want.isEmpty()) continue;
            boolean carried = obligationLines != null && obligationLines.stream().anyMatch(line -> {
                Set<String> have = words(line);
                long shared = want.stream().filter(have::contains).count();
                return shared >= Math.min(2, want.size()) && shared * 2 >= want.size();
            });
            if (!carried) missing.add(clause);
        }
        return missing;
    }

    private static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() <= 3 || STOP.contains(w)) continue;
            out.add(stem(w));
        }
        return out;
    }

    /** Plural and third-person endings only: "passes" -> "pass", "tests" -> "test", "pass" stays. */
    private static String stem(String w) {
        if (w.endsWith("sses") || w.endsWith("shes") || w.endsWith("ches") || w.endsWith("xes")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 4) return w.substring(0, w.length() - 1);
        return w;
    }
}
