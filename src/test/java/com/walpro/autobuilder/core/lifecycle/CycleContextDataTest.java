package com.walpro.autobuilder.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BZ-5: the context's carrier write paths (the stored-signal reinforce/tick machinery this
 * class used to pin was deleted with the store; its accumulation semantics live on in
 * ChannelRequest.raise / ParticipationWindow.grant, pinned here at the context surface).
 */
class CycleContextDataTest {

    @Test
    void issueRequest_raisesExistingKind_andAccumulatesSources() {
        CycleContextData ctx = CycleContextData.builder().build()
                .issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 0.2, "engine", 5))
                .issueRequest(ChannelRequest.of(BasicSignals.REPLAN_REQUESTED, 0.3, "deliberation", 5));

        ChannelRequest r = ctx.peekRequest(BasicSignals.REPLAN_REQUESTED);
        assertThat(r).isNotNull();
        assertThat(r.urgency()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        @SuppressWarnings("unchecked")
        List<String> sources = (List<String>) r.meta().get("sources");
        assertThat(sources).contains("engine", "deliberation");
    }

    @Test
    void grantWindow_mergesBoost_capped() {
        CycleContextData ctx = CycleContextData.builder().build()
                .grantWindow(ParticipationWindow.of("evolution", 2.5, 0.1, "a"))
                .grantWindow(ParticipationWindow.of("evolution", 1.0, 0.1, "b"));
        assertThat(ctx.windowBoost("evolution")).isEqualTo(ChannelRequest.MAX_URGENCY);
    }
}
