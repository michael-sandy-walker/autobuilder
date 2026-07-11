package com.walpro.autobuilder.observability;

import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * T4-BO: measures signal lifetimes without touching decay behaviour — "observe first, tune
 * second." ~20 signal types carry hand-picked {@code decayPerCycle} constants nobody has
 * re-validated against real run behaviour; this tracker answers "how long does each type
 * actually survive, and why did it end" so a future tuning pass (T4-J) has real data instead of
 * more guesses.
 *
 * <p>One call per cycle, wrapping {@code CycleContextData.tickSignals(...)}: pass the signal
 * map from immediately BEFORE the tick (reflects everything this cycle's agents did — including
 * any explicit {@code withoutSignal} clears) and immediately AFTER (reflects natural decay). The
 * two snapshots are enough to attribute every ending to exactly one of two causes without hooking
 * the ~70 call sites that clear signals directly:
 * <ul>
 *   <li><b>cleared</b> — a type tracked as alive at the end of the PREVIOUS cycle is already
 *       absent from THIS cycle's pre-tick map: some agent called {@code withoutSignal} on it
 *       during this cycle's execution, before the tick ran.</li>
 *   <li><b>decayed</b> — a type present in this cycle's pre-tick map is absent from the
 *       post-tick map: {@code tick()} pushed its strength below the survival threshold.</li>
 * </ul>
 *
 * <p>{@code emitCycle}/{@code deathCycle} are this tracker's own monotonic counter (incremented
 * once per {@link #observe} call) — NOT {@code CycleContextData.cycleName()}, which is a
 * timestamp string. A signal type still alive when the JVM stops never produces an end-of-lifecycle event;
 * the offline analyser ({@code scripts/signal_lifetime_report.py}) infers a synthetic
 * {@code sessionEnd} row for those from the last observed snapshot, so no shutdown hook is
 * needed here.
 *
 * <p>Sub-cycle churn on one type (cleared and re-emitted within the same cycle, before the next
 * {@link #observe} call) is invisible to a once-per-cycle observer — an accepted limitation,
 * since "cycles to death" is the unit the ticket asks for. See {@code project_t4bo_design_note.md}
 * for the full algorithmic reasoning (verified against {@code Signal.tick()}'s actual semantics).
 */
@Component
public class SignalLifetimeTracker {

    /** One completed lifetime episode: how and when an entry left its carrier. */
    public record LifecycleEnd(SignalType signalType, int emitCycle, int endCycle, String endReason,
                        String emittedBySource) {}

    private record Emission(int emitCycle, String source) {}

    private int cycleIndex = 0;

    /** T4-BZ-5: one call per cycle boundary (the request observation increments the counter). */
    private final Map<SignalType, Emission> activeRequests = new HashMap<>();

    /**
     * T4-BZ-3 (I9 re-scope): the same two-snapshot attribution for the request channel —
     * request lifecycles end as "consumed" (gone from the pre-tick open map: the addressed
     * mechanism acted, or the subgoal scope dropped it) or "expired" (removed by this cycle's
     * channel tick: validity horizon passed, or verified early resolution). Richer than the
     * decayed/cleared pair the stored types had — expiry is the formerly-silent decay made
     * visible. Shares {@code cycleIndex} with {@link #observe}; call both once per boundary,
     * {@link #observe} first (it increments the counter).
     *
     * @param preTickOpen  open requests immediately before the channel tick
     * @param postTickOpen open requests immediately after
     */
    public List<LifecycleEnd> observeRequests(Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> preTickOpen,
                                       Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> postTickOpen) {
        cycleIndex++;
        Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> pre =
                preTickOpen != null ? preTickOpen : Map.of();
        Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> post =
                postTickOpen != null ? postTickOpen : Map.of();
        List<LifecycleEnd> ended = new ArrayList<>();

        for (Iterator<Map.Entry<SignalType, Emission>> it = activeRequests.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<SignalType, Emission> e = it.next();
            if (!pre.containsKey(e.getKey())) {
                ended.add(new LifecycleEnd(e.getKey(), e.getValue().emitCycle(), cycleIndex, "consumed", e.getValue().source()));
                it.remove();
            }
        }

        for (Map.Entry<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> e : pre.entrySet()) {
            activeRequests.computeIfAbsent(e.getKey(), k -> new Emission(cycleIndex, e.getValue().source()));
        }

        for (Iterator<Map.Entry<SignalType, Emission>> it = activeRequests.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<SignalType, Emission> e = it.next();
            if (!post.containsKey(e.getKey())) {
                ended.add(new LifecycleEnd(e.getKey(), e.getValue().emitCycle(), cycleIndex, "expired", e.getValue().source()));
                it.remove();
            }
        }

        return ended;
    }

    private final Map<String, Emission> activeWindows = new HashMap<>();

    /**
     * T4-BZ-4 (I9 re-scope): the same two-snapshot attribution for participation windows —
     * a role's window lifecycle ends as "closed" (gone from the pre-tick map: revoked by a
     * merge/analysis-unlock, or shrunk-and-replaced mid-cycle) or "expired" (removed by this
     * cycle's window tick: validity horizon passed, or shrunk below the survival threshold).
     * Endings report the role's ROLE_* type name so downstream scripts keep one namespace.
     * Shares {@code cycleIndex}; call after {@link #observe} on the same boundary.
     */
    public List<LifecycleEnd> observeWindows(Map<String, com.walpro.autobuilder.core.lifecycle.ParticipationWindow> preTickActive,
                                      Map<String, com.walpro.autobuilder.core.lifecycle.ParticipationWindow> postTickActive) {
        Map<String, com.walpro.autobuilder.core.lifecycle.ParticipationWindow> pre =
                preTickActive != null ? preTickActive : Map.of();
        Map<String, com.walpro.autobuilder.core.lifecycle.ParticipationWindow> post =
                postTickActive != null ? postTickActive : Map.of();
        List<LifecycleEnd> ended = new ArrayList<>();

        for (Iterator<Map.Entry<String, Emission>> it = activeWindows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Emission> e = it.next();
            if (!pre.containsKey(e.getKey())) {
                ended.add(new LifecycleEnd(com.walpro.autobuilder.core.lifecycle.BasicSignals.roleSignalFor(e.getKey()),
                        e.getValue().emitCycle(), cycleIndex, "closed", e.getValue().source()));
                it.remove();
            }
        }

        for (Map.Entry<String, com.walpro.autobuilder.core.lifecycle.ParticipationWindow> e : pre.entrySet()) {
            activeWindows.computeIfAbsent(e.getKey(), k -> new Emission(cycleIndex, e.getValue().source()));
        }

        for (Iterator<Map.Entry<String, Emission>> it = activeWindows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Emission> e = it.next();
            if (!post.containsKey(e.getKey())) {
                ended.add(new LifecycleEnd(com.walpro.autobuilder.core.lifecycle.BasicSignals.roleSignalFor(e.getKey()),
                        e.getValue().emitCycle(), cycleIndex, "expired", e.getValue().source()));
                it.remove();
            }
        }

        return ended;
    }
}
