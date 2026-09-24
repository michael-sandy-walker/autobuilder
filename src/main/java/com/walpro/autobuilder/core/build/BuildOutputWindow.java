package com.walpro.autobuilder.core.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * T1-GE: the bounded view of a build's output that every {@link BuildOutcome#stderrTail()} is
 * made from — the FIRST {@code headLines} lines and the LAST {@code tailLines}, with one marker
 * line between them naming how many were dropped.
 *
 * <p>It replaces a trailing-only window, and the reason is an ordering fact about compilers, not a
 * size preference. A compiler reports the cause before the cascade: an unresolvable import is
 * reported before every use of a type from it fails, and a build tool then repeats the list and
 * closes with its help text. A trailing window therefore drops the cause FIRST. Run
 * {@code 2026-09-21_19-39-14} showed the cost: six consecutive probes over a tree whose skeleton
 * imported a package the project never declared were classified {@code UNKNOWN}, because the
 * {@code package ... does not exist} line had been cut from every one of them, while the package
 * gate — whose output happened to be short enough to survive whole — classified the same defect
 * correctly 22 minutes later. Across the archived runs, 31% of failing builds hit the old cap.
 *
 * <p>The tail stays because the build tool's own summary (the total error count that
 * {@code CompileProbeService.distinctErrorCount} prefers) prints at the end.
 */
public final class BuildOutputWindow {

    /** The window every build outcome uses: the old tail, plus an equal head. */
    public static final int DEFAULT_HEAD_LINES = 50;
    public static final int DEFAULT_TAIL_LINES = 50;

    private final int headLines;
    private final int tailLines;
    private final List<String> head = new ArrayList<>();
    private final Deque<String> tail = new ArrayDeque<>();
    private int dropped;

    public BuildOutputWindow() {
        this(DEFAULT_HEAD_LINES, DEFAULT_TAIL_LINES);
    }

    public BuildOutputWindow(int headLines, int tailLines) {
        if (headLines < 0 || tailLines < 0) {
            throw new IllegalArgumentException("window sizes must be non-negative");
        }
        this.headLines = headLines;
        this.tailLines = tailLines;
    }

    /*
     * Terminal colour escapes are stripped on the way in, with the classifier's pattern (one home).
     * A build tool that decides it is talking to a terminal wraps every level marker in them, and
     * they then travelled verbatim into the evaluation prompt's build block: 600 to 3000 escape
     * sequences per run across the archive, tokens the model paid for that carried nothing.
     */

    /** Appends one line. Thread-confined: the drainer thread writes, the caller reads after join. */
    public synchronized void add(String line) {
        if (line != null && line.indexOf('\u001B') >= 0) {
            line = BuildErrorClassifier.ANSI_ESCAPE.matcher(line).replaceAll("");
        }
        if (head.size() < headLines) {
            head.add(line);
            return;
        }
        tail.addLast(line);
        if (tail.size() > tailLines) {
            tail.removeFirst();
            dropped++;
        }
    }

    /** Lines retained, excluding the marker. */
    public synchronized int retainedLines() {
        return head.size() + tail.size();
    }

    /** Lines seen in total, retained or not. */
    public synchronized int totalLines() {
        return head.size() + tail.size() + dropped;
    }

    public synchronized String render() {
        List<String> out = new ArrayList<>(head);
        if (dropped > 0) {
            out.add(omissionMarker(dropped));
        }
        out.addAll(tail);
        return String.join("\n", out);
    }

    /**
     * Carries no build-tool prefix, file coordinate or diagnostic keyword, so no extractor or
     * classifier reading the rendered window can mistake it for a diagnostic.
     */
    static String omissionMarker(int count) {
        return "... (" + count + " line(s) of build output omitted) ...";
    }
}
