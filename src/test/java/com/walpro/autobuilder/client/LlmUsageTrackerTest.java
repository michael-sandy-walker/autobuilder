package com.walpro.autobuilder.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** T5-R: token accounting per role + session, tolerant of missing usage blocks. */
class LlmUsageTrackerTest {

    @Test
    void accumulatesPerRoleAndSession() {
        LlmUsageTracker t = new LlmUsageTracker();
        t.record("evolution", 1000, 200);
        t.record("evolution", 500, 100);
        t.record("evaluation", 800, 50);

        assertThat(t.sessionPromptTokens()).isEqualTo(2300);
        assertThat(t.sessionCompletionTokens()).isEqualTo(350);
        assertThat(t.sessionTotalTokens()).isEqualTo(2650);
        assertThat(t.sessionCalls()).isEqualTo(3);

        LlmUsageTracker.Snapshot snap = t.snapshot();
        assertThat(snap.byRole().get("evolution").promptTokens()).isEqualTo(1500);
        assertThat(snap.byRole().get("evolution").calls()).isEqualTo(2);
        assertThat(snap.byRole().get("evaluation").totalTokens()).isEqualTo(850);
    }

    @Test
    void countsCallWithoutUsageButAddsNoTokens() {
        // A provider that omits the usage block: the call is still counted so volume is truthful.
        LlmUsageTracker t = new LlmUsageTracker();
        t.record("planner", null, null);
        t.record("planner", 300, 40);

        assertThat(t.sessionCalls()).isEqualTo(2);
        assertThat(t.sessionCallsWithUsage()).isEqualTo(1);
        assertThat(t.sessionTotalTokens()).isEqualTo(340);
        assertThat(t.snapshot().byRole().get("planner").calls()).isEqualTo(2);
        assertThat(t.snapshot().byRole().get("planner").callsWithUsage()).isEqualTo(1);
    }

    @Test
    void nullRoleFallsToUnknownBucket() {
        LlmUsageTracker t = new LlmUsageTracker();
        t.record(null, 100, 10);
        assertThat(t.snapshot().byRole()).containsKey(LlmCallContext.UNKNOWN_ROLE);
    }
}
