package com.walpro.autobuilder.core.build;

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
 * <p>One home for "a build ran and took N ms" — call sites do not each maintain a counter (the
 * immutable {@code CycleContextData} is not threaded back through the completion-edge gates, so a
 * context field would silently miss those builds). Read at {@code cycle_end} emit and at a
 * session-boundary summary.
 */
@Component
public class BuildCostTracker {

    /** Bound the per-cycle map so a long-lived session cannot grow it without limit; only the
     *  current cycle is ever read (at its own cycle_end), so eviction of old cycles is lossless. */
    private static final int MAX_TRACKED_CYCLES = 1000;

    private final AtomicLong sessionBuildMs = new AtomicLong(0);
    private final AtomicLong sessionInvocations = new AtomicLong(0);

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
        if (durationMs <= 0) {
            return;
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

    public long sessionInvocations() {
        return sessionInvocations.get();
    }
}
