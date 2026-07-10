package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
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
 * T4-BZ-1 equivalence gate (I7/I9): every render of the new {@link SignalReadout} is
 * byte-identical to the legacy inline implementation it replaced, across the full frozen-state
 * library plus the null/empty edges. The legacy renderers are reproduced VERBATIM below as the
 * comparison baseline — they are the pinned pre-T4-BZ-1 behavior; if a later slice deliberately
 * changes a render, it must change the baseline here in the same commit and re-gate the prompt
 * surface (AgentPromptRenderer + layer-2 eval per the design note).
 */
class SignalReadoutEquivalenceTest {

    @Test
    void allFourRenders_byteIdenticalToLegacy_acrossTheStateLibrary() {
        for (FrozenStateFixtures.FrozenState state : FrozenStateFixtures.all()) {
            CycleContextData ctx = state.ctx();
            SignalReadout readout = SignalReadout.of(ctx);

            assertThat(readout.strengthLine())
                    .as("analysis strength line, state " + state.name())
                    .isEqualTo(legacySignalsBlock(ctx));
            assertThat(readout.fullMap())
                    .as("interpretation full map, state " + state.name())
                    .isEqualTo(legacyFullMapRender(ctx));
            assertThat(readout.snapshot())
                    .as("signal_snapshot payload, state " + state.name())
                    .isEqualTo(legacyExtractSignalSnapshot(ctx));
            assertThat(readout.snapshot().toString())
                    .as("signal_snapshot serialized order, state " + state.name())
                    .isEqualTo(legacyExtractSignalSnapshot(ctx).toString());
            assertThat(readout.strengthsInOrder())
                    .as("SSE strengths map, state " + state.name())
                    .isEqualTo(legacyExtractSignalStrengths(ctx));
            assertThat(readout.strengthsInOrder().toString())
                    .as("SSE strengths serialized order, state " + state.name())
                    .isEqualTo(legacyExtractSignalStrengths(ctx).toString());
        }
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
