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
        // T4-BZ-2: the readout composes stored entries + derived facts. Derived facts have no
        // store — each true predicate contributes a computed entry (strength 1.0, age 0,
        // decay 0.0, source reconstructing what its deleted emitter wrote) unless a stored
        // entry of the same type is present (REST injection / recorded state), which wins.
        Map<SignalType, Signal> combined = new LinkedHashMap<>(ctx.signals());
        for (Signal derived : derivedEntries(ctx)) {
            combined.putIfAbsent(derived.type(), derived);
        }
        Map<SignalType, Signal> raw = Collections.unmodifiableMap(combined);
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

    /**
     * Computed entries for the derived facts that are true on this context. Tracker/goal-backed
     * facts (RECURRING_DEFECT_DOMINANT, IRREDUCIBLE_GAP) need beans and are absent from the
     * static readout — their decision carriers never were the signal map (T4-J consumer map).
     */
    private static List<Signal> derivedEntries(CycleContextData ctx) {
        List<Signal> out = new ArrayList<>();
        if (DerivedFacts.buildBroken(ctx)) {
            String source = (ctx.buildOutcome() != null
                    && ctx.buildOutcome().result() == com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR)
                    ? "buildOutcome" : "lastRollback";
            out.add(Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, source));
        }
        if (DerivedFacts.testRepairFocus(ctx)) {
            out.add(Signal.of(BasicSignals.TEST_REPAIR_FOCUS, 1.0, 0.0, "resolvedOutcome"));
        }
        if (DerivedFacts.buildFixLocusStuck(ctx)) {
            out.add(Signal.of(BasicSignals.BUILD_FIX_LOCUS_STUCK, 1.0, 0.0,
                    ctx.sameFailureStreakKey() + "|streak=" + ctx.sameFailureStreak()));
        }
        if (DerivedFacts.evolutionOccurred(ctx)) {
            out.add(Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "evolution"));
        }
        if (DerivedFacts.evolutionFingerprintChanged(ctx)) {
            out.add(Signal.of(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED, 1.0, 0.0, "EvolutionExecutionAgent"));
        }
        if (DerivedFacts.scopeOutOfScope(ctx)) {
            out.add(Signal.of(BasicSignals.SCOPE_OUT_OF_SCOPE, 1.0, 0.0, "subgoal-scope-guard"));
        }
        if (DerivedFacts.subgoalScopeMismatch(ctx)) {
            out.add(Signal.of(BasicSignals.SUBGOAL_SCOPE_MISMATCH, 1.0, 0.0, "subgoal-scope-guard"));
        }
        if (DerivedFacts.goalExhausted(ctx)) {
            String reason = ctx.goalExhaustedReason() != null ? ctx.goalExhaustedReason() : "halt";
            out.add(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, reason));
        }
        return out;
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
