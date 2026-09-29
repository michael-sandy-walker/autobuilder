package com.walpro.autobuilder.core.stuck;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * T4-DY: whether a failure the loop keeps OBSERVING has held long enough to be a stuck case -- judged by
 * ratios over the run itself, never by a fixed count (user, 2026-09-29: "Don't make '3 consecutive tests
 * failed' fixed ... use ratios").
 *
 * <p>A signature is persistent when it appears in at least {@link #RECURRENCE_SHARE} of the observations
 * since it first appeared, and those sightings span at least {@link #SPAN_SHARE} of the time the run has
 * been going. One sighting spans nothing, so it never qualifies; the longer the run, the longer a failure
 * must hold. A clean observation counts too -- it lowers the share.
 *
 * <p>One home for the rule: the test gate's failing sets and the boot gate's findings both use it.
 */
public final class Recurrence {

    /** The share of observations, since a signature first appeared, that must show it again. */
    public static final double RECURRENCE_SHARE = 2.0 / 3.0;

    /** How much of the run so far a signature's sightings must span, first to now. */
    public static final double SPAN_SHARE = 0.25;

    /** One observation: when, and which failure it showed (null: none). */
    public record Sighting(long at, String signature) {}

    private final List<Sighting> history = new CopyOnWriteArrayList<>();
    private final long runStartedAt;

    /** A tracker for a run that began at {@code runStartedAt} (epoch millis). */
    public Recurrence(long runStartedAt) {
        this.runStartedAt = runStartedAt;
    }

    /** A tracker for this JVM's run: the JVM is one run. */
    public static Recurrence ofThisRun() {
        return new Recurrence(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    /** Records one observation now; true when its signature is persistent. A clean observation (null) is false. */
    public boolean observe(String signature) {
        return observe(signature, System.currentTimeMillis());
    }

    public boolean observe(String signature, long at) {
        history.add(new Sighting(at, signature));
        return signature != null && persistent(signature, history, runStartedAt, at);
    }

    public static boolean persistent(String signature, List<Sighting> history, long runStart, long now) {
        int first = -1;
        for (int i = 0; i < history.size(); i++) {
            if (signature.equals(history.get(i).signature())) { first = i; break; }
        }
        if (first < 0) return false;
        List<Sighting> since = history.subList(first, history.size());
        long same = since.stream().filter(f -> signature.equals(f.signature())).count();
        long span = now - history.get(first).at();
        long elapsed = Math.max(1, now - runStart);
        return same / (double) since.size() >= RECURRENCE_SHARE && span / (double) elapsed >= SPAN_SHARE;
    }
}
