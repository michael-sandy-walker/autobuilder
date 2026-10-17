package com.walpro.autobuilder.core.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T1-FR: parses compile stderr into the set of whole TYPES the code references but no file
 * defines. Sister to {@link MissingMemberExtractor}, which handles a missing member on a type that
 * already exists.
 *
 * <p>Recognised shape — {@code cannot find symbol} followed by {@code symbol: class T}, with the
 * referencing file taken from the diagnostic's own path prefix:
 *
 * <pre>
 * [ERROR] /…/LoanController.java:[20,9] cannot find symbol
 * [ERROR]   symbol:   class Loan
 * </pre>
 *
 * <p>This extractor reports what the compiler said and nothing more. Deciding which of those
 * tokens are the project's own to write — as against a library type that wants an import, or an
 * annotation — is the prompt block's job, because only it can see the tree.
 */
public final class MissingTypeExtractor {

    /**
     * A type the compiler could not resolve, one file that referenced it, and -- when javac looked for it INSIDE a
     * class ({@code location: class a.b.Owner}, the code wrote {@code Owner.T}) -- that class's name; null when it
     * looked in a package or the scope of the file.
     */
    public record Entry(String typeName, String referencedBy, String insideClass) {
        public Entry(String typeName, String referencedBy) {
            this(typeName, referencedBy, null);
        }
    }

    private static final Pattern ERROR_FILE_LINE = Pattern.compile(
            "(\\S+\\.java):\\[?\\d+");

    private static final Pattern CLASS_SYMBOL_LINE = Pattern.compile(
            "symbol:\\s*class\\s+([A-Za-z_$][\\w$]*)",
            Pattern.CASE_INSENSITIVE);

    /** The line after the symbol: where javac looked. A class here means a nested type was asked of it. */
    private static final Pattern LOCATION_CLASS_LINE = Pattern.compile(
            "location:\\s*(?:class|interface|enum|record)\\s+([\\w.$]+)");

    private MissingTypeExtractor() {}

    /**
     * Every distinct missing type named in the stderr, in order of first appearance, each paired
     * with the first file seen referencing it. Empty when stderr is blank or names none.
     */
    public static List<Entry> extract(String stderr) {
        if (stderr == null || stderr.isBlank()) return List.of();
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");

        Map<String, Entry> dedup = new LinkedHashMap<>();
        String currentFile = null;
        String lastType = null;
        for (String line : plain.split("\\R")) {
            Matcher fm = ERROR_FILE_LINE.matcher(line);
            if (fm.find()) {
                currentFile = fm.group(1);
                // fall through — javac puts the path and `cannot find symbol` on one line, and the
                // symbol line follows; a path line never also carries the symbol.
            }
            Matcher sm = CLASS_SYMBOL_LINE.matcher(line);
            if (sm.find()) {
                String type = sm.group(1);
                dedup.putIfAbsent(type, new Entry(type, currentFile));
                lastType = type;
                continue;
            }
            // field77 (rule-extraction, 2026-10-17): "symbol: class Endpoint / location: class ...EndpointSurfaceQuery"
            // -- the code named EndpointSurfaceQuery.Endpoint, a nested type the change moves out to a record of its own.
            Matcher lm = LOCATION_CLASS_LINE.matcher(line);
            if (lm.find() && lastType != null) {
                Entry e = dedup.get(lastType);
                if (e != null && e.insideClass() == null) dedup.put(lastType, new Entry(e.typeName(), e.referencedBy(), lm.group(1)));
            }
            if (!line.isBlank()) lastType = null;
        }
        return new ArrayList<>(dedup.values());
    }
}
