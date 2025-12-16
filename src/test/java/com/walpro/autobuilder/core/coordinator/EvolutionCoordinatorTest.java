package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.arbitration.PlanArbiter;
import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.lifecycle.SignalBus;
import com.walpro.autobuilder.core.messaging.AgentMessage;
import com.walpro.autobuilder.core.messaging.AgentMessageType;
import com.walpro.autobuilder.core.messaging.AgentRoles;
import com.walpro.autobuilder.core.messaging.MessageBus;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.model.GoalProgressState;
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

    @Mock
    private SignalBus signalBus;

    @Mock
    private MessageBus messageBus;

    @Mock
    private PlanArbiter planArbiter;

    private EvolutionCoordinator newCoordinator() {
        return new EvolutionCoordinator(
                evolutionAgent,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                signalBus,
                messageBus,
                planArbiter
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

    @Test
    void evolve_publishesRequestMessageToEvaluation() throws Exception {
        EvolutionCoordinator coordinator = newCoordinator();

        // Use a bootstrap-like cycle so computeProjectDiff() uses outputPath-only branch
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("bootstrap")
                .previousAcceptedCycleName(null)
                .outputPath("build/out")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .build();

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

        when(projectDiffService.computeDiff(outPath, null)).thenReturn(diffResult);

        coordinator.evolve(ctx);

        var msgCaptor = org.mockito.ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageBus, atLeastOnce()).publish(msgCaptor.capture());

        AgentMessage msg = msgCaptor.getValue();
        assertThat(msg.fromAgent()).isEqualTo(AgentRoles.EVOLUTION);
        assertThat(msg.toAgent()).isEqualTo(AgentRoles.EVALUATION);
        assertThat(msg.type()).isEqualTo(AgentMessageType.REQUEST);
        assertThat(msg.payload())
                .containsEntry("cycleName", "bootstrap")
                .containsEntry("hasChanges", true);
    }

    @Test
    void execute_afterStagnationInsight_usesDivergentEvolutionOnce() throws Exception {
        EvolutionCoordinator coordinator = newCoordinator();

        when(evolutionAgent.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
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

        CycleContextData context = baseContext()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));

        // simulate incoming Evaluation INSIGHT with STAGNATING progress
        AgentMessage insight = new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.EVOLUTION,
                AgentMessageType.INSIGHT,
                "evolve",
                null,
                Map.of("goalProgress", GoalProgressState.STAGNATING.name())
        );
        coordinator.onAgentMessage(insight);

        // first execute() → divergent
        CycleContextData first = coordinator.execute(context);
        assertThat(first.evolutionOutcome().rawResponse()).isEqualTo("#divergent");
        verify(evolutionAgent, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAgent, never()).evolve(any(CycleContextData.class));

        // second execute() → normal again (flag consumed)
        CycleContextData second = coordinator.execute(first);
        assertThat(second.evolutionOutcome().rawResponse()).isEqualTo("#normal");
        verify(evolutionAgent, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAgent, atLeastOnce()).evolve(any(CycleContextData.class));
    }

    @Test
    void onAgentMessage_storesEvaluationResponsePayload() {
        EvolutionCoordinator coordinator = newCoordinator();

        AgentMessage response = new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.EVOLUTION,
                AgentMessageType.RESPONSE,
                "evolve",
                null,
                Map.of(
                        "cycleName", "cycle-42",
                        "evaluationScore", 0.73,
                        "goalProgress", GoalProgressState.PROGRESSING.name()
                )
        );

        coordinator.onAgentMessage(response);

        Map<String, Object> stored = coordinator.getLastEvaluationResponse();
        assertThat(stored)
                .containsEntry("cycleName", "cycle-42")
                .containsEntry("evaluationScore", 0.73)
                .containsEntry("goalProgress", GoalProgressState.PROGRESSING.name());
    }

    @Test
    void execute_usesDivergentWhenResponseIndicatesRegression() throws Exception {
        EvolutionCoordinator coordinator = newCoordinator();

        // Stub divergent evolution (this is the path we expect)
        when(evolutionAgent.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main { /* divergent */ }")),
                            List.of(),
                            "divergent",
                            "#divergent"
                    ));
                });

        // NO stub for evolutionAgent.evolve(...) here — we assert it's never called

        when(fingerprintService.computeFingerprint(any()))
                .thenReturn("fp-1");
        when(mutationApplier.resolveOutputPath())
                .thenReturn(Path.of("build/out"));
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        CycleContextData context = baseContext()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));

        // Simulate Evaluation RESPONSE with goalProgress=REGRESSING
        AgentMessage response = new AgentMessage(
                AgentRoles.EVALUATION,
                AgentRoles.EVOLUTION,
                AgentMessageType.RESPONSE,
                "evolve",
                null,
                Map.of(
                        "goalProgress", GoalProgressState.REGRESSING.name()
                )
        );
        coordinator.onAgentMessage(response);

        // Act
        CycleContextData result = coordinator.execute(context);

        // Assert: divergent path was taken
        assertThat(result.evolutionOutcome().rawResponse()).isEqualTo("#divergent");
        verify(evolutionAgent, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAgent, never()).evolve(any(CycleContextData.class));
    }

}
