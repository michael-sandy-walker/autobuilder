package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
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
    // T4-AN-3: BUILD RESULT block surfaced to the evolver                  //
    // ------------------------------------------------------------------ //

    @Test
    void getUserPrompt_withFailingBuild_includesBuildResultBlock_aboveOptionalBlocks() {
        BuildOutcome failed = new BuildOutcome(
                BuildResult.COMPILE_ERROR,
                "[ERROR] no POM in this directory",
                100L, "mvn compile", "c");
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(failed)
                .build();

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null, false);

        assertThat(prompt)
                .contains("--- BUILD RESULT ---")
                .contains("Result: COMPILE_ERROR")
                .contains("no POM in this directory")
                .contains("do NOT respond with #NoDiff");
        // The build block must precede the static "Respond with:" trailer at the bottom of
        // the prompt — i.e. live in the high-priority section, not buried after rules.
        int buildIdx = prompt.indexOf("--- BUILD RESULT ---");
        int respondIdx = prompt.indexOf("Respond with:");
        assertThat(buildIdx).isPositive();
        assertThat(respondIdx).isPositive();
        assertThat(buildIdx).isLessThan(respondIdx);
    }

    @Test
    void getUserPrompt_withCompileOk_doesNotIncludeBuildResultBlock() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 100L, "mvn compile", "c"))
                .build();
        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null, false);
        assertThat(prompt).doesNotContain("--- BUILD RESULT ---");
    }

    @Test
    void getUserPrompt_withNullBuildOutcome_doesNotIncludeBuildResultBlock() {
        String prompt = EvolutionProtocolContract.getUserPrompt(baseContext(), null, false);
        assertThat(prompt).doesNotContain("--- BUILD RESULT ---");
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

    @Test
    void getUserPrompt_withEstablishedBasePackage_includesPackageCoherenceRule() {
        // Regression for 2026-05-19 21:44 run where every controller and service merged under
        // <base>.model/. The block must name the principle (distinct responsibilities → distinct
        // sub-packages) without prescribing a framework-specific tree shape.
        CycleContextData ctx = baseContext().withEstablishedBasePackage("com.example.library");

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null);

        assertThat(prompt).contains("PACKAGE COHERENCE RULE");
        assertThat(prompt).contains("com.example.library");
    }

    @Test
    void packageConstraintBlock_doesNotPrescribeFrameworkSpecificSubpackages() {
        // The block must not hardcode controller/, service/, repository/ or other Spring-flavoured
        // sub-package names — they don't apply to libraries, CLIs, batch jobs, etc. Lock the
        // framework-agnostic stance in so future edits don't backslide.
        CycleContextData ctx = baseContext().withEstablishedBasePackage("com.example.library");

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null);

        // The substrings ".controller", ".service", ".repository" would otherwise appear inside
        // the prescriptive enumeration we removed. Other parts of the prompt (e.g. dependency
        // injection guidance) may legitimately mention those terms, so we look for the
        // package-form substring (with the dot) that the old block used.
        assertThat(prompt).doesNotContain("com.example.library.controller");
        assertThat(prompt).doesNotContain("com.example.library.service");
        assertThat(prompt).doesNotContain("com.example.library.repository");
        assertThat(prompt).doesNotContain("@RestController");
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

    // ------------------------------------------------------------------ //
    // T2-DA: contract teaches the LLM to emit configuration files          //
    // ------------------------------------------------------------------ //

    @Test
    void rules_includesConfigFilesWhenNeededRule_withPomXmlExample() {
        String contractText = EvolutionProtocolContract.asPromptBlock();

        assertThat(contractText).contains("CONFIG_FILES_WHEN_NEEDED");
        assertThat(contractText).contains("xml [pom.xml]");
        assertThat(contractText).contains("spring-boot-starter-parent");
    }

    @Test
    void rules_returnChangedFilesMentionsConfigFiles() {
        String contractText = EvolutionProtocolContract.asPromptBlock();

        assertThat(contractText)
                .containsPattern("RETURN_CHANGED_FILES[\\s\\S]*?(xml|properties|yml|gradle)");
    }

    @Test
    void isValidResponse_acceptsConfigFileBlock() {
        String response = """
                ```xml [pom.xml]
                <?xml version="1.0" encoding="UTF-8"?>
                <project></project>
                ```
                """;

        assertThat(EvolutionProtocolContract.isValidResponse(response)).isTrue();
    }

    // ------------------------------------------------------------------ //
    // T2-DM: LAST COMPILE ERRORS block                                     //
    // ------------------------------------------------------------------ //

    @Test
    void lastCompileErrorsBlock_rendersErrorLinesWhenFeedbackCarriesProbePrefix() {
        String stderr = """
                [INFO] Scanning for projects...
                [INFO] Downloading from central: https://repo.maven.org/foo.jar
                [ERROR] /src/main/java/com/example/BookController.java:[39,24] incompatible types: ResponseEntity<...>
                  return ResponseEntity.ok(book);
                [ERROR] /src/main/java/com/example/LoanController.java:[24,28] books has private access in Book
                  this.books.add(b);
                [ERROR]
                [ERROR] :""";
        EvaluationOutcome rejected = new EvaluationOutcome(
                0.0,
                MutationAcceptanceState.REJECTED,
                GoalProgressState.NOT_EVALUATED,
                "Compile probe failed:\n" + stderr,
                null);
        CycleContextData ctx = baseContext().withLastEvaluationOutcome(rejected);

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null, false);

        assertThat(prompt)
                .contains("--- LAST COMPILE ERRORS ---")
                .contains("BookController.java:[39,24] incompatible types")
                .contains("LoanController.java:[24,28] books has private access")
                .doesNotContain("Scanning for projects")
                .doesNotContain("Downloading from central");
        // Block sits above the static "Respond with:" trailer so it's in the high-priority section.
        int errorsIdx = prompt.indexOf("--- LAST COMPILE ERRORS ---");
        int respondIdx = prompt.indexOf("Respond with:");
        assertThat(errorsIdx).isPositive();
        assertThat(errorsIdx).isLessThan(respondIdx);
    }

    @Test
    void lastCompileErrorsBlock_absentWhenFeedbackLacksProbePrefix() {
        // Null lastEvaluationOutcome: no block.
        assertThat(EvolutionProtocolContract.getUserPrompt(baseContext(), null, false))
                .doesNotContain("--- LAST COMPILE ERRORS ---");

        // Non-probe rejection feedback (e.g. negotiator override): no block.
        EvaluationOutcome nonProbe = new EvaluationOutcome(
                0.4,
                MutationAcceptanceState.REJECTED,
                GoalProgressState.NOT_EVALUATED,
                "Heuristic negotiator overrode the LLM score.",
                null);
        CycleContextData ctx = baseContext().withLastEvaluationOutcome(nonProbe);
        assertThat(EvolutionProtocolContract.getUserPrompt(ctx, null, false))
                .doesNotContain("--- LAST COMPILE ERRORS ---");

        // Probe prefix but no [ERROR] lines (e.g. stderr was all [INFO]): no block.
        EvaluationOutcome infoOnly = new EvaluationOutcome(
                0.0,
                MutationAcceptanceState.REJECTED,
                GoalProgressState.NOT_EVALUATED,
                "Compile probe failed:\n[INFO] Scanning for projects...\n[INFO] BUILD FAILURE",
                null);
        CycleContextData ctxInfoOnly = baseContext().withLastEvaluationOutcome(infoOnly);
        assertThat(EvolutionProtocolContract.getUserPrompt(ctxInfoOnly, null, false))
                .doesNotContain("--- LAST COMPILE ERRORS ---");
    }
}
