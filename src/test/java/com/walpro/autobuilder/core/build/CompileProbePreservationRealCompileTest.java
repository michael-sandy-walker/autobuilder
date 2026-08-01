package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.inject.CompileFixInjector;
import com.walpro.autobuilder.core.build.inject.CompileFixResolver;
import com.walpro.autobuilder.core.build.inject.FixAttemptLedger;
import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T2-EZ end to end against a REAL compiler. The mocked tests pin the decision logic against
 * hand-written stderr, which is exactly the input a wrong assumption would agree with — the
 * preservation rule turns on {@link CompileProbeService#fixTargetCleared}, whose whole job is to
 * read javac's own wording. This drives the same path with {@link JavacDirectProbe}, so the
 * diagnostics are produced by the compiler rather than by the test.
 *
 * <p>Scoped honestly: production runs the {@code maven} executor, not {@code javac-direct}, so
 * this proves the rule against the compiler's diagnostics rather than against Maven's framing of
 * them. That is the half that matters here — the {@code symbol:} lines the pattern reads are
 * javac's own and survive Maven's wrapping unchanged — but it is not a substitute for the field.
 *
 * <p>Skipped on a JRE, following {@link JavacDirectProbeTest}'s gate.
 */
class CompileProbePreservationRealCompileTest {

    @TempDir Path tmp;

    private static final String CONTROLLER = "src/main/java/com/example/LoanController.java";
    private static final String SKELETON = "src/main/java/com/example/Loan.java";

    /**
     * References a type it does not create AND calls a member on it. One missing token, so which
     * symbol javac reports first is not left to chance — and after a bare skeleton lands the build
     * stays red with {@code symbol: method describe() / location: class Loan}, which is precisely
     * the case the rule has to get right: the class name still appears in the errors, and that
     * appearance means it RESOLVED.
     */
    private static final String CONTROLLER_SRC = """
            package com.example;
            public class LoanController {
                public String borrow() { return new Loan().describe(); }
            }
            """;
    private static final String SKELETON_SRC = "package com.example;\npublic class Loan {}\n";

    private FileService fileService;
    private CompileProbeService probe;
    private FixAttemptLedger ledger;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system JDK compiler");
        fileService = new FileService(tmp.toString(), new ObjectMapper());
        Files.createDirectories(tmp.resolve("output"));

        // The working dir is overridden to <base>/output exactly as application.yml does it —
        // the probe passes file.base-path, and the override is what actually decides where the
        // compiler looks. Getting that wrong would compile an empty tree and pass for nothing.
        BuildExecutor buildExecutor = new BuildExecutor(
                true, "javac", 60_000L, tmp.resolve("output").toString(), "javac-direct");
        buildExecutor.setJavacDirectProbe(new JavacDirectProbe());

        ledger = new FixAttemptLedger(fileService);
        // One real resolver, offering the Loan skeleton once. Ledger, injector, probe, rollback
        // and fragmentation check are all the production classes.
        CompileFixResolver skeletonOnce = (classification, token, stderr, ctx) ->
                "Loan".equals(token)
                        ? Optional.of(new FixProposal(
                                List.of(new FileMutation(SKELETON, SKELETON_SRC)),
                                new InjectorFixRecord("skeleton-class", Kind.CANNOT_FIND_SYMBOL, "Loan",
                                        List.of(SKELETON), "Created Loan minimal class skeleton")))
                        : Optional.empty();
        BuildErrorClassifierAgent staticAgent = mock(BuildErrorClassifierAgent.class);
        when(staticAgent.classify(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> BuildErrorClassifier.classify(inv.getArgument(0)));
        CompileFixInjector injector = new CompileFixInjector(List.of(skeletonOnce), ledger, staticAgent,
                mock(com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.class),
                mock(com.walpro.autobuilder.core.build.inject.WellKnownPackageRegistry.class));

        probe = new CompileProbeService(fileService, new MergeRollbackService(), buildExecutor, injector,
                new PackageFragmentationCheck(fileService),
                new com.walpro.autobuilder.core.build.inject.TestDependencyEnsurer(fileService));
        ReflectionTestUtils.setField(probe, "fileBasePath", tmp.toString());
    }

    private CycleContextData context() {
        return CycleContextData.builder()
                .cycleName("cycle-real")
                .outputPath("output")
                .evolutionRanThisCycle(true)
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation(CONTROLLER, CONTROLLER_SRC)), List.of(), "llm", ""))
                .build();
    }

    /**
     * The whole chain on real diagnostics: javac reports the missing type, the injector resolves
     * it, javac still fails — now on the MEMBER — the chain runs out of proposals, and the type
     * that was genuinely resolved stays on disk while the rejected controller does not.
     */
    @Test
    void realJavacDiagnostics_theResolvedTypeSurvivesAndTheRejectedFileDoesNot() throws IOException {
        CompileProbeService.ProbeResult result = probe.probe(context(), true);

        assertThat(result.outcome().result()).isEqualTo(BuildResult.COMPILE_ERROR);
        // Still red — on the MEMBER, not the type. This is the discrimination the whole rule rests
        // on, and it is javac saying it, not the test.
        assertThat(result.outcome().stderrTail()).contains("describe");
        assertThat(result.appliedFixes()).hasSize(1);

        assertThat(fileService.exists("output/" + SKELETON)).isTrue();
        assertThat(fileService.readString("output/" + SKELETON)).isEqualTo(SKELETON_SRC);
        assertThat(fileService.exists("output/" + CONTROLLER)).isFalse();
        assertThat(ledger.readout().created()).isEqualTo(1);
        assertThat(ledger.readout().preserved()).isEqualTo(1);
    }

    /**
     * The half the mocks cannot check at all: that {@code targetedDiagnosticGone} reads real javac
     * wording. Before the skeleton javac writes {@code symbol:   class Loan}; after it, the build
     * is still red but says {@code symbol: method describe()} with {@code location: class Loan} —
     * the class name is still in the output and the answer must nonetheless flip to "resolved".
     * A bare substring test would answer "still missing" and re-create the starvation the fix
     * exists for (observed 2026-06-11). Asserted against compiler output, not a string in this file.
     */
    @Test
    void targetedDiagnosticGone_flipsOnRealJavacOutput_whenOnlyTheMemberIsStillMissing() throws IOException {
        fileService.writeFile("output/" + CONTROLLER, CONTROLLER_SRC);
        BuildOutcome before = compileOutput();

        assertThat(before.result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(before.stderrTail()).contains("symbol:");
        assertThat(CompileProbeService.targetedDiagnosticGone(before.stderrTail(), "Loan")).isFalse();

        fileService.writeFile("output/" + SKELETON, SKELETON_SRC);
        BuildOutcome after = compileOutput();

        assertThat(after.result()).isEqualTo(BuildResult.COMPILE_ERROR);
        // javac still names the class — on a location: line, which is what resolution looks like.
        assertThat(after.stderrTail()).contains("Loan");
        assertThat(CompileProbeService.targetedDiagnosticGone(after.stderrTail(), "Loan")).isTrue();
    }

    /** Runs the in-process compiler over {@code <tmp>/output} the way the probe does. */
    private BuildOutcome compileOutput() {
        BuildExecutor ex = new BuildExecutor(
                true, "javac", 60_000L, tmp.resolve("output").toString(), "javac-direct");
        ex.setJavacDirectProbe(new JavacDirectProbe());
        return ex.execute(tmp.toString(), "fixture");
    }

}
