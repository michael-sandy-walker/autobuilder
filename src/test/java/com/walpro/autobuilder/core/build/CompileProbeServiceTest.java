package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.inject.CompileFixInjector;
import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1-EO: the injector's mirror-write into {@code mutations/<cycleId>/} must target the cycle dir
 * that actually holds the LLM mutations it augments — otherwise MergeAgent re-reads the LLM's
 * pre-injector variant from disk and clobbers the injector edit.
 */
class CompileProbeServiceTest {

    @TempDir Path tmp;

    private static final String MEMBER_CONTROLLER =
            "src/main/java/com/example/demo/controller/MemberController.java";

    private static final String LLM_CONTENT = """
            package com.example.demo.controller;
            public class MemberController {
                public List<String> all() { return null; }
            }
            """;

    private static final String INJECTED_CONTENT = """
            package com.example.demo.controller;
            import java.util.List;
            public class MemberController {
                public List<String> all() { return null; }
            }
            """;

    private FileService fileService;
    private BuildExecutor buildExecutor;
    private CompileFixInjector injector;
    private CompileProbeService probe;

    @BeforeEach
    void setUp() throws IOException {
        fileService = new FileService(tmp.toString(), new ObjectMapper());
        buildExecutor = mock(BuildExecutor.class);
        injector = mock(CompileFixInjector.class);
        // T4-BH: real PackageFragmentationCheck — these tests don't exercise fragmentation so it
        // just returns Optional.empty() against the tmp output tree (no pre-existing .java files
        // share the simple name of the test's MemberController write).
        PackageFragmentationCheck fragCheck = new PackageFragmentationCheck(fileService);
        probe = new CompileProbeService(fileService, new MergeRollbackService(), buildExecutor, injector, fragCheck,
                new com.walpro.autobuilder.core.build.inject.TestDependencyEnsurer(fileService));
        ReflectionTestUtils.setField(probe, "fileBasePath", tmp.toString());
        Files.createDirectories(tmp.resolve("output"));

        when(buildExecutor.isEnabled()).thenReturn(true);
        // First build (probe) fails on the missing symbol; second build (re-probe) clears.
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class List", 1L, "mvn", "probe"),
                new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "probe"));

        InjectorFixRecord record = new InjectorFixRecord(
                "symbol-import:import-only", Kind.CANNOT_FIND_SYMBOL, "List",
                List.of(MEMBER_CONTROLLER), "Added import java.util.List");
        FixProposal proposal = new FixProposal(
                List.of(new FileMutation(MEMBER_CONTROLLER, INJECTED_CONTENT)), record);
        when(injector.inject(any(), any())).thenReturn(Optional.of(proposal));
    }

    private CycleContextData.CycleContextDataBuilder baseContext() {
        return CycleContextData.builder()
                .cycleName("cycle-current")
                .outputPath("output")
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation(MEMBER_CONTROLLER, LLM_CONTENT)),
                        List.of(), "llm", ""));
    }

    /**
     * T5-N: the initial probe build and each inner-loop pass emit attempt-level
     * {@code build_fix_attempt} events with source, sequence, rule and duration.
     */
    @Test
    void probeAndInnerLoop_emitBuildFixAttemptEvents() throws IOException {
        com.walpro.autobuilder.observability.behaviour.BehaviourLogger behaviourLogger =
                mock(com.walpro.autobuilder.observability.behaviour.BehaviourLogger.class);
        probe.setBehaviourLogger(behaviourLogger);
        fileService.writeFile("mutations/cycle-current/" + MEMBER_CONTROLLER, LLM_CONTENT);

        CycleContextData ctx = baseContext().evolutionRanThisCycle(true).build();
        probe.probe(ctx, true);

        verify(behaviourLogger).buildFixAttempt("cycle-current", "probe", 0, null,
                "CANNOT_FIND_SYMBOL", null, List.of(), "COMPILE_ERROR", 1L);
        verify(behaviourLogger).buildFixAttempt("cycle-current", "inner-loop", 1,
                "symbol-import:import-only", "CANNOT_FIND_SYMBOL", "List",
                List.of(MEMBER_CONTROLLER), "COMPILE_OK", 1L);
    }

    /**
     * Evolution ran this cycle: the injector fix must land in the CURRENT cycle's mutation dir
     * even though {@code mutationPath} still points at the previous cycle (it is only refreshed
     * post-cycle by CyclePersistenceService).
     */
    @Test
    void injectorFix_mirroredToCurrentCycle_whenMutationPathIsStale() throws IOException {
        // Simulate MutationApplier having written the LLM (pre-import) variant for this cycle.
        fileService.writeFile("mutations/cycle-current/" + MEMBER_CONTROLLER, LLM_CONTENT);

        CycleContextData ctx = baseContext()
                .evolutionRanThisCycle(true)
                .mutationPath("mutations/cycle-stale")   // stale — points at the previous cycle
                .build();

        CompileProbeService.ProbeResult result = probe.probe(ctx, true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_OK);
        assertThat(result.appliedFixes()).hasSize(1);

        // The current cycle's variant now carries the injector's import.
        assertThat(fileService.readString("mutations/cycle-current/" + MEMBER_CONTROLLER))
                .isEqualTo(INJECTED_CONTENT)
                .contains("import java.util.List;");
        // The stale dir must NOT have received the fix.
        assertThat(fileService.exists("mutations/cycle-stale/" + MEMBER_CONTROLLER)).isFalse();
    }

    /**
     * Evolution did NOT run this cycle: the mutation was carried over from a prior cycle whose
     * dir is referenced by {@code mutationPath} — the fix must co-locate there.
     */
    @Test
    void injectorFix_mirroredToMutationPath_whenEvolutionDidNotRunThisCycle() throws IOException {
        fileService.writeFile("mutations/cycle-origin/" + MEMBER_CONTROLLER, LLM_CONTENT);

        CycleContextData ctx = baseContext()
                .evolutionRanThisCycle(false)
                .mutationPath("mutations/cycle-origin")
                .build();

        probe.probe(ctx, true);

        assertThat(fileService.readString("mutations/cycle-origin/" + MEMBER_CONTROLLER))
                .contains("import java.util.List;");
        assertThat(fileService.exists("mutations/cycle-current/" + MEMBER_CONTROLLER)).isFalse();
    }

    /**
     * Falls back to the current cycle dir when {@code mutationPath} is unset, rather than
     * dereferencing a null path.
     */
    @Test
    void injectorFix_mirroredToCurrentCycle_whenMutationPathIsNull() throws IOException {
        CycleContextData ctx = baseContext()
                .evolutionRanThisCycle(false)
                .mutationPath(null)
                .build();

        probe.probe(ctx, true);

        assertThat(fileService.readString("mutations/cycle-current/" + MEMBER_CONTROLLER))
                .contains("import java.util.List;");
    }

    /**
     * T4-BH: probe-time package-fragmentation gate. A pre-existing {@code BookService.java}
     * under {@code com.example.demo} and a proposed second one under {@code com.example.demo.service}
     * must short-circuit the probe with {@link BuildResult#FRAGMENTATION_REJECTED} before any
     * file is written and before any build is run.
     */
    @Test
    void probe_T4BH_returnsFragmentationRejected_withoutWritingFiles_orInvokingBuild() throws IOException {
        // Existing flat BookService on disk.
        String existingPath = "src/main/java/com/example/demo/BookService.java";
        fileService.writeFile("output/" + existingPath,
                "package com.example.demo; public class BookService {}");

        // Proposed mutation: a *second* BookService in a sub-package.
        String fragmentingPath = "src/main/java/com/example/demo/service/BookService.java";
        String fragmentingContent =
                "package com.example.demo.service; public class BookService {}";

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-frag")
                .outputPath("output")
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation(fragmentingPath, fragmentingContent)),
                        List.of(), "frag-test", ""))
                .build();

        // Replace buildExecutor's prior stub with a sentinel so we can assert "never called".
        // The default isEnabled() = true is still in effect from setUp().
        org.mockito.Mockito.reset(buildExecutor);
        when(buildExecutor.isEnabled()).thenReturn(true);

        CompileProbeService.ProbeResult result = probe.probe(ctx, true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.FRAGMENTATION_REJECTED);
        assertThat(result.outcome().stderrTail()).contains("BookService").contains("package fragmentation");
        assertThat(result.rollbackPoint()).isNull();
        // No file was written and no build invoked.
        assertThat(fileService.exists("output/" + fragmentingPath)).isFalse();
        org.mockito.Mockito.verify(buildExecutor, org.mockito.Mockito.never()).execute(any(), any());
    }

    @Test
    void innerFixLoop_T4AN6_chainsSecondFix_andClearsBuild() throws IOException {
        // Two independent error classes: fix 1 removes the List error, build stays red on Foo;
        // fix 2 removes Foo and the build clears. One-shot behaviour would have stopped (and
        // failure-recorded fix 1) after the first re-probe.
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class List", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Foo", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "p"));

        FixProposal first = new FixProposal(
                List.of(new FileMutation(MEMBER_CONTROLLER, INJECTED_CONTENT)),
                new InjectorFixRecord("symbol-import:import-only", Kind.CANNOT_FIND_SYMBOL, "List",
                        List.of(MEMBER_CONTROLLER), "import List"));
        FixProposal second = new FixProposal(
                List.of(new FileMutation("src/main/java/com/example/demo/Foo.java", "class Foo {}")),
                new InjectorFixRecord("skeleton-service", Kind.CANNOT_FIND_SYMBOL, "Foo",
                        List.of("src/main/java/com/example/demo/Foo.java"), "skeleton Foo"));
        when(injector.inject(org.mockito.ArgumentMatchers.contains("List"), any()))
                .thenReturn(Optional.of(first));
        when(injector.inject(org.mockito.ArgumentMatchers.contains("Foo"), any()))
                .thenReturn(Optional.of(second));

        CycleContextData ctx = baseContext().evolutionRanThisCycle(true).build();
        CompileProbeService.ProbeResult result = probe.probe(ctx, true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_OK);
        assertThat(result.appliedFixes()).hasSize(2);
        // Both attempts recorded so any prior blacklists clear.
        org.mockito.Mockito.verify(injector).recordReProbeOutcome(
                org.mockito.ArgumentMatchers.eq(first), any(), any(), any(), any(), any());
        org.mockito.Mockito.verify(injector).recordReProbeOutcome(
                org.mockito.ArgumentMatchers.eq(second), any(), any(), any(), any(), any());
    }

    @Test
    void innerFixLoop_T3BV_chainStopsOnNoProgress_rollsBackAndRecordsAttempt() throws IOException {
        // T3-BV: no fixed pass ceiling — the chain stops because the fix's targeted diagnostic
        // ("List") persists in the re-probe stderr AND the error count did not shrink. One
        // attempt, rolled back, recorded.
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class List", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class List", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class List", 1L, "mvn", "p"));

        CycleContextData ctx = baseContext().evolutionRanThisCycle(true).build();
        CompileProbeService.ProbeResult result = probe.probe(ctx, true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(result.filesOnDisk()).isFalse();
        assertThat(result.appliedFixes()).hasSize(1);
        // Working tree restored: the probe-written file was new, so rollback removes it entirely.
        assertThat(fileService.exists("output/" + MEMBER_CONTROLLER)).isFalse();
        org.mockito.Mockito.verify(injector, org.mockito.Mockito.times(1)).recordReProbeOutcome(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void innerFixLoop_T3BV_tokenLessConfigFix_progressMeasuredByKindChange() throws IOException {
        // bootstrap-pom has no missing-symbol token, and turning "no POM" into ordinary compile
        // errors does not shrink the error count — progress is the failure *kind* changing.
        // Pre-fix the chain stopped after pass 1 here (observed 2026-06-11); now it continues
        // and the second pass clears the build.
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "The goal you specified requires a project to execute but there is no POM in this directory", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "/x/A.java:[1,1] cannot find symbol\n  symbol:   class Foo\n1 error", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "p"));

        FixProposal pomFix = new FixProposal(
                List.of(new FileMutation("pom.xml", "<project/>")),
                new InjectorFixRecord("bootstrap-pom", Kind.MISSING_POM, null, List.of("pom.xml"), "bootstrap pom"));
        FixProposal symbolFix = new FixProposal(
                List.of(new FileMutation("src/main/java/com/example/demo/Foo.java", "class Foo {}")),
                new InjectorFixRecord("skeleton-class", Kind.CANNOT_FIND_SYMBOL, "Foo",
                        List.of("src/main/java/com/example/demo/Foo.java"), "skeleton Foo"));
        when(injector.inject(org.mockito.ArgumentMatchers.contains("no POM"), any()))
                .thenReturn(Optional.of(pomFix));
        when(injector.inject(org.mockito.ArgumentMatchers.contains("Foo"), any()))
                .thenReturn(Optional.of(symbolFix));

        CycleContextData ctx = baseContext().evolutionRanThisCycle(true).build();
        CompileProbeService.ProbeResult result = probe.probe(ctx, true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_OK);
        assertThat(result.appliedFixes()).hasSize(2);
    }

    @Test
    void targetedDiagnosticGone_locationLinesAndMemberErrors_doNotCountAsPersisting() {
        // After a Loan skeleton lands, remaining errors mention Loan only in location lines and
        // member-access diagnostics — the class reference resolved; the diagnostic is gone.
        String memberErrorsOnly = """
                /x/LoanService.java:[9,21] cannot find symbol
                  symbol:   method getBookId()
                  location: class com.example.demo.Loan
                /x/LoanController.java:[14,8] cannot find symbol
                  symbol:   method setReturned(boolean)
                  location: variable loan of type com.example.demo.Loan
                """;
        assertThat(CompileProbeService.targetedDiagnosticGone(memberErrorsOnly, "Loan")).isTrue();

        String classStillMissing = """
                /x/LoanService.java:[3,1] cannot find symbol
                  symbol:   class Loan
                """;
        assertThat(CompileProbeService.targetedDiagnosticGone(classStillMissing, "Loan")).isFalse();

        assertThat(CompileProbeService.targetedDiagnosticGone(
                "package javax.validation does not exist", "javax.validation")).isFalse();
        assertThat(CompileProbeService.targetedDiagnosticGone(
                "/x/A.java:[1,1] something unrelated", "javax.validation")).isTrue();
    }

    /**
     * T1-FE: the LLM emits the same path twice in one response often enough that staging must
     * capture the original on FIRST touch only. Pre-fix, the second iteration read back the
     * just-staged content and recorded it as "original"; the failure rollback then wrote the
     * REJECTED mutation into the baseline permanently (2026-07-03 field wedge: every later probe
     * failed on the poisoned baseline, zero merges for 45 min).
     */
    @Test
    void duplicatePathInOneMutationList_rollbackRestoresTrueOriginal() throws IOException {
        String greenOriginal = "package com.example.demo.controller;\npublic class MemberController { /* green */ }\n";
        fileService.writeFile("output/" + MEMBER_CONTROLLER, greenOriginal);
        String newFile = "src/main/java/com/example/demo/service/MemberService.java";
        String staged = "package com.example.demo.controller;\npublic class MemberController { /* staged */ }\n";

        // Probe fails and the injector offers nothing → the no-preserve rollback runs.
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class MemberService", 1L, "mvn", "probe"));
        when(injector.inject(any(), any())).thenReturn(Optional.empty());

        CycleContextData ctx = baseContext()
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation(MEMBER_CONTROLLER, staged),
                                new FileMutation(newFile, "package x; public class MemberService {}"),
                                // The duplicates: same paths again, as the LLM emits them.
                                new FileMutation(MEMBER_CONTROLLER, staged),
                                new FileMutation(newFile, "package x; public class MemberService {}")),
                        List.of(), "llm", ""))
                .build();
        probe.probe(ctx, true);

        // The pre-existing file is back at its true original, not the staged content …
        assertThat(fileService.readString("output/" + MEMBER_CONTROLLER)).isEqualTo(greenOriginal);
        // … and the newly-added file is deleted, not half-kept.
        assertThat(fileService.exists("output/" + newFile)).isFalse();
    }

    // ── T4-BN-3 scoped probe: test-touching mutations get a test-compile probe ──

    private static final String STAGED_TEST =
            "src/test/java/com/example/demo/controller/MemberControllerTest.java";

    private CycleContextData contextWithMutations(FileMutation... mutations) {
        return baseContext()
                .evolutionOutcome(new EvolutionOutcome(List.of(mutations), List.of(), "llm", ""))
                .evolutionRanThisCycle(true)
                .build();
    }

    @Test
    void testTouchingMutation_usesTestScopeCommand() throws IOException {
        when(buildExecutor.execute(any(), any(), org.mockito.ArgumentMatchers.eq("mvn test-compile")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn test-compile", "p"));

        CompileProbeService.ProbeResult result = probe.probe(contextWithMutations(
                new FileMutation(MEMBER_CONTROLLER, LLM_CONTENT),
                new FileMutation(STAGED_TEST, "class MemberControllerTest {}")), true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_OK);
        verify(buildExecutor).execute(any(), any(), org.mockito.ArgumentMatchers.eq("mvn test-compile"));
        org.mockito.Mockito.verify(buildExecutor, org.mockito.Mockito.never()).execute(any(), any());
    }

    @Test
    void mainOnlyMutation_keepsTheMainOnlyProbe() throws IOException {
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "p"));

        probe.probe(contextWithMutations(new FileMutation(MEMBER_CONTROLLER, LLM_CONTENT)), true);

        verify(buildExecutor).execute(any(), any());
        org.mockito.Mockito.verify(buildExecutor, org.mockito.Mockito.never())
                .execute(any(), any(), any());
    }

    @Test
    void testOnlyMutation_breakingItsOwnStagedTest_isGatedNotSteered() throws IOException {
        // The staged test file itself fails test-compile: rejecting sacrifices no main work, and
        // steering would merge a born-broken test (the run-17 trap). Injector offers nothing →
        // rolled back like any main compile failure.
        when(buildExecutor.execute(any(), any(), org.mockito.ArgumentMatchers.eq("mvn test-compile")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "/x/output/" + STAGED_TEST + ":[5,1] cannot find symbol", 1L, "mvn test-compile", "p"));
        when(injector.inject(any(), any())).thenReturn(Optional.empty());

        CompileProbeService.ProbeResult result = probe.probe(contextWithMutations(
                new FileMutation(STAGED_TEST, "class MemberControllerTest { Broken b; }")), true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(result.filesOnDisk()).isFalse();
        assertThat(fileService.exists("output/" + STAGED_TEST)).isFalse();
    }

    @Test
    void testOnlyMutation_breakingItsOwnStagedTest_stillEnsuresTestDependencyBeforeGating() throws IOException {
        // T4-BN-3 fix (2026-07-07): the deterministic test-dep-ensure fix must run even when the
        // mutation gets GATED (its own staged test breaks) — previously it only ran on the steer
        // branch below, so a mutation whose own test broke never got the pom fix and had to rely
        // on the LLM noticing the missing dependency from prose alone (observed live).
        fileService.writeFile("output/pom.xml", """
                <project>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                    </parent>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Object ensurer = ReflectionTestUtils.getField(probe, "testDependencyEnsurer");

        when(buildExecutor.execute(any(), any(), org.mockito.ArgumentMatchers.eq("mvn test-compile")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "/x/output/" + STAGED_TEST + ":[5,1] cannot find symbol", 1L, "mvn test-compile", "p"));
        when(injector.inject(any(), any())).thenReturn(Optional.empty());

        CompileProbeService.ProbeResult result = probe.probe(contextWithMutations(
                new FileMutation(STAGED_TEST, "class MemberControllerTest { Broken b; }")), true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(fileService.readFile("output/pom.xml")).contains("spring-boot-starter-test");
    }

    @Test
    void mixedMutation_testOnlyFailure_keepsTheSteerPath() throws IOException {
        // Main work rides along: the T4-BN-2 steer keeps the whole mutation, test break becomes
        // a diagnostic — gating here would re-create the main-evolution freeze.
        when(buildExecutor.execute(any(), any(), org.mockito.ArgumentMatchers.eq("mvn test-compile")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "/x/output/" + STAGED_TEST + ":[5,1] cannot find symbol", 1L, "mvn test-compile", "p"));

        CompileProbeService.ProbeResult result = probe.probe(contextWithMutations(
                new FileMutation(MEMBER_CONTROLLER, LLM_CONTENT),
                new FileMutation(STAGED_TEST, "class MemberControllerTest { Broken b; }")), true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(result.filesOnDisk()).isTrue();
        assertThat(fileService.exists("output/" + MEMBER_CONTROLLER)).isTrue();
    }

    @Test
    void testOnlyMutation_failureInOtherTestSources_staysSteered() throws IOException {
        // The staged test compiles; a PRE-EXISTING test elsewhere is broken. Rejecting would
        // discard good staged work over someone else's break — the reverted blanket gate's
        // exact unfairness.
        when(buildExecutor.execute(any(), any(), org.mockito.ArgumentMatchers.eq("mvn test-compile")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "/x/output/src/test/java/com/example/demo/OtherTest.java:[5,1] cannot find symbol",
                        1L, "mvn test-compile", "p"));

        CompileProbeService.ProbeResult result = probe.probe(contextWithMutations(
                new FileMutation(STAGED_TEST, "class MemberControllerTest {}")), true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(result.filesOnDisk()).isTrue();
        assertThat(fileService.exists("output/" + STAGED_TEST)).isTrue();
    }

    @Test
    void distinctErrorCount_prefersReportedTotal_thenDiagnosticLines() {
        assertThat(CompileProbeService.distinctErrorCount(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "[ERROR] x\n[INFO] 6 errors\n", 1L, "mvn", "p")))
                .isEqualTo(6);
        assertThat(CompileProbeService.distinctErrorCount(
                new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "/a/B.java:[3,1] cannot find symbol\n/a/B.java:[9,2] incompatible types\n", 1L, "mvn", "p")))
                .isEqualTo(2);
        assertThat(CompileProbeService.distinctErrorCount(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "opaque failure", 1L, "mvn", "p")))
                .isEqualTo(1);
        assertThat(CompileProbeService.distinctErrorCount(
                new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "p")))
                .isEqualTo(0);
    }

    /**
     * A delete directive is part of the candidate. Until 2026-09-14 the probe staged only the
     * written files, so a mutation that removed one of two parallel stores was compiled with both
     * present — and the tree the heuristics then judged still held the one it had deleted.
     */
    @Test
    void aDeleteDirectiveIsStagedForTheProbe_andRestoredByTheRollback() throws IOException {
        String repo = "src/main/java/com/example/demo/BookRepository.java";
        Files.createDirectories(tmp.resolve("output/src/main/java/com/example/demo"));
        Files.writeString(tmp.resolve("output/" + repo), "class BookRepository {}");
        java.util.List<Boolean> presentDuringBuild = new java.util.ArrayList<>();
        when(buildExecutor.execute(any(), any())).thenAnswer(inv -> {
            presentDuringBuild.add(Files.exists(tmp.resolve("output/" + repo)));
            return new BuildOutcome(BuildResult.COMPILE_ERROR, "unrelated: class Foo", 1L, "mvn", "probe");
        });
        when(injector.inject(any(), any())).thenReturn(Optional.empty());
        CycleContextData ctx = baseContext()
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation(MEMBER_CONTROLLER, LLM_CONTENT)), List.of(repo), "llm", ""))
                .build();

        CompileProbeService.ProbeResult result = probe.probe(ctx, false);

        assertThat(presentDuringBuild).as("the compiler saw the tree the mutation describes").containsExactly(false);
        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(tmp.resolve("output/" + repo)).as("a rejected probe puts the deleted file back").exists();
        assertThat(Files.readString(tmp.resolve("output/" + repo))).isEqualTo("class BookRepository {}");
    }

    @Test
    void aDeleteOnlyMutationIsStillProbed() throws IOException {
        String repo = "src/main/java/com/example/demo/BookRepository.java";
        Files.createDirectories(tmp.resolve("output/src/main/java/com/example/demo"));
        Files.writeString(tmp.resolve("output/" + repo), "class BookRepository {}");
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "probe"));
        CycleContextData ctx = baseContext()
                .evolutionOutcome(new EvolutionOutcome(List.of(), List.of(repo), "llm", ""))
                .build();

        CompileProbeService.ProbeResult result = probe.probe(ctx, false);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_OK);
        assertThat(tmp.resolve("output/" + repo)).as("COMPILE_OK leaves the candidate on disk, deletion included").doesNotExist();
        assertThat(result.rollbackPoint()).isNotNull();
        assertThat(result.rollbackPoint().originalContents()).containsKey(repo);
    }


    /**
     * T4-CQ: an injector fix that would write a path the operator protected is not made. Found on
     * the first adopted project, where the injector created an empty class under a source tree the
     * specification said not to touch, to satisfy a test's wrong reference.
     */
    @Test
    void injectorFix_isNotMade_whenItWouldWriteAProtectedPath() throws IOException {
        String stub = "src/main/java/com/example/demo/Stub.java";
        fileService.writeFile(ProtectedPaths.PATH, stub + "\n");
        ReflectionTestUtils.setField(probe, "protectedPaths", new ProtectedPaths(fileService));
        FixProposal creatingStub = new FixProposal(
                List.of(new FileMutation(stub, "package com.example.demo; class Stub {}")),
                new InjectorFixRecord("symbol-skeleton", Kind.CANNOT_FIND_SYMBOL, "Stub",
                        List.of(stub), "Created Stub"));
        when(injector.inject(any(), any())).thenReturn(Optional.of(creatingStub));
        fileService.writeFile("mutations/cycle-current/" + MEMBER_CONTROLLER, LLM_CONTENT);

        CompileProbeService.ProbeResult result = probe.probe(baseContext().evolutionRanThisCycle(true).build(), true);

        assertThat(result.appliedFixes()).isEmpty();
        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(fileService.exists("output/" + stub)).isFalse();
    }

    /**
     * T4-CQ, field78 (rule-extraction, 2026-10-18): the import a corrected candidate needed was proposed for four
     * files, one of them the protected specification test, and the whole fix was dropped. The protected file is left
     * out; the rest of the fix is made.
     */
    @Test
    void injectorFix_leavesOutOnlyTheProtectedFile_andMakesTheRest() throws IOException {
        String spec = "src/test/java/com/example/demo/SpecTest.java";
        String service = "src/main/java/com/example/demo/ColdRead.java";
        fileService.writeFile(ProtectedPaths.PATH, "src/test/**\n");
        ReflectionTestUtils.setField(probe, "protectedPaths", new ProtectedPaths(fileService));
        fileService.writeFile("output/" + spec, "package com.example.demo; class SpecTest {}");
        FixProposal importing = new FixProposal(
                List.of(new FileMutation(spec, "package com.example.demo; import x.Endpoint; class SpecTest {}"),
                        new FileMutation(service, "package com.example.demo; import x.Endpoint; class ColdRead {}")),
                new InjectorFixRecord("symbol-import:local", Kind.CANNOT_FIND_SYMBOL, "Endpoint",
                        List.of(spec, service), "added import x.Endpoint to 2 file(s)"));
        when(injector.inject(any(), any())).thenReturn(Optional.of(importing), Optional.empty());
        fileService.writeFile("mutations/cycle-current/" + MEMBER_CONTROLLER, LLM_CONTENT);

        CompileProbeService.ProbeResult result = probe.probe(baseContext().evolutionRanThisCycle(true).build(), true);

        assertThat(result.appliedFixes()).hasSize(1);
        assertThat(result.appliedFixes().get(0).mutations()).extracting(FileMutation::path).containsExactly(service);
        assertThat(fileService.readString("output/" + spec)).isEqualTo("package com.example.demo; class SpecTest {}");
    }
}
