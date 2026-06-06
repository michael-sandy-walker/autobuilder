package com.walpro.autobuilder.core.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T4-BD: shared utility for extracting diagnostic lines from build-tool stderr. Replaces three
 * call-site-local filters ({@code BuildResultPromptBlock}, {@code BlockerSubgoalInjector#summariseStderr},
 * {@code EvolutionProtocolContract#lastCompileErrorsBlock}) that each reimplemented the same
 * "pick the informative lines out of Maven's noise" intent with subtly different options.
 *
 * <p>Two entry points:</p>
 * <ul>
 *   <li>{@link #errorLines(String, Options)} — list extractor; flags select ANSI-strip,
 *       prefix filtering, banner skip, trailing-context inclusion, line cap.</li>
 *   <li>{@link #pickBestSingleLine(String)} — single best diagnostic line, prioritised by
 *       classifier-matchable patterns then by broader diagnostic shapes; truncated to 200 chars.</li>
 * </ul>
 */
public final class BuildStderrLines {

    private BuildStderrLines() {}

    /** Reuses {@link BuildErrorClassifier#ANSI_ESCAPE} so there is one pattern in the codebase. */
    public static final Pattern ANSI_ESCAPE = BuildErrorClassifier.ANSI_ESCAPE;

    /**
     * Maven's "COMPILATION ERROR :" / "BUILD FAILURE" framing — non-diagnostic header text that
     * a naive "first {@code [ERROR]} line" picker would otherwise return.
     */
    static final Set<String> NON_DIAGNOSTIC_BANNERS = Set.of(
            "COMPILATION ERROR :",
            "COMPILATION ERROR:",
            "BUILD FAILURE",
            "BUILD ERROR"
    );

    /**
     * Lines matching these patterns produce a non-UNKNOWN {@link BuildErrorClassifier} classification.
     * Tried first by {@link #pickBestSingleLine(String)} so the summary is something downstream
     * classification can identify.
     */
    static final List<Pattern> CLASSIFIER_MATCHABLE_PATTERNS = List.of(
            Pattern.compile("no POM in this directory|could not find a POM", Pattern.CASE_INSENSITIVE),
            Pattern.compile("symbol:\\s*(?:class|variable|method)\\s+\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("package\\s+\\S+\\s+does not exist", Pattern.CASE_INSENSITIVE)
    );

    /**
     * Broader diagnostic shapes — useful as informative summaries even when the classifier
     * returns UNKNOWN. Tried as a second-tier preference by {@link #pickBestSingleLine(String)}.
     */
    static final List<Pattern> DIAGNOSTIC_PATTERNS = List.of(
            Pattern.compile("cannot find symbol", Pattern.CASE_INSENSITIVE),
            Pattern.compile("class\\s+\\S+\\s+is public, should be declared", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[';]\\s*expected", Pattern.CASE_INSENSITIVE),
            Pattern.compile("incompatible types"),
            Pattern.compile("method\\s+\\S+\\s+in (class|interface)"),
            Pattern.compile("error: ", Pattern.CASE_INSENSITIVE)
    );

    public enum Mode {
        /** Pass every line through (subject to other filters). */
        ALL,
        /** Filter to only {@code [ERROR]}-prefixed lines; if none exist, fall back to ALL. */
        PREFER_ERROR_LINES,
        /** Keep only {@code [ERROR]}-prefixed lines; drop everything else. */
        ERROR_LINES_ONLY
    }

    /**
     * Options for {@link #errorLines(String, Options)}.
     *
     * @param stripAnsi           strip ANSI escape sequences before any further processing
     * @param mode                {@link Mode#ALL} / {@link Mode#PREFER_ERROR_LINES} / {@link Mode#ERROR_LINES_ONLY}
     * @param stripErrorPrefix    when a line passes the {@code [ERROR]} filter, strip the prefix from the emitted value
     * @param skipBanners         drop lines whose stripped body matches {@link #NON_DIAGNOSTIC_BANNERS}
     * @param dropEmptyBodies     drop {@code [ERROR]} lines with empty / {@code ":"} body (Maven separator noise)
     * @param withTrailingContext after each retained {@code [ERROR]} line, include the next raw line
     *                            iff it is non-empty and not itself {@code [ERROR]}/{@code [INFO]}
     * @param maxLines            soft cap on returned list size; {@code 0} = unlimited
     * @param returnTail          when {@code maxLines > 0}: {@code true} keeps the last N, {@code false} keeps the first N
     */
    public record Options(
            boolean stripAnsi,
            Mode mode,
            boolean stripErrorPrefix,
            boolean skipBanners,
            boolean dropEmptyBodies,
            boolean withTrailingContext,
            int maxLines,
            boolean returnTail
    ) {
        public Options {
            if (mode == null) mode = Mode.ALL;
            if (maxLines < 0) maxLines = 0;
        }

        /** Preset for the {@code --- BUILD RESULT ---} prompt block: tail of N lines, prefer [ERROR]. */
        public static Options promptBlockTail(int maxLines) {
            return new Options(false, Mode.PREFER_ERROR_LINES, false, false, false, false, maxLines, true);
        }

        /** Preset for the {@code --- LAST COMPILE ERRORS ---} evolution-prompt block. */
        public static Options compileErrorsBlock(int maxLines) {
            return new Options(false, Mode.ERROR_LINES_ONLY, false, false, true, true, maxLines, false);
        }
    }

    /**
     * Extracts diagnostic lines from {@code stderrTail} per {@code opts}. Returns lines verbatim
     * (no per-line truncation) — callers handle any per-line truncation themselves.
     */
    public static List<String> errorLines(String stderrTail, Options opts) {
        if (stderrTail == null || stderrTail.isBlank()) return List.of();
        if (opts == null) opts = new Options(false, Mode.ALL, false, false, false, false, 0, false);

        String[] raw = stderrTail.split("\\r?\\n");

        Mode effectiveMode = opts.mode;
        if (effectiveMode == Mode.PREFER_ERROR_LINES) {
            boolean anyError = false;
            for (String r : raw) {
                if (r != null && (r.contains("[ERROR]") || r.strip().startsWith("ERROR"))) {
                    anyError = true;
                    break;
                }
            }
            effectiveMode = anyError ? Mode.ERROR_LINES_ONLY : Mode.ALL;
        }

        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) {
            String line = raw[i];
            if (line == null) continue;
            String working = opts.stripAnsi ? ANSI_ESCAPE.matcher(line).replaceAll("") : line;
            String trimmed = working.strip();

            boolean isErrorLine = working.contains("[ERROR]") || trimmed.startsWith("ERROR");
            if (effectiveMode == Mode.ERROR_LINES_ONLY && !isErrorLine) continue;

            String emitted;
            if (isErrorLine && (opts.skipBanners || opts.dropEmptyBodies || opts.stripErrorPrefix)) {
                String body = trimmed.startsWith("[ERROR]")
                        ? trimmed.substring("[ERROR]".length()).strip()
                        : trimmed;
                if (opts.dropEmptyBodies && (body.isEmpty() || body.equals(":"))) continue;
                if (opts.skipBanners && NON_DIAGNOSTIC_BANNERS.contains(body)) continue;
                emitted = opts.stripErrorPrefix ? body : (opts.stripAnsi ? working : line);
            } else {
                emitted = opts.stripAnsi ? working : line;
            }
            out.add(emitted);

            if (opts.withTrailingContext && i + 1 < raw.length) {
                String next = raw[i + 1];
                if (next != null) {
                    String nextTrim = next.trim();
                    if (!nextTrim.isEmpty()
                            && !nextTrim.startsWith("[ERROR]")
                            && !nextTrim.startsWith("[INFO]")) {
                        out.add(next);
                    }
                }
            }
        }

        if (opts.maxLines > 0 && out.size() > opts.maxLines) {
            if (opts.returnTail) {
                out = new ArrayList<>(out.subList(out.size() - opts.maxLines, out.size()));
            } else {
                out = new ArrayList<>(out.subList(0, opts.maxLines));
            }
        }

        return out;
    }

    /**
     * Returns the most informative single line from {@code stderrTail}, prioritised as:
     * <ol>
     *   <li>lines matching {@link #CLASSIFIER_MATCHABLE_PATTERNS}</li>
     *   <li>lines matching {@link #DIAGNOSTIC_PATTERNS}</li>
     *   <li>the first informative {@code [ERROR]} line</li>
     *   <li>the first non-empty line of any kind (non-Maven fallback)</li>
     * </ol>
     * ANSI escapes are stripped, {@code [ERROR]} prefix is removed, banner / help-link / empty
     * lines are skipped. Result is truncated to 200 characters.
     */
    public static String pickBestSingleLine(String stderrTail) {
        if (stderrTail == null || stderrTail.isBlank()) return "(no stderr captured)";

        List<String> errorLines = stderrTail.lines()
                .map(line -> ANSI_ESCAPE.matcher(line).replaceAll("").strip())
                .filter(line -> line.startsWith("[ERROR]"))
                .map(line -> line.substring("[ERROR]".length()).strip())
                .filter(stripped -> !stripped.isEmpty()
                        && !stripped.startsWith("-> [Help")
                        && !NON_DIAGNOSTIC_BANNERS.contains(stripped))
                .toList();

        if (!errorLines.isEmpty()) {
            for (String line : errorLines) {
                if (matchesAny(line, CLASSIFIER_MATCHABLE_PATTERNS)) return truncate(line);
            }
            for (String line : errorLines) {
                if (matchesAny(line, DIAGNOSTIC_PATTERNS)) return truncate(line);
            }
            return truncate(errorLines.get(0));
        }

        return stderrTail.lines()
                .map(line -> ANSI_ESCAPE.matcher(line).replaceAll("").strip())
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElseGet(() -> ANSI_ESCAPE.matcher(stderrTail).replaceAll("").strip())
                .transform(BuildStderrLines::truncate);
    }

    private static boolean matchesAny(String line, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(line).find()) return true;
        }
        return false;
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
