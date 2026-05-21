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
        probe = new CompileProbeService(fileService, new MergeRollbackService(), buildExecutor, injector);
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
}
