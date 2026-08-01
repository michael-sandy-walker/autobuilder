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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2-EZ: when the inner fix chain stops with the build still red, a file the injector CREATED and
 * whose own compile diagnostic cleared is partial progress and stays; everything else goes.
 *
 * <p>Measured over 26 field runs before this shipped: 175 of 185 injector-created skeletons were
 * discarded by exactly this rollback, and 169 of those had provably removed the diagnostic they
 * targeted — after which 43.6% of (rule, token) fixes were re-derived in a later cycle to be
 * discarded again, up to the T2-EN run-level cap of 6.
 */
class CompileProbePreservedInjectorFilesTest {

    @TempDir Path tmp;

    private static final String LLM_FILE =
            "src/main/java/com/example/demo/controller/LoanController.java";
    private static final String SKELETON = "src/main/java/com/example/demo/model/Loan.java";
    private static final String LLM_CONTENT = "package com.example.demo.controller;\nclass LoanController {}\n";
    private static final String SKELETON_CONTENT = "package com.example.demo.model;\npublic class Loan {}\n";

    private FileService fileService;
    private BuildExecutor buildExecutor;
    private CompileFixInjector injector;
    private CompileProbeService probe;

    @BeforeEach
    void setUp() throws IOException {
        fileService = new FileService(tmp.toString(), new ObjectMapper());
        buildExecutor = mock(BuildExecutor.class);
        injector = mock(CompileFixInjector.class);
        probe = new CompileProbeService(fileService, new MergeRollbackService(), buildExecutor, injector,
                new PackageFragmentationCheck(fileService),
                new com.walpro.autobuilder.core.build.inject.TestDependencyEnsurer(fileService));
        ReflectionTestUtils.setField(probe, "fileBasePath", tmp.toString());
        Files.createDirectories(tmp.resolve("output"));
        when(buildExecutor.isEnabled()).thenReturn(true);
    }

    private CycleContextData context() {
        return CycleContextData.builder()
                .cycleName("cycle-current")
                .outputPath("output")
                .evolutionRanThisCycle(true)
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation(LLM_FILE, LLM_CONTENT)), List.of(), "llm", ""))
                .build();
    }

    private static FixProposal skeleton(String path, String token) {
        return new FixProposal(
                List.of(new FileMutation(path, SKELETON_CONTENT)),
                new InjectorFixRecord("skeleton-class", Kind.CANNOT_FIND_SYMBOL, token,
                        List.of(path), "Created " + token + " minimal class skeleton"));
    }

    /**
     * The chain's own progress test said the skeleton worked ("Loan" is gone from the final
     * stderr) — the build is red for a different missing type. The skeleton stays; the rejected
     * LLM file does not.
     */
    @Test
    void skeletonWhoseDiagnosticCleared_survivesTheRedBuildRollback() throws IOException {
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Loan", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class LoanService", 1L, "mvn", "p"));
        when(injector.inject(any(), any())).thenReturn(Optional.of(skeleton(SKELETON, "Loan")));

        CompileProbeService.ProbeResult result = probe.probe(context(), true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(result.filesOnDisk()).isFalse();
        assertThat(fileService.exists("output/" + SKELETON)).isTrue();
        assertThat(fileService.exists("output/" + LLM_FILE)).isFalse();
    }

    /**
     * The counter-case that keeps the rule honest: a skeleton whose own diagnostic is still in the
     * final stderr did not resolve anything — a wrong-package or hallucinated type — and is
     * discarded exactly as before.
     */
    @Test
    void skeletonWhoseDiagnosticPersists_isStillDiscarded() throws IOException {
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Loan", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Loan", 1L, "mvn", "p"));
        when(injector.inject(any(), any())).thenReturn(Optional.of(skeleton(SKELETON, "Loan")));

        probe.probe(context(), true);

        assertThat(fileService.exists("output/" + SKELETON)).isFalse();
    }

    /**
     * An injector fix that EDITS a file the LLM staged (the {@code symbol-import} shape) is a
     * modification of the content being rejected, not a new type. Preserving it would merge the
     * rejected mutation by the back door, so the file is restored to its pre-probe bytes.
     */
    @Test
    void injectorEditToAnLlmStagedFile_isNeverPreserved() throws IOException {
        String preExisting = "package com.example.demo.controller;\nclass LoanController { /* old */ }\n";
        fileService.writeFile("output/" + LLM_FILE, preExisting);
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class List", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Other", 1L, "mvn", "p"));
        when(injector.inject(any(), any())).thenReturn(Optional.of(new FixProposal(
                List.of(new FileMutation(LLM_FILE, "import java.util.List;\n" + LLM_CONTENT)),
                new InjectorFixRecord("symbol-import:import-only", Kind.CANNOT_FIND_SYMBOL, "List",
                        List.of(LLM_FILE), "Added import java.util.List"))));

        probe.probe(context(), true);

        assertThat(fileService.readString("output/" + LLM_FILE)).isEqualTo(preExisting);
        verify(injector, never()).noteInjectorFileOutcome(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    /**
     * T4-BH's invariant decides the edge case the field produced: one run's injector created
     * {@code LoanAlreadyReturnedException} under both {@code exception/} and {@code service/}.
     * Keeping a type that already lives elsewhere would leave two declarations of one simple name,
     * which compiles and breaks runtime dispatch — so the candidate is dropped.
     */
    @Test
    void skeletonThatWouldFragmentTheTree_isNotPreserved() throws IOException {
        String elsewhere = "src/main/java/com/example/demo/domain/Loan.java";
        fileService.writeFile("output/" + elsewhere, "package com.example.demo.domain;\npublic class Loan {}\n");
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Loan", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class LoanService", 1L, "mvn", "p"));
        when(injector.inject(any(), any())).thenReturn(Optional.of(skeleton(SKELETON, "Loan")));

        probe.probe(context(), true);

        assertThat(fileService.exists("output/" + SKELETON)).isFalse();
        assertThat(fileService.exists("output/" + elsewhere)).isTrue();
    }

    /**
     * Two skeletons for one type in a single chain — the same shape, seen within one chain rather
     * than across cycles. Neither is preserved: with no way to tell which package is right,
     * keeping either would pick one at random and keeping both would fragment.
     */
    @Test
    void twoSkeletonsForOneTypeInOneChain_areBothDropped() throws IOException {
        String second = "src/main/java/com/example/demo/service/Loan.java";
        ReflectionTestUtils.setField(probe, "fixChainEnabled", true);
        // Pass 1 keeps the chain alive on the shrinking error count while its own "Loan"
        // diagnostic persists; pass 2 clears it. Both skeletons are therefore preservable on the
        // final stderr, and both name the same type.
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Loan\n2 errors", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Loan\n1 error", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Nothing\n1 error", 1L, "mvn", "p"));
        when(injector.inject(any(), any())).thenReturn(
                Optional.of(skeleton(SKELETON, "Loan")), Optional.of(skeleton(second, "Loan")), Optional.empty());

        probe.probe(context(), true);

        // Both were written during the chain, and neither survives it.
        verify(injector, org.mockito.Mockito.times(3)).inject(any(), any());
        verify(injector).noteInjectorFileOutcome(2, 0);
        assertThat(fileService.exists("output/" + SKELETON)).isFalse();
        assertThat(fileService.exists("output/" + second)).isFalse();
    }

    /** The comparison arm: off, a red build discards injector files exactly as it did before. */
    @Test
    void switchedOff_theRedBuildDiscardsEverythingAgain() throws IOException {
        ReflectionTestUtils.setField(probe, "preserveClearedInjectorFiles", false);
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Loan", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class LoanService", 1L, "mvn", "p"));
        when(injector.inject(any(), any())).thenReturn(Optional.of(skeleton(SKELETON, "Loan")));

        probe.probe(context(), true);

        assertThat(fileService.exists("output/" + SKELETON)).isFalse();
        verify(injector).noteInjectorFileOutcome(1, 0);
    }

    /**
     * The case with the longest paper trail behind it: a bootstrapped {@code pom.xml}. It is
     * token-less, so its diagnostic is the error KIND, and the build turning from "no POM" into
     * ordinary compile errors is what clears it. Rolling this one back is how a run wedged for 25
     * minutes on 2026-06-16 — T1-FC re-enables the bootstrap afterwards, but not re-deleting it is
     * cheaper than re-creating it, and the same four conditions get there without a special case.
     */
    @Test
    void bootstrappedPom_survivesTheRedBuildRollback() throws IOException {
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "The goal you specified requires a project to execute but there is no POM in this directory",
                        1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "/x/A.java:[1,1] cannot find symbol\n  symbol:   class Loan\n1 error", 1L, "mvn", "p"));
        when(injector.inject(any(), any())).thenReturn(Optional.of(new FixProposal(
                List.of(new FileMutation("pom.xml", "<project><!-- bootstrapped --></project>")),
                new InjectorFixRecord("bootstrap-pom", Kind.MISSING_POM, "",
                        List.of("pom.xml"), "Bootstrapped a minimal pom"))));

        probe.probe(context(), true);

        assertThat(fileService.exists("output/pom.xml")).isTrue();
        assertThat(fileService.exists("output/" + LLM_FILE)).isFalse();
    }

    /** Both sides of the trade reach the run-level ledger — the effort as well as what it saved. */
    @Test
    void bothSidesOfTheTradeAreRecorded() throws IOException {
        when(buildExecutor.execute(any(), any())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class Loan", 1L, "mvn", "p"),
                new BuildOutcome(BuildResult.COMPILE_ERROR, "symbol: class LoanService", 1L, "mvn", "p"));
        when(injector.inject(any(), any())).thenReturn(Optional.of(skeleton(SKELETON, "Loan")));

        probe.probe(context(), true);

        verify(injector).noteInjectorFileOutcome(eq(1), eq(1));
    }
}
