package com.walpro.autobuilder.core.build;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2-DN: parses Maven compile stderr into the set of distinct symbol names the next mutation
 * must resolve. {@link BuildErrorClassifier#extractToken} only returns the FIRST missing symbol;
 * this extractor aggregates ALL of them so the evolution prompt and the coverage guard can see
 * the whole gap at once.
 *
 * <p>Scope is intentionally narrow: only {@code symbol: class|variable|method <name>} matches and
 * {@code package X.Y.Z does not exist} lines, the same shapes
 * {@link BuildErrorClassifier} already understands. Anything else stays in
 * {@link BuildErrorClassifier.Kind#UNKNOWN} territory and is handled via the existing LLM path.
 */
public final class CompileErrorSymbolExtractor {

    private static final Pattern SYMBOL_LINE = Pattern.compile(
            "symbol:\\s*(?:class|variable|method)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PACKAGE_LINE = Pattern.compile(
            "package\\s+(\\S+)\\s+does not exist", Pattern.CASE_INSENSITIVE);

    private CompileErrorSymbolExtractor() {}

    /**
     * Extract every distinct missing symbol named in the stderr, in order of first appearance.
     * Returns an empty set when stderr is blank or no symbol-shaped lines are present.
     */
    public static Set<String> extract(String stderr) {
        if (stderr == null || stderr.isBlank()) return Set.of();
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");

        Set<String> out = new LinkedHashSet<>();
        Matcher m = SYMBOL_LINE.matcher(plain);
        while (m.find()) {
            String name = trimTrailingPunctuation(m.group(1));
            if (!name.isBlank()) out.add(name);
        }
        Matcher pm = PACKAGE_LINE.matcher(plain);
        while (pm.find()) {
            String name = trimTrailingPunctuation(pm.group(1));
            if (!name.isBlank()) out.add(name);
        }
        return out;
    }

    private static String trimTrailingPunctuation(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ',' || c == '.' || c == ';' || c == ':' || c == ')' || c == '(') end--;
            else break;
        }
        return s.substring(0, end);
    }
}
