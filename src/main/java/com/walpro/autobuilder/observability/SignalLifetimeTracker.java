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
 * actually survive, and why did it die" so a future tuning pass (T4-J) has real data instead of
 * more guesses.
 *
 * <p>One call per cycle, wrapping {@code CycleContextData.tickSignals(...)}: pass the signal
 * map from immediately BEFORE the tick (reflects everything this cycle's agents did — including
 * any explicit {@code withoutSignal} clears) and immediately AFTER (reflects natural decay). The
 * two snapshots are enough to attribute every death to exactly one of two causes without hooking
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
 * timestamp string. A signal type still alive when the JVM stops never produces a death event;
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

    /** One completed lifetime episode. */
    public record Death(SignalType signalType, int emitCycle, int deathCycle, String deathReason,
                        String emittedBySource) {}

    private record Emission(int emitCycle, String source) {}

    private final Map<SignalType, Emission> active = new HashMap<>();
    private int cycleIndex = 0;

    /**
     * Call once per cycle boundary. Returns the lifetime deaths observed this cycle (usually
     * empty — deaths are rarer than ticks).
     *
     * @param preTick  the signal map immediately before {@code tickSignals()} ran this cycle
     * @param postTick the signal map immediately after
     */
    public List<Death> observe(Map<SignalType, Signal> preTick, Map<SignalType, Signal> postTick) {
        cycleIndex++;
        Map<SignalType, Signal> pre = preTick != null ? preTick : Map.of();
        Map<SignalType, Signal> post = postTick != null ? postTick : Map.of();
        List<Death> deaths = new ArrayList<>();

        // Tracked types already gone before this cycle's pre-tick snapshot were cleared by an
        // agent sometime during this cycle's execution (before the tick housekeeping ran).
        for (Iterator<Map.Entry<SignalType, Emission>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<SignalType, Emission> e = it.next();
            if (!pre.containsKey(e.getKey())) {
                deaths.add(new Death(e.getKey(), e.getValue().emitCycle(), cycleIndex, "cleared", e.getValue().source()));
                it.remove();
            }
        }

        // Types present pre-tick but not yet tracked are new emissions this cycle — either a
        // genuine first sighting, or a re-emission after an earlier death.
        for (Map.Entry<SignalType, Signal> e : pre.entrySet()) {
            active.computeIfAbsent(e.getKey(), k -> new Emission(cycleIndex, e.getValue().source()));
        }

        // Tracked types absent from the post-tick snapshot decayed away on this cycle's tick().
        for (Iterator<Map.Entry<SignalType, Emission>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<SignalType, Emission> e = it.next();
            if (!post.containsKey(e.getKey())) {
                deaths.add(new Death(e.getKey(), e.getValue().emitCycle(), cycleIndex, "decayed", e.getValue().source()));
                it.remove();
            }
        }

        return deaths;
    }

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
    public List<Death> observeRequests(Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> preTickOpen,
                                       Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> postTickOpen) {
        Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> pre =
                preTickOpen != null ? preTickOpen : Map.of();
        Map<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> post =
                postTickOpen != null ? postTickOpen : Map.of();
        List<Death> deaths = new ArrayList<>();

        for (Iterator<Map.Entry<SignalType, Emission>> it = activeRequests.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<SignalType, Emission> e = it.next();
            if (!pre.containsKey(e.getKey())) {
                deaths.add(new Death(e.getKey(), e.getValue().emitCycle(), cycleIndex, "consumed", e.getValue().source()));
                it.remove();
            }
        }

        for (Map.Entry<SignalType, com.walpro.autobuilder.core.lifecycle.ChannelRequest> e : pre.entrySet()) {
            activeRequests.computeIfAbsent(e.getKey(), k -> new Emission(cycleIndex, e.getValue().source()));
        }

        for (Iterator<Map.Entry<SignalType, Emission>> it = activeRequests.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<SignalType, Emission> e = it.next();
            if (!post.containsKey(e.getKey())) {
                deaths.add(new Death(e.getKey(), e.getValue().emitCycle(), cycleIndex, "expired", e.getValue().source()));
                it.remove();
            }
        }

        return deaths;
    }

    private final Map<String, Emission> activeWindows = new HashMap<>();

    /**
     * T4-BZ-4 (I9 re-scope): the same two-snapshot attribution for participation windows —
     * a role's window lifecycle ends as "closed" (gone from the pre-tick map: revoked by a
     * merge/analysis-unlock, or shrunk-and-replaced mid-cycle) or "expired" (removed by this
     * cycle's window tick: validity horizon passed, or shrunk below the survival threshold).
     * Deaths report the role's ROLE_* type name so downstream scripts keep one namespace.
     * Shares {@code cycleIndex}; call after {@link #observe} on the same boundary.
     */
    public List<Death> observeWindows(Map<String, com.walpro.autobuilder.core.lifecycle.ParticipationWindow> preTickActive,
                                      Map<String, com.walpro.autobuilder.core.lifecycle.ParticipationWindow> postTickActive) {
        Map<String, com.walpro.autobuilder.core.lifecycle.ParticipationWindow> pre =
                preTickActive != null ? preTickActive : Map.of();
        Map<String, com.walpro.autobuilder.core.lifecycle.ParticipationWindow> post =
                postTickActive != null ? postTickActive : Map.of();
        List<Death> deaths = new ArrayList<>();

        for (Iterator<Map.Entry<String, Emission>> it = activeWindows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Emission> e = it.next();
            if (!pre.containsKey(e.getKey())) {
                deaths.add(new Death(com.walpro.autobuilder.core.lifecycle.BasicSignals.roleSignalFor(e.getKey()),
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
                deaths.add(new Death(com.walpro.autobuilder.core.lifecycle.BasicSignals.roleSignalFor(e.getKey()),
                        e.getValue().emitCycle(), cycleIndex, "expired", e.getValue().source()));
                it.remove();
            }
        }

        return deaths;
    }
}
