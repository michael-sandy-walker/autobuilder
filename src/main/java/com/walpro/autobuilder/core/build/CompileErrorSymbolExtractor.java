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

    // class/variable: bare identifier or FQN (no parens, no spaces) — captured in group 1.
    // method:        identifier followed by an optional paren-balanced parameter list
    //                (may contain spaces, commas, generics, `<nulltype>` placeholders) —
    //                captured in group 2. Without the explicit paren capture, the original
    //                `\S+` form combined with a too-greedy `trimTrailingPunctuation`
    //                stripped the closing paren and produced garbled symbols like
    //                `setLoanDate(java.time.LocalDateTime` (run 2026-05-24_17-16-49).
    private static final Pattern SYMBOL_LINE = Pattern.compile(
            "symbol:\\s*(?:(?:class|variable)\\s+(\\S+)"
                    + "|method\\s+(\\w+(?:\\([^)]*\\))?))",
            Pattern.CASE_INSENSITIVE);

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
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            String name = trimTrailingPunctuation(raw);
            if (!name.isBlank()) out.add(name);
        }
        Matcher pm = PACKAGE_LINE.matcher(plain);
        while (pm.find()) {
            String name = trimTrailingPunctuation(pm.group(1));
            if (!name.isBlank()) out.add(name);
        }
        return out;
    }

    /** Strips trailing javac line punctuation ({@code , . ; :}) but never structural
     *  delimiters — closing/opening parens are part of method signatures and removing them
     *  silently corrupts the symbol (see header comment on {@link #SYMBOL_LINE}). */
    private static String trimTrailingPunctuation(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ',' || c == '.' || c == ';' || c == ':') end--;
            else break;
        }
        return s.substring(0, end);
    }
}
