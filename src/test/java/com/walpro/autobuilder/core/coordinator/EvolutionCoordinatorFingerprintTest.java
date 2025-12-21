package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalBus;
import com.walpro.autobuilder.core.messaging.MessageBus;
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

    @Mock
    private SignalBus signalBus;

    @Mock
    private MessageBus messageBus;

    @Test
    void evolve_withSameFingerprintTwice_setsRepetitionDetectedSignal() throws Exception {
        EvolutionCoordinator coordinator = new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                signalBus,
                messageBus);

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
                projectDiffService,
                signalBus,
                messageBus
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

    @Test
    void execute_afterStagnationSignal_usesDivergentEvolutionOnce() throws Exception {
        EvolutionCoordinator coordinator = new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                signalBus,
                messageBus
        );

        // evolutionAgent: normal vs divergent distinguishable in calls
        when(evolutionAgent.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    // normal evolution path
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main {}")),
                            List.of(),
                            "normal",
                            "#normal"
                    ));
                });

        when(evolutionAgent.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    // divergent evolution path
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main { /* divergent */ }")),
                            List.of(),
                            "divergent",
                            "#divergent"
                    ));
                });

        when(fingerprintService.computeFingerprint(any()))
                .thenReturn("fp-1");
        when(mutationApplier.resolveOutputPath())
                .thenReturn(Path.of("build/out"));
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        // base context with ROLE_EVOLUTION so canExecute() is true
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .previousAcceptedCycleName(null)
                .outputPath("build/out")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));

        // simulate incoming STAGNATION signal
        Signal stagnation = Signal.of(BasicSignals.STAGNATION, 1.0, 0.2, "test");
        coordinator.onStagnationSignal(stagnation);

        // Act: first execute() after stagnation → divergent
        CycleContextData first = coordinator.execute(context);

        // Assert: divergent used
        assertThat(first.evolutionOutcome().rawResponse())
                .isEqualTo("#divergent");
        verify(evolutionAgent, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAgent, never()).evolve(any(CycleContextData.class));

        // Act: second execute() without another stagnation → normal
        CycleContextData second = coordinator.execute(first);

        assertThat(second.evolutionOutcome().rawResponse())
                .isEqualTo("#normal");
        verify(evolutionAgent, times(1)).evolve(any(CycleContextData.class));
    }
}
