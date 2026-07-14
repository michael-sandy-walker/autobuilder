package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.PackageFragmentationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-BA: tests for the {@link PackageFragmentationPromptBlock} formatter. */
class PackageFragmentationPromptBlockTest {

    @Test
    void render_nullCtx_returnsNull() {
        assertThat(PackageFragmentationPromptBlock.render(null)).isNull();
    }

    @Test
    void render_noObservation_returnsNull() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c").build();
        assertThat(PackageFragmentationPromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_emptyConflicts_returnsNull() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .packageFragmentation(new PackageFragmentationReport("cycle-frag", List.of()))
                .build();
        assertThat(PackageFragmentationPromptBlock.render(ctx)).isNull();
    }

    @Test
    void render_observationPresent_emitsFragmentationBlock() {
        PackageFragmentationReport observation = new PackageFragmentationReport("cycle-frag",
                List.of(
                        new PackageFragmentationReport.Conflict(
                                "BookAlreadyLoanedException",
                                "src/main/java/com/example/demo/BookAlreadyLoanedException.java",
                                "src/main/java/com/example/demo/loan/BookAlreadyLoanedException.java"),
                        new PackageFragmentationReport.Conflict(
                                "LoanRequest",
                                "src/main/java/com/example/demo/LoanRequest.java",
                                "src/main/java/com/example/demo/loan/LoanRequest.java")));
        CycleContextData ctx = CycleContextData.builder().cycleName("c")
                .packageFragmentation(observation).build();

        String block = PackageFragmentationPromptBlock.render(ctx);

        assertThat(block).isNotNull();
        assertThat(block)
                .contains("--- PACKAGE FRAGMENTATION REJECTED ---")
                .contains("Previous cycle: cycle-frag")
                .contains("REJECTED and NOT merged")
                .contains("Conflicts (2)")
                .contains("BookAlreadyLoanedException: already defined at "
                        + "src/main/java/com/example/demo/BookAlreadyLoanedException.java")
                .contains("rejected duplicate at "
                        + "src/main/java/com/example/demo/loan/BookAlreadyLoanedException.java")
                .contains("LoanRequest")
                .contains("Do NOT create a second");
    }
}
