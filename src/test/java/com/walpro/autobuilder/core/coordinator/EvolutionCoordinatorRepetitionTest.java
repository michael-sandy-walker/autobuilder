package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
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

@ExtendWith(MockitoExtension.class)
class EvolutionCoordinatorRepetitionTest {

    @Mock private EvolutionAgent evolutionAgent;
    @Mock private MutationApplier mutationApplier;
    @Mock private FingerprintService fingerprintService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ProjectDiffService projectDiffService;

    @Mock private ProjectDiffResult diffResult;
    @Mock private SignalBus signalBus;
    @Mock private MessageBus messageBus;

    @Test
    void evolve_whenFingerprintAlreadyInRecent_setsRepetitionDetected() throws Exception {
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
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");

        when(mutationApplier.resolveMutationPath(any())).thenReturn(Path.of("output"));
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diffResult);

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .outputPath("output")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .recentEvolutionFingerprints(List.of("fp-1", "fp-0"))
                .build();

        CycleContextData out = sut.evolve(ctx);

        verify(fingerprintService, times(1)).computeEvolutionFingerprint(outcome);

        assertThat(out.signals().keySet()).anyMatch(s -> s.equals(BasicSignals.REPETITION_DETECTED));
        assertThat(out.recentEvolutionFingerprints().get(0)).isEqualTo("fp-1");
        assertThat(out.recentEvolutionFingerprints().stream().filter("fp-1"::equals).count()).isEqualTo(1);
        assertThat(out.signals().keySet()).anyMatch(s -> s.equals(BasicSignals.DIVERGENCE_REQUEST));
    }

    @Test
    void evolve_whenFingerprintNotInRecent_updatesRecentAndDoesNotSetRepetitionDetected() throws Exception {
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

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .outputPath("output")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .recentEvolutionFingerprints(List.of("fp-1", "fp-0"))
                .build();

        CycleContextData out = sut.evolve(ctx);

        verify(fingerprintService, times(1)).computeEvolutionFingerprint(outcome);

        assertThat(out.signals().keySet()).noneMatch(s -> s.equals(BasicSignals.REPETITION_DETECTED));
        assertThat(out.recentEvolutionFingerprints()).startsWith("fp-new");
        assertThat(out.recentEvolutionFingerprints()).contains("fp-1", "fp-0");
        assertThat(out.recentEvolutionFingerprints().stream().filter("fp-1"::equals).count()).isEqualTo(1);
    }
}
