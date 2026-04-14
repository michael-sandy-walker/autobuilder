package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;

import com.walpro.autobuilder.graph.evolution.EvolutionGraphService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvolutionAgentDiffPathTest {

    @Mock private EvolutionLLMAdapter evolutionAdapter;
    @Mock private MutationApplier mutationApplier;
    @Mock private FingerprintService fingerprintService;
    @Mock private ProjectDiffService projectDiffService;
    @Mock private ProjectDiffResult diffResult;
    @Mock private EvolutionGraphService evolutionGraphService;

    private EvolutionAgent newAgent() {
        return new EvolutionAgent(evolutionAdapter, mutationApplier, fingerprintService, projectDiffService, evolutionGraphService);
    }

    @Test
    void computeProjectDiff_usesOutputOnlyInBootstrapPhase() throws Exception {
        EvolutionAgent agent = newAgent();

        Path outputPath = Path.of("build/out");
        when(mutationApplier.resolveOutputPath()).thenReturn(outputPath);
        when(projectDiffService.computeDiff(outputPath, null)).thenReturn(diffResult);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("bootstrap")
                .previousAcceptedCycleName(null)
                .build();

        ProjectDiffResult result = agent.computeProjectDiff(ctx);

        assertThat(result).isSameAs(diffResult);
        verify(mutationApplier).resolveOutputPath();
        verify(projectDiffService).computeDiff(outputPath, null);
        verifyNoMoreInteractions(projectDiffService);
    }

    @Test
    void computeProjectDiff_usesOutputAsPreviousForFirstMutationAfterBootstrap() throws Exception {
        EvolutionAgent agent = newAgent();

        Path outputPath = Path.of("build/out");
        Path mutationPath = Path.of("mutations/cycle-1");

        when(mutationApplier.resolveOutputPath()).thenReturn(outputPath);
        when(mutationApplier.resolveMutationPath("cycle-1")).thenReturn(mutationPath);
        when(projectDiffService.computeDiff(mutationPath, outputPath)).thenReturn(diffResult);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-1")
                .previousAcceptedCycleName(null)
                .build();

        ProjectDiffResult result = agent.computeProjectDiff(ctx);

        assertThat(result).isSameAs(diffResult);
        verify(mutationApplier).resolveOutputPath();
        verify(mutationApplier).resolveMutationPath("cycle-1");
        verify(projectDiffService).computeDiff(mutationPath, outputPath);
        verifyNoMoreInteractions(projectDiffService);
    }

    @Test
    void computeProjectDiff_usesPreviousAndCurrentMutationPathsForLaterCycles() throws Exception {
        EvolutionAgent agent = newAgent();

        Path prev = Path.of("mutations/cycle-1");
        Path curr = Path.of("mutations/cycle-2");

        when(mutationApplier.resolveMutationPath("cycle-1")).thenReturn(prev);
        when(mutationApplier.resolveMutationPath("cycle-2")).thenReturn(curr);
        when(projectDiffService.computeDiff(curr, prev)).thenReturn(diffResult);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-2")
                .previousAcceptedCycleName("cycle-1")
                .build();

        ProjectDiffResult result = agent.computeProjectDiff(ctx);

        assertThat(result).isSameAs(diffResult);
        verify(mutationApplier).resolveMutationPath("cycle-1");
        verify(mutationApplier).resolveMutationPath("cycle-2");
        verify(projectDiffService).computeDiff(curr, prev);
        verifyNoMoreInteractions(projectDiffService);
    }
}
