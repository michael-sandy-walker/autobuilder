package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests the fingerprint + repetition-detection behaviour in EvolutionCoordinator.
 */
@ExtendWith(MockitoExtension.class)
class EvolutionCoordinatorFingerprintTest {

    @Mock
    private EvolutionAgent evolutionAgent;

    @Mock
    private MutationApplier mutationApplier;

    @Mock
    private FingerprintService fingerprintService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ProjectDiffService projectDiffService;

    @Mock
    private ProjectDiffResult diffResult;

    @Test
    void evolve_withSameFingerprintTwice_setsRepetitionDetectedSignal() throws Exception {
        EvolutionCoordinator coordinator = new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService
        );

        // evolution outcome with one changed file
        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file",
                "#raw"
        );

        when(evolutionAgent.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(outcome);
                });

        // fingerprint is the same on both updateFingerprint calls
        when(fingerprintService.computeFingerprint(any()))
                .thenReturn("fp-1");

        // avoid IO issues in runEvolution
        when(mutationApplier.resolveOutputPath())
                .thenReturn(Path.of("build/out"));
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(diffResult);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .previousAcceptedCycleName(null)
                .outputPath("build/out")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .build();

        CycleContextData result = coordinator.evolve(context);

        // fingerprint was written
        assertThat(result.lastEvolutionFingerprint())
                .isEqualTo("fp-1");

        // repetition signal must be present
        assertThat(result.signals().keySet())
                .anyMatch(s -> s.equals(BasicSignals.REPETITION_DETECTED));

        // sanity: fingerprintService was invoked at least twice (two updateFingerprint calls)
        verify(fingerprintService, atLeast(2)).computeFingerprint(any());
    }

    @Test
    void evolve_withDifferentFingerprints_doesNotSetRepetitionDetectedSignal() throws Exception {
        EvolutionCoordinator coordinator = new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService
        );

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file",
                "#raw"
        );

        when(evolutionAgent.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(outcome);
                });

        // first updateFingerprint → "fp-1", second → "fp-2"
        when(fingerprintService.computeFingerprint(any()))
                .thenReturn("fp-1", "fp-2");

        when(mutationApplier.resolveOutputPath())
                .thenReturn(Path.of("build/out"));
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(diffResult);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .previousAcceptedCycleName(null)
                .outputPath("build/out")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .build();

        CycleContextData result = coordinator.evolve(context);

        // last fingerprint is the second one
        assertThat(result.lastEvolutionFingerprint())
                .isEqualTo("fp-2");

        // no repetition signal when fingerprints differ
        assertThat(result.signals().keySet())
                .noneMatch(s -> s.equals(BasicSignals.REPETITION_DETECTED));
    }
}
