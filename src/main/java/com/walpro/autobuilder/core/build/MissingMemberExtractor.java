package com.walpro.autobuilder.core.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-BB: parses Maven compile stderr into the set of missing-member entries the next mutation
 * must add to existing source files. Sister extractor to {@link CompileErrorSymbolExtractor}
 * (which reports missing whole-class / package symbols).
 *
 * <p>Recognised shapes:
 * <ul>
 *   <li><b>Missing constructor</b> — {@code no suitable constructor found for T(argTypes)}.
 *       Single-line; the target type is in the message itself.</li>
 *   <li><b>Missing method</b> — {@code cannot find symbol} followed by
 *       {@code symbol: method m(argTypes)} and {@code location: class|interface T}.
 *       Multi-line; the target type is on the {@code location:} line.</li>
 * </ul>
 *
 * <p>Each entry names the simple type that should grow a new member, the member kind, the
 * member name (constructor name = type name), and the argument-type list verbatim. The
 * {@code MissingMemberPromptBlock} resolves the simple type to a file path in
 * {@code previousOutput()} and surfaces the gap to the evolution LLM.
 *
 * <p>Out of scope: ambiguous calls, overload mismatches that the compiler reports under a
 * different message shape, and member-access errors on third-party types (the
 * {@code definedSymbolsInOutput()} resolver check in the prompt block filters those).
 */
public final class MissingMemberExtractor {

    public enum Kind { CONSTRUCTOR, METHOD }

    public record Entry(Kind kind, String targetType, String memberName, String argTypes) {}

    private static final Pattern CONSTRUCTOR_LINE = Pattern.compile(
            "no suitable constructor found for\\s+([\\w.]+)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern METHOD_SYMBOL_LINE = Pattern.compile(
            "symbol:\\s*method\\s+(\\w+)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern METHOD_LOCATION_LINE = Pattern.compile(
            "location:\\s*(?:class|interface)\\s+([\\w.]+)",
            Pattern.CASE_INSENSITIVE);

    private MissingMemberExtractor() {}

    /**
     * Extract every distinct missing-member entry named in the stderr, in order of first
     * appearance. Returns an empty list when stderr is blank or no member-shaped lines are
     * present. Deduplication key is {@code (kind, targetType, memberName, argTypes)}.
     */
    public static List<Entry> extract(String stderr) {
        if (stderr == null || stderr.isBlank()) return List.of();
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");

        Map<String, Entry> dedup = new LinkedHashMap<>();

        Matcher cm = CONSTRUCTOR_LINE.matcher(plain);
        while (cm.find()) {
            String fqOrSimple = cm.group(1);
            String simple = lastSegment(fqOrSimple);
            String args = normaliseArgs(cm.group(2));
            Entry entry = new Entry(Kind.CONSTRUCTOR, simple, simple, args);
            dedup.putIfAbsent(key(entry), entry);
        }

        // Method shapes pair a `symbol: method m(args)` line with a following
        // `location: class T` line. Walk the plaintext linearly so each symbol/location pair
        // stays associated. A symbol line without a matching location line is skipped — the
        // single-class-resolver path (T4-AZ) handles bare missing symbols.
        String[] lines = plain.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher sm = METHOD_SYMBOL_LINE.matcher(lines[i]);
            if (!sm.find()) continue;
            String methodName = sm.group(1);
            String args = normaliseArgs(sm.group(2));
            String target = null;
            for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                Matcher lm = METHOD_LOCATION_LINE.matcher(lines[j]);
                if (lm.find()) {
                    target = lastSegment(lm.group(1));
                    break;
                }
            }
            if (target == null) continue;
            Entry entry = new Entry(Kind.METHOD, target, methodName, args);
            dedup.putIfAbsent(key(entry), entry);
        }

        return new ArrayList<>(dedup.values());
    }

    private static String lastSegment(String fq) {
        int dot = fq.lastIndexOf('.');
        return dot < 0 ? fq : fq.substring(dot + 1);
    }

    private static String normaliseArgs(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s+", "").trim();
    }

    private static String key(Entry e) {
        return e.kind() + "|" + e.targetType() + "|" + e.memberName() + "|" + e.argTypes();
    }
}
