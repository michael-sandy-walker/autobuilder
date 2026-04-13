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

    // ------------------------------------------------------------------ //
    // T2-BN: criteria progress block                                      //
    // ------------------------------------------------------------------ //

    @Test
    void criteriaProgressBlock_withNullStatus_returnsNull() {
        assertThat(EvolutionProtocolContract.criteriaProgressBlock(baseContext())).isNull();
    }

    @Test
    void criteriaProgressBlock_withEmptyStatus_returnsNull() {
        CycleContextData ctx = baseContext().withCumulativeCriteriaStatus(Map.of());
        assertThat(EvolutionProtocolContract.criteriaProgressBlock(ctx)).isNull();
    }

    @Test
    void criteriaProgressBlock_allPassed_showsCheckmarks() {
        CycleContextData ctx = baseContext()
                .withCumulativeCriteriaStatus(Map.of(0, true, 1, true))
                .withCurrentSubGoalCriteria(List.of("criterion A", "criterion B"));

        String block = EvolutionProtocolContract.criteriaProgressBlock(ctx);

        assertThat(block).isNotNull();
        assertThat(block).contains("CRITERIA PROGRESS");
        assertThat(block).contains("[1] \u2713 criterion A");
        assertThat(block).contains("[2] \u2713 criterion B");
        assertThat(block).doesNotContain("\u2717");
    }

    @Test
    void criteriaProgressBlock_nonePassed_showsCrosses() {
        CycleContextData ctx = baseContext()
                .withCumulativeCriteriaStatus(Map.of(0, false, 1, false))
                .withCurrentSubGoalCriteria(List.of("criterion A", "criterion B"));

        String block = EvolutionProtocolContract.criteriaProgressBlock(ctx);

        assertThat(block).contains("[1] \u2717 criterion A");
        assertThat(block).contains("[2] \u2717 criterion B");
        assertThat(block).doesNotContain("\u2713");
    }

    @Test
    void criteriaProgressBlock_mixedStatus_showsBothSymbols() {
        CycleContextData ctx = baseContext()
                .withCumulativeCriteriaStatus(Map.of(0, true, 1, false))
                .withCurrentSubGoalCriteria(List.of("criterion A", "criterion B"));

        String block = EvolutionProtocolContract.criteriaProgressBlock(ctx);

        assertThat(block).contains("[1] \u2713 criterion A").contains("already satisfied");
        assertThat(block).contains("[2] \u2717 criterion B").contains("still unmet");
    }

    @Test
    void getUserPrompt_withCumulativeCriteriaStatus_includesCriteriaProgressBlock() {
        CycleContextData ctx = baseContext()
                .withCumulativeCriteriaStatus(Map.of(0, true, 1, false))
                .withCurrentSubGoalCriteria(List.of("criterion A", "criterion B"));

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null);

        assertThat(prompt).contains("CRITERIA PROGRESS");
        assertThat(prompt).contains("criterion A");
        assertThat(prompt).contains("criterion B");
    }

    @Test
    void getUserPrompt_withoutCumulativeCriteriaStatus_omitsCriteriaProgressBlock() {
        String prompt = EvolutionProtocolContract.getUserPrompt(baseContext(), null);
        assertThat(prompt).doesNotContain("CRITERIA PROGRESS");
    }

    // ------------------------------------------------------------------ //
    // T2-BX: structural summary in required files block                   //
    // ------------------------------------------------------------------ //

    @Test
    void requiredFilesBlock_withExistingFilesHavingMethods_showsExistsTagAndSummary() {
        CycleContextData ctx = baseContext()
                .withCurrentSubGoalCriteria(List.of(
                        "LoanController calls loanService.createLoan()",
                        "LoanService has createLoan method"
                ))
                .withPreviousOutput(Map.of(
                        "src/main/java/com/example/LoanController.java",
                        "public class LoanController {\n  public LoanEntity getLoan(String id) { return null; }\n}",
                        "src/main/java/com/example/LoanService.java",
                        "public class LoanService {\n  public LoanEntity createLoan(String m, String b) { return null; }\n}"
                ));

        String block = EvolutionProtocolContract.requiredFilesBlock(ctx);

        assertThat(block).isNotNull();
        assertThat(block).contains("LoanController.java  [EXISTS]");
        assertThat(block).contains("LoanService.java  [EXISTS]");
        assertThat(block).contains("getLoan");
        assertThat(block).contains("createLoan");
    }

    @Test
    void requiredFilesBlock_withExistingFiles_includesDoNotClobberInstruction() {
        CycleContextData ctx = baseContext()
                .withCurrentSubGoalCriteria(List.of(
                        "LoanController calls loanService.createLoan()",
                        "LoanService has createLoan method"
                ))
                .withPreviousOutput(Map.of(
                        "src/main/java/com/example/LoanController.java", "public class LoanController {}",
                        "src/main/java/com/example/LoanService.java", "public class LoanService {}"
                ));

        String block = EvolutionProtocolContract.requiredFilesBlock(ctx);

        assertThat(block).contains("Do NOT omit existing methods or handlers");
        assertThat(block).contains("[EXISTS]");
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
