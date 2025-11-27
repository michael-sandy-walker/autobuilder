package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class AdaptiveExecutionEngineEvolutionIntegrationTest {

    @Mock
    private EvolutionAgent evolutionAgent;

    @Mock
    private MutationApplier mutationApplier;

    @Mock
    private FingerprintService fingerprintService;

    @Mock
    private ProjectDiffService projectDiffService;

    @Test
    void runCycle_executesEvolutionCoordinator_andUpdatesContext() throws Exception {
        // Real coordinator, mocked internals
        EvolutionCoordinator evolutionCoordinator = new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService
        );

        // Real coordination strategy
        SequentialCoordinationStrategy strategy = new SequentialCoordinationStrategy();

        AdaptiveExecutionEngine engine = new AdaptiveExecutionEngine(
                List.of(evolutionCoordinator),
                strategy
        );

        // Context with a cycleName + outputPath and a ROLE_EVOLUTION signal,
        // so EvolutionCoordinator.canExecute(...) is true on the first pass.
        CycleContextData context = CycleContextData.builder()
                .cycleName("cycle-1")
                .outputPath("build/out")
                .build();

        context = context.withSignal(
                Signal.of(BasicSignals.ROLE_EVOLUTION, 0.8, 0.05, "test")
        );

        // Evolution outcome produced by the agent
        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/App.java", "class App {}")),
                List.of(),
                "Parsed 1 changed file(s), 0 deleted file(s).",
                "#raw"
        );

        when(evolutionAgent.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(outcome);
                });

        // Fingerprint just returns some non-repeating id
        when(fingerprintService.computeFingerprint(any()))
                .thenReturn("fp-1");

        // Diff computation: we don't care *how*, just that the coordinator
        // uses ProjectDiffService and stores the result in the context.
        ProjectDiffResult diffResult = new ProjectDiffResult(
                null,
                Map.of("src/main/java/App.java", "changed"),
                Set.of()
        );

        when(mutationApplier.resolveOutputPath())
                .thenReturn(Path.of("build/out"));

        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(diffResult);

        // Act
        CycleContextData result = engine.runCycle(context);

        // Assert: EvolutionCoordinator has run and updated the context
        assertThat(result.evolutionOutcome())
                .as("Evolution outcome should be attached to the context")
                .isEqualTo(outcome);

        assertThat(result.projectDiff())
                .as("Project diff should be computed and stored")
                .isEqualTo(diffResult);

        assertThat(result.signals())
                .as("Signals map should not be null")
                .isNotNull();

        // EVOLUTION_OCCURRED should have been emitted because there was at least one changed file
        assertThat(result.signals().keySet())
                .anyMatch(signalType -> signalType.equals(BasicSignals.EVOLUTION_OCCURRED));
    }
}
