package com.walpro.autobuilder.core.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * T4-BZ-1: the derived signal readout — the single rendering surface every signal-map CONSUMER
 * that only reads-for-display goes through (analysis prompt block, interpretation full render,
 * {@code signal_snapshot}, SSE goal-state events). The readout is computed per read, never
 * stored.
 *
 * In this slice it composes the CURRENT stored map ({@link CycleContextData#signals()}) and its
 * renders are byte-identical to the legacy inline implementations it replaced. Later T4-BZ
 * slices change how entries are COMPUTED (windows / request channel / derived predicates)
 * behind this stable face — the contract in the T4-BZ design note fixes entry shape and names;
 * only computation may change.
 */
public record SignalReadout(String cycleId, List<Entry> entries, Map<SignalType, Signal> raw) {

    /** The T4-BZ-0 family classification — additive metadata; no renderer depends on it yet. */
    public enum Family { WINDOW, REQUEST, DERIVED, TOGGLE }

    public record Entry(
            String name,
            Family family,
            double strength,
            int age,
            double decayPerCycle,
            String source,
            Map<String, Object> meta
    ) {}

    private static final Set<SignalType> WINDOWS = Set.of(
            BasicSignals.ROLE_PLANNER, BasicSignals.ROLE_EVOLUTION, BasicSignals.ROLE_EVALUATION,
            BasicSignals.ROLE_MERGE, BasicSignals.ROLE_ANALYSIS);

    private static final Set<SignalType> DERIVED = Set.of(
            BasicSignals.BUILD_BROKEN, BasicSignals.TEST_REPAIR_FOCUS,
            BasicSignals.BUILD_FIX_LOCUS_STUCK, BasicSignals.RECURRING_DEFECT_DOMINANT,
            BasicSignals.IRREDUCIBLE_GAP, BasicSignals.SCOPE_OUT_OF_SCOPE,
            BasicSignals.SUBGOAL_SCOPE_MISMATCH, BasicSignals.GOAL_EXHAUSTED,
            BasicSignals.EVOLUTION_OCCURRED, BasicSignals.EVOLUTION_FINGERPRINT_CHANGED);

    private static final Set<SignalType> TOGGLES = Set.of(
            BasicSignals.SYSTEM_PAUSED, BasicSignals.STEERING_SUPPRESS, BasicSignals.RESTAGE_SUPPRESS);

    public static SignalReadout of(CycleContextData ctx) {
        if (ctx == null || ctx.signals() == null) {
            return new SignalReadout(ctx != null ? ctx.cycleName() : null, List.of(), null);
        }
        Map<SignalType, Signal> raw = Collections.unmodifiableMap(new LinkedHashMap<>(ctx.signals()));
        List<Entry> entries = new ArrayList<>(raw.size());
        for (Map.Entry<SignalType, Signal> e : raw.entrySet()) {
            SignalType type = e.getKey();
            Signal s = e.getValue();
            if (type == null || s == null) continue;
            entries.add(new Entry(type.name(), familyOf(type),
                    s.strength(), s.age(), s.decayPerCycle(), s.source(), s.meta()));
        }
        return new SignalReadout(ctx.cycleName(), List.copyOf(entries), raw);
    }

    /** Operator-injected names outside every family read as requests (poke = ask). */
    public static Family familyOf(SignalType type) {
        if (WINDOWS.contains(type)) return Family.WINDOW;
        if (DERIVED.contains(type)) return Family.DERIVED;
        if (TOGGLES.contains(type)) return Family.TOGGLE;
        return Family.REQUEST;
    }

    /**
     * The analysis prompt's SIGNALS block: NAME=strength pairs, strongest first, two decimals.
     * Legacy: {@code AnalysisProtocolContract.signalsBlock}.
     */
    public String strengthLine() {
        if (raw == null || raw.isEmpty()) return "(none)";
        return raw.values().stream()
                .sorted((a, b) -> Double.compare(b.strength(), a.strength()))
                .map(s -> s.type().name() + "=" + String.format(Locale.ROOT, "%.2f", s.strength()))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * The interpretation prompt's full-map render (incl. age/decay/source/meta — decay's only
     * same-cycle prompt consumer, T4-J 3c). Legacy: the raw map formatted via {@code %s}.
     */
    public String fullMap() {
        return raw != null ? raw.toString() : "(none)";
    }

    /**
     * The {@code signal_snapshot} behaviour-event payload: name → strength.
     * Legacy: {@code AdaptiveExecutionEngine.extractSignalSnapshot} (HashMap, same iteration
     * order for identical contents — keeps the serialized event byte-stable).
     */
    public Map<String, Double> snapshot() {
        Map<String, Double> out = new HashMap<>();
        if (raw == null) return out;
        for (Map.Entry<SignalType, Signal> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.put(e.getKey().name(), e.getValue().strength());
        }
        return out;
    }

    /**
     * The SSE goal-state event's signal map: name → strength in emission order.
     * Legacy: {@code GoalStateEventPublisher.extractSignalStrengths} (LinkedHashMap; immutable
     * empty map when there is nothing to render).
     */
    public Map<String, Double> strengthsInOrder() {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<SignalType, Signal> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.put(e.getKey().name(), e.getValue().strength());
        }
        return out;
    }
}
