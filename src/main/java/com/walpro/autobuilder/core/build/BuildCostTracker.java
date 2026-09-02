package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * T5-P: session- and cycle-scoped accumulation of build wall-clock, recorded at the single
 * {@link BuildExecutor#execute} chokepoint every build invocation passes through (pre-merge probe,
 * inner-loop re-probes, post-merge gate, completion-edge package/test gates, the javac-direct
 * probe). {@link BuildOutcome#durationMs()} exists per invocation but is never aggregated; a run
 * that spent 40 minutes building looks the same in the trace as one that spent 4.
 *
 * <p>One home for "an operation ran and took N ms" — call sites do not each maintain a counter (the
 * immutable {@code CycleContextData} is not threaded back through the completion-edge gates, so a
 * context field would silently miss those builds). Read at {@code cycle_end} emit and at a
 * session-boundary summary.
 *
 * <p><b>T6-J: widened from builds to named CATEGORIES, because builds were never the expensive
 * part.</b> Accounting run {@code 2026-07-30_21-25-43} against the 23/23 run of {@code 07-28}:
 * LLM calls and builds together explain 18.9 of 88.6 minutes, and 26.8 of 82 — so 70 and 55
 * minutes respectively were spent somewhere nothing measured. Over the same interval the loop's
 * cycle time doubled (77s → 153s median) while package/boot/probe gate operations went from 7
 * across 62 cycles to 23 across 35. Those gates package an artifact and launch a JVM with a
 * readiness wait of up to 60s, and not one of them was timed. A category is added rather than a
 * second tracker so "what did this run spend its wall-clock on" has a single answer.
 */
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "session build accounting; rewound builds were still run")
public class BuildCostTracker {

    /** Bound the per-cycle map so a long-lived session cannot grow it without limit; only the
     *  current cycle is ever read (at its own cycle_end), so eviction of old cycles is lossless. */
    private static final int MAX_TRACKED_CYCLES = 1000;

    /** The category every pre-T6-J call site records under, so existing readings keep their meaning. */
    public static final String BUILD = "build";

    private final AtomicLong sessionBuildMs = new AtomicLong(0);
    private final AtomicLong sessionInvocations = new AtomicLong(0);

    /** category -> {totalMs, invocations}. Insertion-ordered so the readout is stable. */
    private final Map<String, long[]> perCategory = Collections.synchronizedMap(new LinkedHashMap<>());

    private final Map<String, long[]> perCycle = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                    return size() > MAX_TRACKED_CYCLES;
                }
            });

    /** Record one build invocation's wall-clock. Zero/negative durations (disabled executor,
     *  skipped no-op) are ignored so they neither inflate the session total nor the invocation
     *  count. A null cycleId (skipped path) still contributes to the session total. */
    public void record(String cycleId, long durationMs) {
        record(cycleId, BUILD, durationMs);
    }

    /**
     * T6-J: record one operation's wall-clock under a named category. {@link #BUILD} keeps the
     * original session totals exactly as they were, so the T5-P readings stay comparable across
     * the change; every other category is additive.
     */
    public void record(String cycleId, String category, long durationMs) {
        if (durationMs <= 0) {
            return;
        }
        String key = category == null || category.isBlank() ? BUILD : category;
        synchronized (perCategory) {
            long[] agg = perCategory.computeIfAbsent(key, k -> new long[2]);
            agg[0] += durationMs;
            agg[1] += 1;
        }
        if (!BUILD.equals(key)) {
            return; // only builds feed the T5-P session totals below
        }
        sessionBuildMs.addAndGet(durationMs);
        sessionInvocations.incrementAndGet();
        if (cycleId != null) {
            synchronized (perCycle) {
                long[] agg = perCycle.computeIfAbsent(cycleId, k -> new long[2]);
                agg[0] += durationMs;
                agg[1] += 1;
            }
        }
    }

    /** Build wall-clock accumulated for one cycle (0 if none ran or the cycle was evicted). */
    public long cycleBuildMs(String cycleId) {
        if (cycleId == null) {
            return 0L;
        }
        long[] agg = perCycle.get(cycleId);
        return agg == null ? 0L : agg[0];
    }

    /** Number of build invocations in one cycle. */
    public int cycleInvocations(String cycleId) {
        if (cycleId == null) {
            return 0;
        }
        long[] agg = perCycle.get(cycleId);
        return agg == null ? 0 : (int) agg[1];
    }

    public long sessionBuildMs() {
        return sessionBuildMs.get();
    }

    /** Total wall-clock recorded under {@code category}, 0 when nothing recorded there. */
    public long sessionMs(String category) {
        synchronized (perCategory) {
            long[] agg = perCategory.get(category);
            return agg == null ? 0L : agg[0];
        }
    }

    /** How many operations were recorded under {@code category}. */
    public long sessionCount(String category) {
        synchronized (perCategory) {
            long[] agg = perCategory.get(category);
            return agg == null ? 0L : agg[1];
        }
    }

    /**
     * T6-J: {@code category=<ms>/<count>} for every category with activity, most expensive first.
     * Rendered into the log at a session boundary so a run's wall-clock is attributable after the
     * fact — the property whose absence left 70 of 89 minutes unexplained.
     */
    public String costReadout() {
        java.util.List<Map.Entry<String, long[]>> entries;
        synchronized (perCategory) {
            entries = new java.util.ArrayList<>(perCategory.entrySet());
        }
        entries.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, long[]> e : entries) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(e.getKey()).append('=').append(e.getValue()[0]).append("ms/").append(e.getValue()[1]);
        }
        return sb.length() == 0 ? "(nothing recorded)" : sb.toString();
    }

    public long sessionInvocations() {
        return sessionInvocations.get();
    }
}
