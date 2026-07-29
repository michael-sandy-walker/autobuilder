package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
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
    void getUserPrompt_rendersPriorRunEndReport_whenPresent() {
        // T4-CD slice 2: a resumed run's first evolution attempts consult the parent's end report.
        CycleContextData ctx = baseContext()
                .withPriorRunEndReport("--- PRIOR RUN OF THIS LINEAGE (advisory) ---\nThat run ended: deadline");
        assertThat(EvolutionProtocolContract.getUserPrompt(ctx, null, false))
                .contains("--- PRIOR RUN OF THIS LINEAGE (advisory) ---");
        assertThat(EvolutionProtocolContract.getUserPrompt(baseContext(), null, false))
                .doesNotContain("PRIOR RUN OF THIS LINEAGE");
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
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 1.0, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.05)));

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
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 1.0, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.05)));

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
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.10)));

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

    // T3-BX: the fact reaching the prompt. Measured over six run corpora, the analyzer produced a
    // status-path fact on every one while the only analyzer path that reached this prompt produced
    // none on any of them — the cause of the project's most persistent artifact defect was computed
    // every run and never shown to the agent that could fix it.

    private static final String VALIDATED_BINDING_WITHOUT_CONSTRAINTS = """
            package a;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RestController;
            import jakarta.validation.Valid;
            @RestController
            public class MemberController {
                @PostMapping
                public String create(@Valid @RequestBody Member body) { return body.getName(); }
            }
            """;

    private static final String UNCONSTRAINED_MODEL = """
            package a;
            public class Member {
                private String name;
                public String getName() { return name; }
            }
            """;

    @Test
    void getUserPrompt_withAnInertValidationSurface_includesTheStatusPathBlock() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .previousOutput(java.util.Map.of(
                        "src/main/java/a/MemberController.java", VALIDATED_BINDING_WITHOUT_CONSTRAINTS,
                        "src/main/java/a/Member.java", UNCONSTRAINED_MODEL))
                .build();

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null, false);

        assertThat(prompt)
                .contains("STATUS-PATH FACTS")
                .contains("Member")
                .contains("no field constraint annotations");
        // High-priority section, like the build block — not buried after the rules.
        assertThat(prompt.indexOf("STATUS-PATH FACTS")).isLessThan(prompt.indexOf("Respond with:"));
    }

    @Test
    void getUserPrompt_withAConsistentlyValidatedArtifact_omitsTheStatusPathBlock() {
        // Conditional, not a payload: silence on a healthy artifact is what keeps the prompt lean.
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c")
                .previousOutput(java.util.Map.of(
                        "src/main/java/a/MemberController.java", VALIDATED_BINDING_WITHOUT_CONSTRAINTS,
                        "src/main/java/a/Member.java", """
                                package a;
                                import jakarta.validation.constraints.NotBlank;
                                public class Member {
                                    @NotBlank private String name;
                                    public String getName() { return name; }
                                }
                                """))
                .build();

        assertThat(EvolutionProtocolContract.getUserPrompt(ctx, null, false))
                .doesNotContain("STATUS-PATH FACTS");
    }

    @Test
    void getUserPrompt_withNoOutputYet_omitsTheStatusPathBlock() {
        assertThat(EvolutionProtocolContract.getUserPrompt(baseContext(), null, false))
                .doesNotContain("STATUS-PATH FACTS");
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
    // T4-BD: BuildResultPromptBlock + lastProbeOutcome (replaced lastCompileErrorsBlock) //
    // ------------------------------------------------------------------ //

    @Test
    void buildResultPromptBlock_rendersProbeOutcomeWhenBuildOutcomeIsAbsent() {
        String stderr = """
                [INFO] Scanning for projects...
                [INFO] Downloading from central: https://repo.maven.org/foo.jar
                [ERROR] /src/main/java/com/example/BookController.java:[39,24] incompatible types: ResponseEntity<...>
                  return ResponseEntity.ok(book);
                [ERROR] /src/main/java/com/example/LoanController.java:[24,28] books has private access in Book
                  this.books.add(b);""";
        com.walpro.autobuilder.core.build.BuildOutcome probe = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                stderr,
                100L,
                "mvn -q compile",
                "test-cycle"
        );
        CycleContextData ctx = baseContext().withLastProbeOutcome(probe);

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null, false);

        assertThat(prompt)
                .contains("--- BUILD RESULT ---")
                .contains("BookController.java:[39,24] incompatible types")
                .contains("LoanController.java:[24,28] books has private access")
                .doesNotContain("Scanning for projects")
                .doesNotContain("Downloading from central");
    }

    @Test
    void buildResultPromptBlock_absentWhenNoProbeOrBuildOutcome() {
        assertThat(EvolutionProtocolContract.getUserPrompt(baseContext(), null, false))
                .doesNotContain("--- BUILD RESULT ---");

        // Probe COMPILE_OK: no block.
        com.walpro.autobuilder.core.build.BuildOutcome okProbe = new com.walpro.autobuilder.core.build.BuildOutcome(
                com.walpro.autobuilder.core.build.BuildResult.COMPILE_OK,
                "",
                10L,
                "mvn -q compile",
                "test-cycle"
        );
        CycleContextData ctxOk = baseContext().withLastProbeOutcome(okProbe);
        assertThat(EvolutionProtocolContract.getUserPrompt(ctxOk, null, false))
                .doesNotContain("--- BUILD RESULT ---");
    }

    // ── T4-BN-3: focused test-repair prompt ──────────────────────────────────

    private CycleContextData focusedContext() {
        BuildOutcome testOnly = new BuildOutcome(
                BuildResult.COMPILE_ERROR,
                "/proj/output/src/test/java/app/FooTest.java:[1,1] cannot find symbol",
                10L, "mvn test-compile", "c");
        return baseContext()
                .withLastProbeOutcome(testOnly)
                .withCurrentGoalDescription("Provide a REST API with tests for every endpoint")
                .withCurrentSubGoalDescription("Add tests for LoanController")
                .withPreviousOutput(Map.of("pom.xml", "<project><artifactId>demo</artifactId></project>"))
                .withCumulativeCriteriaStatus(Map.of(0, Boolean.TRUE))
                .withCurrentSubGoalCriteria(List.of("Returns 200 on success"))
                .withPlanRationale("Cover every controller with a test class");
    }

    @Test
    void getUserPrompt_withTestRepairFocusSignal_rendersFocusedHeaderAndOmitsTrimmedSections() {
        String prompt = EvolutionProtocolContract.getUserPrompt(focusedContext(), null, false);

        assertThat(prompt)
                .contains("--- FOCUSED TEST-REPAIR MODE ---")
                .contains("--- BUILD RESULT ---")
                .doesNotContain("--- CRITERIA PROGRESS ---")
                .doesNotContain("--- PLAN CONTEXT ---")
                .doesNotContain("--- REQUIRED FILES ---");
    }

    @Test
    void getUserPrompt_withTestRepairFocusSignal_includesGuardDirectiveLanguageAndStaysEcosystemAgnostic() {
        String prompt = EvolutionProtocolContract.getUserPrompt(focusedContext(), null, false);

        assertThat(prompt)
                .contains("TEST-INFRA BREAK")
                .contains("TEST-AHEAD-OF-MAIN")
                .doesNotContain("JUnit")
                .doesNotContain("Mockito");
    }

    @Test
    void getUserPrompt_withTestRepairFocusSignal_includesPomContentFromPreviousOutput() {
        String prompt = EvolutionProtocolContract.getUserPrompt(focusedContext(), null, false);

        assertThat(prompt).contains("<project><artifactId>demo</artifactId></project>");
    }

    @Test
    void getUserPrompt_withTestRepairFocusSignal_stillEndsWithResponseFormatFooter() {
        String prompt = EvolutionProtocolContract.getUserPrompt(focusedContext(), null, false);

        assertThat(prompt).contains("#NoDiff").contains("Do not return unchanged files.");
    }

    @Test
    void getUserPrompt_withoutTestRepairFocusSignal_usesFullPromptPath() {
        String prompt = EvolutionProtocolContract.getUserPrompt(baseContext(), null, false);

        assertThat(prompt).doesNotContain("--- FOCUSED TEST-REPAIR MODE ---");
    }

    @Test
    void getUserPrompt_withTestRepairFocusSignalButNoLongerTestOnly_fallsBackToFullPrompt() {
        // Signal present (stale) but the resolved outcome is now main-broken — must not render
        // the focused block on data that no longer matches it.
        BuildOutcome mainBroken = new BuildOutcome(
                BuildResult.COMPILE_ERROR,
                "/proj/output/src/main/java/app/Foo.java:[1,1] cannot find symbol",
                10L, "mvn test-compile", "c");
        CycleContextData ctx = baseContext()
                .withLastProbeOutcome(mainBroken);

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null, false);

        assertThat(prompt)
                .doesNotContain("--- FOCUSED TEST-REPAIR MODE ---")
                .contains("--- BUILD RESULT ---");
    }

    // ------------------------------------------------------------------ //
    // T2-EP surface 2: previous-proposal structural digest                //
    // (formatLastRawOutput renders paths + declarations, not full source) //
    // ------------------------------------------------------------------ //

    private static final String BOOK_CONTROLLER = """
            package com.example.library.web;

            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class BookController {
                @PostMapping("/books")
                public Book createBook(Book book) {
                    return repository.save(book);
                }
            }
            """;

    @Test
    void formatLastRawOutput_rendersPathsAndDeclarations_notFullSource() {
        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/library/web/BookController.java", BOOK_CONTROLLER)),
                List.of(), "added controller", "```java [BookController.java]\n" + BOOK_CONTROLLER + "\n```");

        String rendered = EvolutionProtocolContract.formatLastRawOutput(outcome);

        assertThat(rendered).contains("[src/main/java/com/example/library/web/BookController.java]");
        assertThat(rendered).contains("createBook");
        assertThat(rendered).contains("@PostMapping");
        assertThat(rendered).doesNotContain("import org.springframework");
        assertThat(rendered).doesNotContain("return repository.save");
    }

    @Test
    void formatLastRawOutput_summarisesDeletedFiles() {
        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(), List.of("src/main/java/com/example/Old.java"), "cleanup", "#Delete [Old.java]", false, false);

        assertThat(EvolutionProtocolContract.formatLastRawOutput(outcome))
                .contains("#Delete [src/main/java/com/example/Old.java]");
    }

    @Test
    void formatLastRawOutput_reportsNoOp() {
        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(), List.of(), "already satisfied", "#NoDiff\nlooks complete", false, true);

        assertThat(EvolutionProtocolContract.formatLastRawOutput(outcome)).contains("#NoDiff");
    }

    @Test
    void formatLastRawOutput_malformedResponseWithNoMutations_fallsBackToCappedExcerpt() {
        String junk = "x".repeat(2000);
        EvolutionOutcome outcome = new EvolutionOutcome(List.of(), List.of(), "unparseable", junk);

        String rendered = EvolutionProtocolContract.formatLastRawOutput(outcome);

        assertThat(rendered).contains("… (truncated)");
        assertThat(rendered.length()).isLessThan(700);
    }

    @Test
    void formatLastRawOutput_nullOutcome_isNone() {
        assertThat(EvolutionProtocolContract.formatLastRawOutput(null)).isEqualTo("(none)");
    }

    @Test
    void getUserPrompt_previousProposalBlock_carriesStructureNotRawEcho() {
        EvolutionOutcome prior = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/library/web/BookController.java", BOOK_CONTROLLER)),
                List.of(), "added controller", "```java [BookController.java]\n" + BOOK_CONTROLLER + "\n```");
        CycleContextData ctx = CycleContextData.builder().lastEvolutionOutcome(prior).build();

        String prompt = EvolutionProtocolContract.getUserPrompt(ctx, null, false);

        assertThat(prompt).contains("Structure of your previous evolution proposal");
        assertThat(prompt).contains("createBook");
        assertThat(prompt).doesNotContain("import org.springframework.web.bind.annotation.RestController");
    }
}
