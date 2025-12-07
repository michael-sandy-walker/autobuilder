package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvolutionCoordinatorDiffPathTest {

    @Mock
    private EvolutionAgent evolutionAgent;

    @Mock
    private MutationApplier mutationApplier;

    @Mock
    private FingerprintService fingerprintService;

    @Mock
    private ProjectDiffService projectDiffService;

    @Mock
    private ProjectDiffResult diffResult;

    /**
     * bootstrap phase:
     *  - cycleName == "bootstrap" (or null)
     *  - previousAcceptedCycleName == null
     * <p>
     * Expected:
     *  previous = null
     *  current  = mutationApplier.resolveOutputPath()
     *  computeDiff(current, previous) is called in that order.
     */
    @Test
    void computeProjectDiff_usesOutputOnlyInBootstrapPhase() throws Exception {
        EvolutionCoordinator coordinator = new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService
        );

        Path outputPath = Path.of("build/out");
        when(mutationApplier.resolveOutputPath()).thenReturn(outputPath);
        when(projectDiffService.computeDiff(outputPath, null)).thenReturn(diffResult);

        CycleContextData context = CycleContextData.builder()
                .cycleName("bootstrap")
                .previousAcceptedCycleName(null)
                .build();

        ProjectDiffResult result = coordinator.computeProjectDiff(context);

        assertThat(result).isSameAs(diffResult);

        verify(mutationApplier).resolveOutputPath();
        verify(projectDiffService).computeDiff(outputPath, null);
        verifyNoMoreInteractions(projectDiffService);
    }

    /**
     * first mutation after bootstrap:
     *  - cycleName starts with "cycle-"
     *  - previousAcceptedCycleName == null
     * <p>
     * Expected:
     *  previous = outputPath
     *  current  = mutationPath(cycleName)
     *  computeDiff(current, previous)
     */
    @Test
    void computeProjectDiff_usesOutputAsPreviousForFirstMutationAfterBootstrap() throws Exception {
        EvolutionCoordinator coordinator = new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService
        );

        Path outputPath = Path.of("build/out");
        Path mutationPath = Path.of("mutations/cycle-1");

        when(mutationApplier.resolveOutputPath()).thenReturn(outputPath);
        when(mutationApplier.resolveMutationPath("cycle-1")).thenReturn(mutationPath);
        when(projectDiffService.computeDiff(mutationPath, outputPath)).thenReturn(diffResult);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .previousAcceptedCycleName(null)
                .build();

        ProjectDiffResult result = coordinator.computeProjectDiff(context);

        assertThat(result).isSameAs(diffResult);

        verify(mutationApplier).resolveOutputPath();
        verify(mutationApplier).resolveMutationPath("cycle-1");
        verify(projectDiffService).computeDiff(mutationPath, outputPath);
        verifyNoMoreInteractions(projectDiffService);
    }

    /**
     * later mutations:
     *  - cycleName starts with "cycle-"
     *  - previousAcceptedCycleName != null
     * <p>
     * Expected:
     *  previous = mutationPath(previousAcceptedCycleName)
     *  current  = mutationPath(cycleName)
     *  computeDiff(current, previous)
     */
    @Test
    void computeProjectDiff_usesPreviousAndCurrentMutationPathsForLaterCycles() throws Exception {
        EvolutionCoordinator coordinator = new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService
        );

        Path previousMutation = Path.of("mutations/cycle-1");
        Path currentMutation  = Path.of("mutations/cycle-2");

        when(mutationApplier.resolveMutationPath("cycle-1")).thenReturn(previousMutation);
        when(mutationApplier.resolveMutationPath("cycle-2")).thenReturn(currentMutation);
        when(projectDiffService.computeDiff(currentMutation, previousMutation)).thenReturn(diffResult);

        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-2")
                .previousAcceptedCycleName("cycle-1")
                .build();

        ProjectDiffResult result = coordinator.computeProjectDiff(context);

        assertThat(result).isSameAs(diffResult);

        verify(mutationApplier).resolveMutationPath("cycle-1");
        verify(mutationApplier).resolveMutationPath("cycle-2");
        verify(projectDiffService).computeDiff(currentMutation, previousMutation);
        verifyNoMoreInteractions(projectDiffService);
    }
}
