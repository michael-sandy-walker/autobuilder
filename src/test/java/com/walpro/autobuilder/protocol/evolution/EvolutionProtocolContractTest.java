package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvolutionProtocolContractTest {

    private CycleContextData baseContext() {
        return CycleContextData.builder().build();
    }

    // ------------------------------------------------------------------ //
    // divergent=true (explicit parameter)                                  //
    // ------------------------------------------------------------------ //

    @Test
    void getUserPrompt_withDivergentTrue_includesDivergenceModeBlock() {
        String prompt = EvolutionProtocolContract.getUserPrompt(baseContext(), null, true);
        assertThat(prompt).contains("Divergence Mode Triggered");
    }

    @Test
    void getUserPrompt_withDivergentFalse_doesNotIncludeDivergenceBlock() {
        String prompt = EvolutionProtocolContract.getUserPrompt(baseContext(), null, false);
        assertThat(prompt).doesNotContain("Divergence Mode Triggered");
        assertThat(prompt).doesNotContain("Escalated Divergence Required");
    }

    // ------------------------------------------------------------------ //
    // escalated: divergent=true + REPETITION_DETECTED on context          //
    // ------------------------------------------------------------------ //

    @Test
    void getUserPrompt_withDivergentAndRepetitionDetected_includesEscalatedBlock() {
        CycleContextData ctx = baseContext()
                .withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 1.0, 0.05, "test"));

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null, true);

        assertThat(prompt).contains("Escalated Divergence Required");
        assertThat(prompt).doesNotContain("Divergence Mode Triggered");
    }

    // ------------------------------------------------------------------ //
    // repetition only (divergent=false, REPETITION_DETECTED present)      //
    // ------------------------------------------------------------------ //

    @Test
    void getUserPrompt_withRepetitionOnly_includesRepetitionBlock() {
        CycleContextData ctx = baseContext()
                .withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 1.0, 0.05, "test"));

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null, false);

        assertThat(prompt).contains("Repetition Detected");
        assertThat(prompt).doesNotContain("Divergence Mode Triggered");
        assertThat(prompt).doesNotContain("Escalated Divergence Required");
    }

    // ------------------------------------------------------------------ //
    // 2-arg overload reads DIVERGENCE_REQUEST signal from context         //
    // ------------------------------------------------------------------ //

    @Test
    void getUserPrompt_twoArgOverload_readsDivergenceRequestSignalFromContext() {
        CycleContextData ctx = baseContext()
                .withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "test"));

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null);

        assertThat(prompt).contains("Divergence Mode Triggered");
    }

    @Test
    void getUserPrompt_twoArgOverload_noDivergenceSignal_doesNotIncludeDivergenceBlock() {
        String prompt = EvolutionProtocolContract.getUserPrompt(baseContext(), null);

        assertThat(prompt).doesNotContain("Divergence Mode Triggered");
    }

    // ------------------------------------------------------------------ //
    // T1-BC: package constraint block                                      //
    // ------------------------------------------------------------------ //

    @Test
    void getUserPrompt_withEstablishedBasePackage_includesPackageConstraintBlock() {
        CycleContextData ctx = baseContext().withEstablishedBasePackage("com.example.library");

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null);

        assertThat(prompt).contains("PACKAGE CONSTRAINT");
        assertThat(prompt).contains("com.example.library");
    }

    @Test
    void getUserPrompt_withoutEstablishedBasePackage_omitsPackageConstraintBlock() {
        String prompt = EvolutionProtocolContract.getUserPrompt(baseContext(), null);

        assertThat(prompt).doesNotContain("PACKAGE CONSTRAINT");
    }

    // ------------------------------------------------------------------ //
    // T2-BS: required files block                                          //
    // ------------------------------------------------------------------ //

    @Test
    void requiredFilesBlock_withTwoCriteriaMatchingTwoFiles_emitsBlock() {
        CycleContextData ctx = baseContext()
                .withCurrentSubGoalCriteria(List.of(
                        "LoanController calls bookService.markBookAsAvailable()",
                        "BookService has markBookAsAvailable method"
                ))
                .withPreviousOutput(Map.of(
                        "src/main/java/com/example/LoanController.java", "class LoanController {}",
                        "src/main/java/com/example/BookService.java", "class BookService {}"
                ));

        String block = EvolutionProtocolContract.requiredFilesBlock(ctx);

        assertThat(block).isNotNull();
        assertThat(block).contains("REQUIRED FILES");
        assertThat(block).contains("LoanController.java");
        assertThat(block).contains("BookService.java");
    }

    @Test
    void requiredFilesBlock_withOneCriterion_returnsNull() {
        CycleContextData ctx = baseContext()
                .withCurrentSubGoalCriteria(List.of(
                        "LoanController calls bookService.markBookAsAvailable()"
                ))
                .withPreviousOutput(Map.of(
                        "src/main/java/com/example/LoanController.java", "class LoanController {}"
                ));

        assertThat(EvolutionProtocolContract.requiredFilesBlock(ctx)).isNull();
    }

    @Test
    void requiredFilesBlock_withNoPreviousOutput_returnsNull() {
        CycleContextData ctx = baseContext()
                .withCurrentSubGoalCriteria(List.of(
                        "LoanController calls bookService.markBookAsAvailable()",
                        "BookService has markBookAsAvailable method"
                ));

        assertThat(EvolutionProtocolContract.requiredFilesBlock(ctx)).isNull();
    }

    @Test
    void requiredFilesBlock_criteriaMatchOnlyOneFile_returnsNull() {
        CycleContextData ctx = baseContext()
                .withCurrentSubGoalCriteria(List.of(
                        "LoanController processes the loan request",
                        "LoanController returns HTTP 201"
                ))
                .withPreviousOutput(Map.of(
                        "src/main/java/com/example/LoanController.java", "class LoanController {}"
                ));

        // only one distinct file found — block should be suppressed
        assertThat(EvolutionProtocolContract.requiredFilesBlock(ctx)).isNull();
    }

    @Test
    void getUserPrompt_withMultiFileSubgoal_includesRequiredFilesBlock() {
        CycleContextData ctx = baseContext()
                .withCurrentSubGoalCriteria(List.of(
                        "LoanController calls bookService.markBookAsAvailable()",
                        "BookService has markBookAsAvailable method"
                ))
                .withPreviousOutput(Map.of(
                        "src/main/java/com/example/LoanController.java", "class LoanController {}",
                        "src/main/java/com/example/BookService.java", "class BookService {}"
                ));

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null);

        assertThat(prompt).contains("REQUIRED FILES");
        assertThat(prompt).contains("LoanController.java");
        assertThat(prompt).contains("BookService.java");
    }
}
