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
    void evolve_withSameFingerprintAsPreviousCycle_setsRepetitionDetectedSignal() throws Exception {
        EvolutionCoordinator coordinator = new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                signalBus,
                messageBus);

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file",
                "#raw"
        );

        when(evolutionAgent.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> invocation.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));

        when(fingerprintService.computeEvolutionFingerprint(any()))
                .thenReturn("fp-1");

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
                // seed previous-cycle fingerprint
                .lastEvolutionFingerprint("fp-1")
                .build();

        CycleContextData result = coordinator.evolve(context);

        assertThat(result.lastEvolutionFingerprint()).isEqualTo("fp-1");

        assertThat(result.signals().keySet())
                .anyMatch(s -> s.equals(BasicSignals.REPETITION_DETECTED));

        verify(fingerprintService, times(1)).computeEvolutionFingerprint(any());
    }

    @Test
    void evolve_withDifferentFingerprintThanPreviousCycle_doesNotSetRepetitionDetectedSignal() throws Exception {
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
                .thenAnswer(invocation -> invocation.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));

        when(fingerprintService.computeEvolutionFingerprint(any()))
                .thenReturn("fp-2");

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
                // previous-cycle fingerprint differs
                .lastEvolutionFingerprint("fp-1")
                .build();

        CycleContextData result = coordinator.evolve(context);

        assertThat(result.lastEvolutionFingerprint()).isEqualTo("fp-2");

        assertThat(result.signals().keySet())
                .noneMatch(s -> s.equals(BasicSignals.REPETITION_DETECTED));

        verify(fingerprintService, times(1)).computeEvolutionFingerprint(any());
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

        when(fingerprintService.computeEvolutionFingerprint(any()))
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

    @Test
    void evolve_trimsRecentEvolutionFingerprintsToMaxWindow() throws Exception {
        EvolutionCoordinator sut = new EvolutionCoordinator(
                evolutionAgent, mutationApplier, fingerprintService, projectDiffService, signalBus, messageBus
        );

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("a.txt", "A")),
                List.of(),
                "summary",
                "raw"
        );

        when(evolutionAgent.evolve(any())).thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-new");

        when(mutationApplier.resolveMutationPath(any())).thenReturn(Path.of("output"));
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diffResult);

        List<String> old = List.of("fp-1","fp-2","fp-3","fp-4","fp-5","fp-6","fp-7","fp-8","fp-9","fp-10","fp-11");
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .outputPath("output")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .recentEvolutionFingerprints(old)
                .build();

        CycleContextData out = sut.evolve(ctx);

        // expects: fp-new + (up to max-1 old entries)
        assertThat(out.recentEvolutionFingerprints().get(0)).isEqualTo("fp-new");
        assertThat(out.recentEvolutionFingerprints().size()).isLessThanOrEqualTo(10); // adjust to your constant
    }

}
