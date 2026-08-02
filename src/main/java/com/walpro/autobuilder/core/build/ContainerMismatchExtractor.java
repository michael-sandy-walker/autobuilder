package com.walpro.autobuilder.core.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2-FD (second shape): parses compile stderr for the OTHER way the tree disagrees about whether a
 * value is wrapped — a plain value supplied where the container of that same value is expected.
 *
 * <pre>
 * [ERROR] …/LoanService.java:[41,68] incompatible types:
 *         com.example.library.model.Member cannot be converted to
 *         java.util.Optional&lt;com.example.library.model.Member&gt;
 * </pre>
 *
 * <p>Its sibling shape — {@link MissingMemberExtractor#unwrappedContainerCalls} — is the same
 * confusion seen from the caller's end, where a container method is chained onto the plain value
 * and javac reports a missing method. Measured 2026-08-02, the two barely overlap: only 6 of 70
 * container-method diagnostics co-occur with an incompatible-types line, so each is mostly its own
 * occurrence. This shape is the larger one — **169 cycles across 15 runs** (counted per CYCLE, not
 * per log line: the raw line count is 936 and inflated by re-rendering, the trap
 * [[feedback-count-events-not-log-lines]] records).
 *
 * <p>Only a mismatch whose element type equals the plain type is reported. {@code Book} against
 * {@code Optional<Book>} is a wrapping disagreement; {@code Book} against {@code List<String>} is
 * an ordinary type error and belongs to the compile-error relay.
 */
public final class ContainerMismatchExtractor {

    /** A plain value used where {@code container&lt;plainType&gt;} was expected. */
    public record Entry(String plainType, String containerType, String referencedBy) {}

    private static final Pattern ERROR_FILE_LINE = Pattern.compile("(\\S+\\.java):\\[?\\d+");

    private static final Pattern MISMATCH = Pattern.compile(
            "incompatible types:\\s*([\\w.]+)\\s+cannot be converted to\\s+([\\w.]+)<([\\w.]+)>",
            Pattern.CASE_INSENSITIVE);

    private ContainerMismatchExtractor() {}

    /** Every distinct wrapping disagreement in the stderr, in order of first appearance. */
    public static List<Entry> extract(String stderr) {
        if (stderr == null || stderr.isBlank()) return List.of();
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");

        Map<String, Entry> dedup = new LinkedHashMap<>();
        String currentFile = null;
        for (String line : plain.split("\\R")) {
            Matcher fm = ERROR_FILE_LINE.matcher(line);
            if (fm.find()) currentFile = fm.group(1);
            Matcher mm = MISMATCH.matcher(line);
            if (!mm.find()) continue;
            String plainType = lastSegment(mm.group(1));
            String container = lastSegment(mm.group(2));
            String element = lastSegment(mm.group(3));
            // Same element type on both sides — otherwise this is an ordinary type error.
            if (!plainType.equals(element)) continue;
            dedup.putIfAbsent(plainType + "|" + container,
                    new Entry(plainType, container, currentFile));
        }
        return new ArrayList<>(dedup.values());
    }

    private static String lastSegment(String fq) {
        int dot = fq.lastIndexOf('.');
        return dot < 0 ? fq : fq.substring(dot + 1);
    }
}
