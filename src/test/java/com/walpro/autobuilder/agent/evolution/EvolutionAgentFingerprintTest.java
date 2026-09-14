package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.DerivedFacts;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvolutionAgentFingerprintTest {

    @Mock private EvolutionLLMAdapter evolutionAdapter;
    @Mock private MutationApplier mutationApplier;
    @Mock private FingerprintService fingerprintService;
    @Mock private EvolutionGraphService evolutionGraphService;
    @Mock private EvaluationGraphFacade evaluationGraphFacade;
    @Mock private GoalPlannerService goalPlannerService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ProjectDiffService projectDiffService;

    @Mock private ProjectDiffResult diffResult;

    private EvolutionAgent newAgent() {
        return new EvolutionAgent(
                evolutionAdapter,
                mutationApplier,
                fingerprintService,
                projectDiffService,
                evolutionGraphService,
                evaluationGraphFacade,
                goalPlannerService,
                new com.walpro.autobuilder.protocol.PromptTokenBudget()
        );
    }

    private static EvolutionOutcome oneFileOutcome(String raw) {
        return new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file",
                raw
        );
    }

    private CycleContextData baseContext() {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .lastAttemptId("cycle-1")
                .previousAcceptedCycleName(null)
                .outputPath("build/out")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .build()
                .grantWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of("evolution", 1.0, 0.1, "test"));
    }

    private void stubDiffPipeline() throws Exception {
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        when(mutationApplier.resolveMutationPath(anyString()))
                .thenAnswer(inv -> Path.of("mutations/" + inv.getArgument(0)));
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));
        when(projectDiffService.computeDiff(any(), any(), any())).thenReturn(diffResult);
    }

    @Test
    void execute_repeatByLastFingerprint_setsRepetitionDetectedAndDivergenceRequest() throws Exception {
        EvolutionAgent agent = newAgent();
        stubDiffPipeline();

        EvolutionOutcome outcome = oneFileOutcome("#raw");
        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");

        CycleContextData context = baseContext()
                .withLastEvolutionFingerprint("fp-1"); // repeat via last

        CycleContextData result = agent.execute(context);

        assertThat(result.lastEvolutionFingerprint()).isEqualTo("fp-1");
        assertThat(result.hasOpenRequest(BasicSignals.REPETITION_DETECTED)).isTrue();
        assertThat(result.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)).isTrue();

        verify(fingerprintService, times(1)).computeEvolutionFingerprint(outcome);
    }

    @Test
    void execute_repeatByRecentFingerprints_setsRepetitionDetectedAndDivergenceRequest() throws Exception {
        EvolutionAgent agent = newAgent();
        stubDiffPipeline();

        EvolutionOutcome outcome = oneFileOutcome("#raw");
        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-2");

        CycleContextData context = baseContext()
                .withLastEvolutionFingerprint("fp-1")
                .withRecentEvolutionFingerprints(List.of("fp-2", "fp-x")); // repeat via recent

        CycleContextData result = agent.execute(context);

        assertThat(result.hasOpenRequest(BasicSignals.REPETITION_DETECTED)).isTrue();
        assertThat(result.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
    }

    @Test
    void execute_nonRepeat_setsEvolutionFingerprintChangedSignal() throws Exception {
        EvolutionAgent agent = newAgent();
        stubDiffPipeline();

        EvolutionOutcome outcome = oneFileOutcome("#raw");
        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-2");

        CycleContextData context = baseContext()
                .withLastEvolutionFingerprint("fp-1")
                .withRecentEvolutionFingerprints(List.of("fp-x"));

        CycleContextData result = agent.execute(context);

        assertThat(result.lastEvolutionFingerprint()).isEqualTo("fp-2");
        assertThat(result.hasOpenRequest(BasicSignals.REPETITION_DETECTED)).isFalse();
        assertThat(DerivedFacts.evolutionFingerprintChanged(result)).isTrue();
    }

    @Test
    void execute_blankFingerprint_doesNotModifyFingerprintOrSignals() throws Exception {
        EvolutionAgent agent = newAgent();
        stubDiffPipeline();

        EvolutionOutcome outcome = oneFileOutcome("#raw");
        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("   "); // blank

        CycleContextData context = baseContext()
                .withLastEvolutionFingerprint("fp-1")
                .withRecentEvolutionFingerprints(List.of("fp-x"));

        CycleContextData result = agent.execute(context);

        // unchanged
        assertThat(result.lastEvolutionFingerprint()).isEqualTo("fp-1");
        assertThat(result.recentEvolutionFingerprints()).containsExactly("fp-x");
        assertThat(DerivedFacts.evolutionFingerprintChanged(result)).isFalse();
        assertThat(result.hasOpenRequest(BasicSignals.REPETITION_DETECTED)).isFalse();
    }

    @Test
    void execute_afterStagnationSignal_usesDivergentEvolutionOnceAndConsumesStagnation() throws Exception {
        EvolutionAgent agent = newAgent();
        stubDiffPipeline();

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(oneFileOutcome("#normal")));

        when(evolutionAdapter.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(oneFileOutcome("#divergent")));

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");

        CycleContextData context = baseContext()
                .issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(BasicSignals.STAGNATION, 1.0, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(1.0, 0.2)));

        CycleContextData first = agent.execute(context);

        assertThat(first.evolutionOutcome().rawResponse()).isEqualTo("#divergent");
        assertThat(first.hasOpenRequest(BasicSignals.STAGNATION)).isFalse();
        verify(evolutionAdapter, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAdapter, never()).evolve(any(CycleContextData.class));

        CycleContextData second = agent.execute(first);

        assertThat(second.evolutionOutcome().rawResponse()).isEqualTo("#normal");
        verify(evolutionAdapter, times(1)).evolve(any(CycleContextData.class));
    }

    @Test
    void execute_trimsAndDedupsRecentEvolutionFingerprints_max10_newestFirst() throws Exception {
        EvolutionAgent agent = newAgent();
        stubDiffPipeline();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("a.txt", "A")),
                List.of(),
                "summary",
                "raw"
        );

        when(evolutionAdapter.evolve(any()))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-new");

        List<String> old = List.of("fp-1","fp-2","fp-3","fp-4","fp-5","fp-6","fp-7","fp-8","fp-9","fp-10","fp-11");

        CycleContextData ctx = baseContext()
                .withCycleName("cycle-2")
                .withRecentEvolutionFingerprints(old);

        CycleContextData out = agent.execute(ctx);

        assertThat(out.recentEvolutionFingerprints().get(0)).isEqualTo("fp-new");
        assertThat(out.recentEvolutionFingerprints()).doesNotHaveDuplicates();
        assertThat(out.recentEvolutionFingerprints().size()).isLessThanOrEqualTo(10);
    }
}
