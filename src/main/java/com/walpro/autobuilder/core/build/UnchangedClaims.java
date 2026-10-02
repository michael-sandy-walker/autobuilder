package com.walpro.autobuilder.core.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a criterion's WHOLE claim is that something was NOT changed -- and whether the changes since
 * adoption prove it ({@link AdoptionManifest}). The twin of {@link TestRunClaims}: a claim with an
 * authority other than a reading is answered by that authority.
 *
 * <p>Narrow on purpose, because its answer stands over a reading's either way ({@link #answer}, the user's
 * decision 2026-10-02: the record answers first). The claim may be several such parts joined by ";" or
 * ", and" -- every part must be one of these shapes; anything else is left to the reader:
 * <ul>
 *   <li>"no file(s)/class(es) under P is/are/has/have been modified/changed/touched"</li>
 *   <li>"only X[.java] (under P) is/was/has been modified/changed"</li>
 *   <li>"all/every other class(es)/file(s) (under P) remain(s) unchanged" -- other than the classes the
 *       criterion or the goal names</li>
 *   <li>"the project's packages, classes and naming are preserved" / "... are kept" -- nothing the
 *       adopted tree had was removed or renamed</li>
 * </ul>
 */
public final class UnchangedClaims {

    private static final String PATH = "`?([\\w./*-]+?)`?";
    private static final Pattern NONE_UNDER = Pattern.compile(
            "no (?:files?|class(?:es)?|tests?|sources?)(?: files?)? (?:under|in) " + PATH
                    + "(?: (?:is|are|was|were|has|have))?(?: been)? (?:modified|changed|touched|altered)");
    private static final Pattern ONLY = Pattern.compile(
            "(?i)only (\\w+)(?:\\.java)?(?: (?:under|in) ([\\w./*-]+?))? (?:is|was|has been|gets) (?:modified|changed)");
    private static final Pattern ALL_OTHER = Pattern.compile(
            "(?:all|every) other (?:class(?:es)?|files?|sources?)(?: (?:under|in) " + PATH + ")? (?:remains?|stays?|(?:is|are) kept|(?:is|are)) (?:unchanged|as (?:it|they) (?:is|are|was|were))");
    private static final Pattern PRESERVED = Pattern.compile(
            "(?:the )?project'?s? packages,? classes,? and naming (?:are|is) (?:preserved|kept|unchanged)");
    private static final Pattern NAMED_CLASS = Pattern.compile("\\b([A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+)\\b");

    /**
     * The claim written the way this class proves it, one per shape, with {@code <path>} and {@code <Class>}
     * to fill in. The planner is given exactly these (PlannerProtocolContract): the patterns above were
     * matched against free phrasing and, over the archive (2026-09-30: 10,690 criteria in 227 runs),
     * recognised 23 of the 44 whole no-change claims -- "the diff shows that only X is modified", "X, Y and
     * Z are not modified", "the package structure and naming conventions are preserved" all went to a
     * reader. A template the prompt states is what makes the phrasing match; the patterns stay for the rest.
     */
    public static final List<String> TEMPLATES = List.of(
            "No file under <path> is changed",
            "Only <Class> under <path> is changed",
            "All other classes under <path> remain unchanged",
            "The project's packages, classes and naming are preserved");

    private UnchangedClaims() {
    }

    /**
     * True when {@code criterion} claims only that things were not changed, and {@code changes} shows
     * exactly that. {@code goal} is the goal's own text: a class it names is "the one to change".
     */
    public static boolean provenBy(String criterion, AdoptionManifest.Changes changes, String goal) {
        if (criterion == null || changes == null) return false;
        String claim = criterion.strip().replaceAll("[.\\s]+$", "");
        String[] parts = claim.split("\\s*;\\s*|,\\s+and\\s+(?=(?:no|only|all|every|the)\\b)");
        if (parts.length == 0) return false;
        for (String part : parts) {
            if (!partProven(part.strip(), changes, goal)) return false;
        }
        return true;
    }

    /**
     * The record's answer to {@code criterion}: TRUE when it is a no-change claim the changes prove, FALSE
     * when it is one they contradict, null when it is not such a claim (a reading answers it).
     */
    public static Boolean answer(String criterion, AdoptionManifest.Changes changes, String goal) {
        if (changes == null || !isClaim(criterion)) return null;
        return provenBy(criterion, changes, goal);
    }

    /**
     * Whether {@code criterion} is, as a whole, a claim that something was NOT changed -- every part one
     * of the shapes above. Shape only: says nothing about whether it holds.
     */
    public static boolean isClaim(String criterion) {
        if (criterion == null || criterion.isBlank()) return false;
        String claim = criterion.strip().replaceAll("[.\\s]+$", "");
        for (String part : claim.split("\\s*;\\s*|,\\s+and\\s+(?=(?:no|only|all|every|the)\\b)")) {
            String p = part.strip();
            String lower = p.toLowerCase(Locale.ROOT).replace("`", "");
            if (!(NONE_UNDER.matcher(lower).matches() || ONLY.matcher(p.replace("`", "")).matches()
                    || ALL_OTHER.matcher(lower).matches() || PRESERVED.matcher(lower).matches())) {
                return false;
            }
        }
        return true;
    }

    private static boolean partProven(String part, AdoptionManifest.Changes changes, String goal) {
        String lower = part.toLowerCase(Locale.ROOT).replace("`", "");
        Matcher none = NONE_UNDER.matcher(lower);
        if (none.matches()) {
            String scope = none.group(1);
            return changes.changedOrAdded().stream().noneMatch(p -> under(p, scope))
                    && changes.removed().stream().noneMatch(p -> under(p, scope));
        }
        Matcher only = ONLY.matcher(part.replace("`", ""));
        if (only.matches()) {
            String name = only.group(1);
            String scope = only.group(2);
            return changes.changedOrAdded().stream().filter(p -> scope == null || under(p, scope))
                    .allMatch(p -> simpleName(p).equals(name)) && changes.removed().isEmpty();
        }
        Matcher other = ALL_OTHER.matcher(lower);
        if (other.matches()) {
            String scope = other.group(1);
            List<String> allowed = named(part + " " + (goal == null ? "" : goal));
            return changes.changedOrAdded().stream().filter(p -> scope == null || under(p, scope))
                    .allMatch(p -> allowed.contains(simpleName(p))) && changes.removed().isEmpty();
        }
        if (PRESERVED.matcher(lower).matches()) {
            return changes.removed().isEmpty();
        }
        return false;
    }

    private static boolean under(String path, String scope) {
        String s = scope.replaceAll("/\\*+$", "").replaceAll("/$", "");
        return path.equals(s) || path.startsWith(s + "/");
    }

    private static String simpleName(String path) {
        String f = path.substring(path.lastIndexOf('/') + 1);
        return f.contains(".") ? f.substring(0, f.indexOf('.')) : f;
    }

    private static List<String> named(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = NAMED_CLASS.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
