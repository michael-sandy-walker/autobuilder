package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalReadout;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BZ-1/2 equivalence gate (I7/I9): every render of {@link SignalReadout} equals the legacy
 * inline implementation applied to the COMPOSED map (stored entries + computed derived facts,
 * stored wins on collision — the T4-BZ-2 composition), across the full frozen-state library
 * plus the null/empty edges. The legacy renderers are reproduced VERBATIM below; if a later
 * slice deliberately changes a render, it must change this baseline in the same commit and
 * re-gate the prompt surface (AgentPromptRenderer + layer-2 eval per the design note).
 *
 * T4-BZ-2 baseline change, deliberate: derived facts render from predicates now — a state whose
 * context makes a fact true (e.g. an unmerged evolution fingerprint) renders that entry even
 * though no store write ever happened. Store-only composition was the T4-BZ-1 baseline.
 */
class SignalReadoutEquivalenceTest {

    @Test
    void allFourRenders_matchLegacyRenderersOverTheComposedMap_acrossTheStateLibrary() {
        for (FrozenStateFixtures.FrozenState state : FrozenStateFixtures.all()) {
            CycleContextData ctx = state.ctx();
            CycleContextData composed = composedView(ctx);
            SignalReadout readout = SignalReadout.of(ctx);

            assertThat(readout.strengthLine())
                    .as("analysis strength line, state " + state.name())
                    .isEqualTo(legacySignalsBlock(composed));
            assertThat(readout.fullMap())
                    .as("interpretation full map, state " + state.name())
                    .isEqualTo(legacyFullMapRender(composed));
            assertThat(readout.snapshot())
                    .as("signal_snapshot payload, state " + state.name())
                    .isEqualTo(legacyExtractSignalSnapshot(composed));
            assertThat(readout.snapshot().toString())
                    .as("signal_snapshot serialized order, state " + state.name())
                    .isEqualTo(legacyExtractSignalSnapshot(composed).toString());
            assertThat(readout.strengthsInOrder())
                    .as("SSE strengths map, state " + state.name())
                    .isEqualTo(legacyExtractSignalStrengths(composed));
            assertThat(readout.strengthsInOrder().toString())
                    .as("SSE strengths serialized order, state " + state.name())
                    .isEqualTo(legacyExtractSignalStrengths(composed).toString());
        }
    }

    /** The T4-BZ-2 composition, built independently of SignalReadout's own implementation. */
    private static CycleContextData composedView(CycleContextData ctx) {
        Map<SignalType, Signal> combined = new LinkedHashMap<>(ctx.signals());
        if (DerivedFacts.buildBroken(ctx)) {
            String source = (ctx.buildOutcome() != null
                    && ctx.buildOutcome().result() == com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR)
                    ? "buildOutcome" : "lastRollback";
            combined.putIfAbsent(BasicSignals.BUILD_BROKEN, Signal.of(BasicSignals.BUILD_BROKEN, 1.0, 0.0, source));
        }
        if (DerivedFacts.testRepairFocus(ctx)) {
            combined.putIfAbsent(BasicSignals.TEST_REPAIR_FOCUS,
                    Signal.of(BasicSignals.TEST_REPAIR_FOCUS, 1.0, 0.0, "resolvedOutcome"));
        }
        if (DerivedFacts.buildFixLocusStuck(ctx)) {
            combined.putIfAbsent(BasicSignals.BUILD_FIX_LOCUS_STUCK,
                    Signal.of(BasicSignals.BUILD_FIX_LOCUS_STUCK, 1.0, 0.0,
                            ctx.sameFailureStreakKey() + "|streak=" + ctx.sameFailureStreak()));
        }
        if (DerivedFacts.evolutionOccurred(ctx)) {
            combined.putIfAbsent(BasicSignals.EVOLUTION_OCCURRED,
                    Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.0, "evolution"));
        }
        if (DerivedFacts.evolutionFingerprintChanged(ctx)) {
            combined.putIfAbsent(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED,
                    Signal.of(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED, 1.0, 0.0, "EvolutionExecutionAgent"));
        }
        if (DerivedFacts.scopeOutOfScope(ctx)) {
            combined.putIfAbsent(BasicSignals.SCOPE_OUT_OF_SCOPE,
                    Signal.of(BasicSignals.SCOPE_OUT_OF_SCOPE, 1.0, 0.0, "subgoal-scope-guard"));
        }
        if (DerivedFacts.subgoalScopeMismatch(ctx)) {
            combined.putIfAbsent(BasicSignals.SUBGOAL_SCOPE_MISMATCH,
                    Signal.of(BasicSignals.SUBGOAL_SCOPE_MISMATCH, 1.0, 0.0, "subgoal-scope-guard"));
        }
        if (DerivedFacts.goalExhausted(ctx)) {
            String reason = ctx.goalExhaustedReason() != null ? ctx.goalExhaustedReason() : "halt";
            combined.putIfAbsent(BasicSignals.GOAL_EXHAUSTED,
                    Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, reason));
        }
        return ctx.withSignals(combined);
    }

    @Test
    void nullAndEmptyEdges_matchLegacyBehavior() {
        CycleContextData empty = CycleContextData.builder().build().withSignals(new LinkedHashMap<>());

        assertThat(SignalReadout.of(null).strengthLine()).isEqualTo("(none)");
        assertThat(SignalReadout.of(null).fullMap()).isEqualTo("(none)");
        assertThat(SignalReadout.of(null).snapshot()).isEmpty();
        assertThat(SignalReadout.of(null).strengthsInOrder()).isEmpty();

        assertThat(SignalReadout.of(empty).strengthLine()).isEqualTo(legacySignalsBlock(empty));
        assertThat(SignalReadout.of(empty).fullMap()).isEqualTo(legacyFullMapRender(empty));
        assertThat(SignalReadout.of(empty).snapshot()).isEqualTo(legacyExtractSignalSnapshot(empty));
        assertThat(SignalReadout.of(empty).strengthsInOrder()).isEqualTo(legacyExtractSignalStrengths(empty));
    }

    @Test
    void familyClassification_matchesTheEmissionSiteTable() {
        SignalReadout readout = SignalReadout.of(FrozenStateFixtures.all().get(0).ctx());
        for (SignalReadout.Entry e : readout.entries()) {
            if (e.name().startsWith("ROLE_")) {
                assertThat(e.family()).as(e.name()).isEqualTo(SignalReadout.Family.WINDOW);
            }
        }
        assertThat(SignalReadout.familyOf(SignalType.of("SYSTEM_PAUSED")))
                .isEqualTo(SignalReadout.Family.TOGGLE);
        assertThat(SignalReadout.familyOf(SignalType.of("EVOLUTION_OCCURRED")))
                .isEqualTo(SignalReadout.Family.DERIVED);
        assertThat(SignalReadout.familyOf(SignalType.of("REPLAN_REQUESTED")))
                .isEqualTo(SignalReadout.Family.REQUEST);
        // operator-injected names outside every family read as requests
        assertThat(SignalReadout.familyOf(SignalType.of("SOME_OPERATOR_POKE")))
                .isEqualTo(SignalReadout.Family.REQUEST);
    }

    // --- legacy renderers, reproduced verbatim (pre-T4-BZ-1 pinned behavior) ---

    /** Legacy AnalysisProtocolContract.signalsBlock. */
    private static String legacySignalsBlock(CycleContextData ctx) {
        if (ctx.signals() == null || ctx.signals().isEmpty()) return "(none)";
        return ctx.signals().values().stream()
                .sorted((a, b) -> Double.compare(b.strength(), a.strength()))
                .map(s -> s.type().name() + "=" + String.format(Locale.ROOT, "%.2f", s.strength()))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** Legacy InterpretationProtocolContract render: the raw map through %s. */
    private static String legacyFullMapRender(CycleContextData ctx) {
        return String.format("%s", ctx.signals() != null ? ctx.signals() : "(none)");
    }

    /** Legacy AdaptiveExecutionEngine.extractSignalSnapshot. */
    private static Map<String, Double> legacyExtractSignalSnapshot(CycleContextData ctx) {
        Map<String, Double> out = new HashMap<>();
        if (ctx == null || ctx.signals() == null || ctx.signals().isEmpty()) {
            return out;
        }
        for (Map.Entry<SignalType, Signal> e : ctx.signals().entrySet()) {
            SignalType type = e.getKey();
            Signal s = e.getValue();
            if (type == null || s == null) continue;
            out.put(type.name(), s.strength());
        }
        return out;
    }

    /** Legacy GoalStateEventPublisher.extractSignalStrengths. */
    private static Map<String, Double> legacyExtractSignalStrengths(CycleContextData context) {
        if (context == null || context.signals() == null || context.signals().isEmpty()) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<SignalType, Signal> e : context.signals().entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.put(e.getKey().name(), e.getValue().strength());
        }
        return out;
    }
}
