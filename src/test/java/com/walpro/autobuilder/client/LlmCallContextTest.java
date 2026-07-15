package com.walpro.autobuilder.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** T5-R: ambient role/cycle tag semantics — default, scope, nesting, restoration. */
class LlmCallContextTest {

    @Test
    void defaultsToUnknownWhenNoScope() {
        assertThat(LlmCallContext.currentRole()).isEqualTo(LlmCallContext.UNKNOWN_ROLE);
        assertThat(LlmCallContext.currentCycleId()).isNull();
    }

    @Test
    void scopeSetsAndRestores() {
        try (var ignored = LlmCallContext.scope("evolution", "cycle-1")) {
            assertThat(LlmCallContext.currentRole()).isEqualTo("evolution");
            assertThat(LlmCallContext.currentCycleId()).isEqualTo("cycle-1");
        }
        // Restored to the no-scope default.
        assertThat(LlmCallContext.currentRole()).isEqualTo(LlmCallContext.UNKNOWN_ROLE);
        assertThat(LlmCallContext.currentCycleId()).isNull();
    }

    @Test
    void nestedScopeRestoresOuter() {
        try (var outer = LlmCallContext.scope("evaluation", "cycle-2")) {
            try (var inner = LlmCallContext.scope("analysis", "cycle-2")) {
                assertThat(LlmCallContext.currentRole()).isEqualTo("analysis");
            }
            // Inner closed — outer restored.
            assertThat(LlmCallContext.currentRole()).isEqualTo("evaluation");
        }
        assertThat(LlmCallContext.currentRole()).isEqualTo(LlmCallContext.UNKNOWN_ROLE);
    }
}
