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
 * High-level behaviour tests for EvolutionCoordinator (pipeline + signals),
 * complementary to EvolutionCoordinatorDiffPathTest and EvolutionCoordinatorFingerprintTest.
 */
@ExtendWith(MockitoExtension.class)
class EvolutionCoordinatorTest {

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

    private EvolutionCoordinator newCoordinator() {
        return new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService
        );
    }

    private CycleContextData baseContext() {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .previousAcceptedCycleName(null)
                .outputPath("build/out")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .build();
    }

    /**
     * Basic evolve() pipeline:
     *  - calls evolutionAgent.evolve(...)
     *  - writes evolutionOutcome and lastEvolutionOutcome
     *  - sets EVOLUTION_OCCURRED when there are changes
     *  - computes and stores projectDiff
     */
    @Test
    void evolve_withChanges_setsEvolutionOccurredAndStoresOutcomeAndDiff() throws Exception {
        EvolutionCoordinator coordinator = newCoordinator();

        CycleContextData ctx = baseContext();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file",
                "#raw"
        );

        when(evolutionAgent.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(outcome);
                });

        when(fingerprintService.computeFingerprint(any()))
                .thenReturn("fp-1");

        Path outPath = Path.of("build/out");
        when(mutationApplier.resolveOutputPath()).thenReturn(outPath);
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));

        when(projectDiffService.computeDiff(null, outPath)).thenReturn(diffResult);

        CycleContextData result = coordinator.evolve(ctx);

        // evolution agent was called
        verify(evolutionAgent).evolve(any(CycleContextData.class));

        // outcome is attached
        assertThat(result.evolutionOutcome()).isEqualTo(outcome);
        assertThat(result.lastEvolutionOutcome()).isEqualTo(outcome);

        // EVOLUTION_OCCURRED signal present because there are changes
        assertThat(result.signals().keySet())
                .anyMatch(t -> t.equals(BasicSignals.EVOLUTION_OCCURRED));

        // diff computed and stored
        verify(projectDiffService).computeDiff(null, outPath);
        assertThat(result.projectDiff()).isEqualTo(diffResult);
    }

    /**
     * Recombination path:
     *  - uses evolutionAgent.evolveDivergent(...)
     *  - still runs through fingerprint + diff + signal pipeline
     */
    @Test
    void recombineOnStagnation_usesDivergentEvolutionAndSetsSignalWhenChanged() throws Exception {
        EvolutionCoordinator coordinator = newCoordinator();

        CycleContextData ctx = baseContext();

        EvolutionOutcome divergentOutcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file (divergent)",
                "#raw-divergent"
        );

        when(evolutionAgent.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(divergentOutcome);
                });

        when(fingerprintService.computeFingerprint(any()))
                .thenReturn("fp-divergent");

        Path outPath = Path.of("build/out");
        when(mutationApplier.resolveOutputPath()).thenReturn(outPath);
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));
        when(projectDiffService.computeDiff(null, outPath)).thenReturn(diffResult);

        CycleContextData result = coordinator.recombineOnStagnation(ctx);

        // divergent path used
        verify(evolutionAgent).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAgent, never()).evolve(any());

        // outcome + fingerprint stored
        assertThat(result.evolutionOutcome()).isEqualTo(divergentOutcome);
        assertThat(result.lastEvolutionFingerprint()).isEqualTo("fp-divergent");

        // EVOLUTION_OCCURRED present because there were changes
        assertThat(result.signals().keySet())
                .anyMatch(t -> t.equals(BasicSignals.EVOLUTION_OCCURRED));

        // diff computed and stored
        verify(projectDiffService).computeDiff(null, outPath);
        assertThat(result.projectDiff()).isEqualTo(diffResult);
    }

    @Test
    void evolve_withoutChanges_doesNotSetEvolutionOccurredSignal() throws Exception {
        EvolutionCoordinator coordinator = newCoordinator();

        CycleContextData ctx = baseContext();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(),       // no added/changed files
                List.of(),       // no deletions
                "no-op",
                "#raw"
        );

        when(evolutionAgent.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(outcome);
                });

        when(fingerprintService.computeFingerprint(any()))
                .thenReturn("fp-1");

        Path outPath = Path.of("build/out");
        when(mutationApplier.resolveOutputPath()).thenReturn(outPath);
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));
        when(projectDiffService.computeDiff(null, outPath)).thenReturn(diffResult);

        CycleContextData result = coordinator.evolve(ctx);

        assertThat(result.signals().keySet())
                .noneMatch(t -> t.equals(BasicSignals.EVOLUTION_OCCURRED));
    }

}
