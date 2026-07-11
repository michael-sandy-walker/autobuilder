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
        if (ctx == null) {
            return new SignalReadout(null, List.of(), null);
        }
        // T4-BZ-5: the readout composes the carriers alone — windows, derived facts and
        // toggles (each a computed entry whose source states the carrier's reason), then open
        // requests. Signal survives purely as the render-carrier record (I7 byte shapes).
        Map<SignalType, Signal> combined = new LinkedHashMap<>();
        // T4-BZ-4: active participation windows render as ROLE_* entries — strength = boost
        // (grant value, no decay arithmetic), age = cycles since grant, decay 0.0 (the stated
        // expiry rides meta as validForCycles). A stored twin (recorded state / REST
        // injection) wins, matching the read-side migration bridge.
        for (ParticipationWindow w : ctx.windows().active().values()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            if (w.validForCycles() != null) meta.put("validForCycles", w.validForCycles());
            combined.putIfAbsent(BasicSignals.roleSignalFor(w.role()),
                    new Signal(BasicSignals.roleSignalFor(w.role()), w.boost(), 0.0, w.age(),
                            w.source(), Map.copyOf(meta)));
        }
        for (Signal derived : derivedEntries(ctx)) {
            combined.putIfAbsent(derived.type(), derived);
        }
        // T4-BZ-3: open channel requests render as entries too — strength = urgency (issue
        // value, no decay arithmetic), age = cycles since issue, decay 0.0 (expiry rides meta
        // as validForCycles). A stored twin (recorded state / REST injection) wins, matching
        // the read-side migration bridge.
        for (ChannelRequest r : ctx.requests().open().values()) {
            Map<String, Object> meta = r.meta() != null ? new LinkedHashMap<>(r.meta()) : new LinkedHashMap<>();
            if (r.validForCycles() != null) meta.put("validForCycles", r.validForCycles());
            if (r.subGoalId() != null) meta.put("subGoalId", r.subGoalId());
            combined.putIfAbsent(r.kind(),
                    new Signal(r.kind(), r.urgency(), 0.0, r.age(), r.source(), Map.copyOf(meta)));
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
        // T4-BZ-5: toggles render from their carriers (set = present at 1.0, source = reason).
        if (ctx.pausedReason() != null) {
            out.add(Signal.of(BasicSignals.SYSTEM_PAUSED, 1.0, 0.0, ctx.pausedReason()));
        }
        if (ctx.steeringSuppressed()) {
            out.add(Signal.of(BasicSignals.STEERING_SUPPRESS, 1.0, 0.0, "operator"));
        }
        if (ctx.restageSuppressed()) {
            out.add(Signal.of(BasicSignals.RESTAGE_SUPPRESS, 1.0, 0.0, "operator"));
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
    /**
     * Present entry NAMES only, sorted, bracketed — the pre-7a687f5 analysis-SIGNALS render.
     * 7a687f5 changed this block to NAME=strength so the LLM could judge the #InjectionThrottle
     * rule's "at significant strength" condition; that BACKFIRED (field-confirmed 2026-07-11):
     * window/role carriers saturate at 3.00, so the LLM saw "significant strength" almost every
     * cycle and over-throttled subgoal injection (39 injections at the proven-good e0b5bff vs
     * 9-15 in the degraded runs), leaving endpoints unmapped and stalling. Names-only restores
     * the e0b5bff behavior (23/23): the LLM cannot over-read saturated strengths it never sees.
     */
    public String namesLine() {
        if (raw == null || raw.isEmpty()) return "(none)";
        return raw.keySet().stream().map(SignalType::name).sorted()
                .collect(Collectors.joining(", ", "[", "]"));
    }

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
     * T4-BZ-3 (additive): open requests issued in an EARLIER cycle that no addressed mechanism
     * has consumed yet — the formerly-silent-decay pathology made visible. Age 0 entries are
     * excluded (issued this cycle, consumption normally happens next cycle).
     */
    public List<Entry> unconsumedRequests() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.family() == Family.REQUEST && e.age() >= 1) out.add(e);
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
