package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-BX: tests for the {@link RejectionFeedbackPromptBlock} formatter. */
class RejectionFeedbackPromptBlockTest {

    @Test
    void render_nullCtx_returnsNull() {
        assertThat(RejectionFeedbackPromptBlock.render(null)).isNull();
    }

    @Test
    void render_noReasons_returnsNull() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();
        assertThat(RejectionFeedbackPromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_emptyReasons_returnsNull() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .rejectionFeedback(List.of()).build();
        assertThat(RejectionFeedbackPromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_reasonsPresent_relaysAuthoredLinesVerbatim() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .rejectionFeedback(List.of(
                        "Only test files were changed; the rule itself is not implemented.",
                        "TestCoherenceHeuristic: Test files changed and feedback indicates failures."))
                .build();

        String block = RejectionFeedbackPromptBlock.render(ctx);

        assertThat(block).isNotNull();
        assertThat(block)
                .contains("--- WHY THE PREVIOUS ATTEMPT WAS REJECTED ---")
                .contains("NOT merged")
                .contains("Only test files were changed; the rule itself is not implemented.")
                .contains("TestCoherenceHeuristic: Test files changed and feedback indicates failures.")
                .contains("Do not resubmit");
    }

    @Test
    void render_excludingAlreadyShown_skipsThatLine() {
        String evaluatorMessage = "The rule itself is not implemented.";
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .rejectionFeedback(List.of(
                        evaluatorMessage,
                        "TestCoherenceHeuristic: only test files changed."))
                .build();

        String block = RejectionFeedbackPromptBlock.render(ctx, evaluatorMessage);

        assertThat(block).isNotNull();
        assertThat(block)
                .doesNotContain("- " + evaluatorMessage)
                .contains("TestCoherenceHeuristic: only test files changed.");
    }

    @Test
    void render_excludingLeavesNothing_returnsNull() {
        String only = "The only reason.";
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .rejectionFeedback(List.of(only)).build();

        assertThat(RejectionFeedbackPromptBlock.render(ctx, only)).isNull();
    }
}
