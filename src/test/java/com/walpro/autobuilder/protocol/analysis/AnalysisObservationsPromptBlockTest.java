package com.walpro.autobuilder.protocol.analysis;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-BX: tests for the {@link AnalysisObservationsPromptBlock} formatter. */
class AnalysisObservationsPromptBlockTest {

    private static CycleContextData withOutcome(AnalysisOutcome outcome) {
        return CycleContextData.builder().cycleName("c").analysisOutcome(outcome).build();
    }

    private static AnalysisOutcome outcome(String message, List<AnalysisOutcome.Finding> findings) {
        return new AnalysisOutcome(findings, List.of(), List.of(), null, message, null, false, false);
    }

    @Test
    void render_nullCtxOrOutcome_returnsNull() {
        assertThat(AnalysisObservationsPromptBlock.render(null)).isNull();
        assertThat(AnalysisObservationsPromptBlock.render(
                CycleContextData.builder().cycleName("c").build())).isNull();
    }

    @Test
    void render_relaysMessageAndBlockingFindingsWithPath() {
        AnalysisOutcome outcome = outcome(
                "The loan feature cannot compile without its repository.",
                List.of(
                        new AnalysisOutcome.Finding("WARNING", "MISSING_TYPE",
                                "A required repository class does not exist yet", "LoanRepository.java"),
                        new AnalysisOutcome.Finding("CRITICAL", "CYCLE",
                                "Controller depends on an undefined service", null)));

        String block = AnalysisObservationsPromptBlock.render(withOutcome(outcome));

        assertThat(block).isNotNull();
        assertThat(block)
                .contains("--- ANALYSIS OBSERVATIONS ---")
                .contains("what is blocking progress")
                .contains("The loan feature cannot compile without its repository.")
                .contains("A required repository class does not exist yet (LoanRepository.java)")
                .contains("Controller depends on an undefined service");
    }

    @Test
    void render_skipsInfoFindings() {
        AnalysisOutcome outcome = outcome(null, List.of(
                new AnalysisOutcome.Finding("INFO", "NOTE", "Everything looks fine", null)));
        assertThat(AnalysisObservationsPromptBlock.render(withOutcome(outcome))).isNull();
    }

    @Test
    void render_noMessageNoBlockingFindings_returnsNull() {
        AnalysisOutcome outcome = outcome("   ", List.of());
        assertThat(AnalysisObservationsPromptBlock.render(withOutcome(outcome))).isNull();
    }
}
