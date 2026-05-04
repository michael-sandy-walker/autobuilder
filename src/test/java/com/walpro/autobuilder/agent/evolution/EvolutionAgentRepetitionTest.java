package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
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
class EvolutionAgentRepetitionTest {

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
                goalPlannerService
        );
    }

    private CycleContextData baseCtx() {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .lastAttemptId("cycle-1")
                .previousAcceptedCycleName(null)
                .outputPath("output")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .build();
    }

    private void stubDiffPipeline() throws Exception {
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("output"));
        when(mutationApplier.resolveMutationPath(anyString()))
                .thenAnswer(inv -> Path.of("mutations/" + inv.getArgument(0)));
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diffResult);
    }

    @Test
    void execute_whenFingerprintAlreadyInRecent_setsRepetitionDetectedAndDivergenceRequest() throws Exception {
        EvolutionAgent sut = newAgent();
        stubDiffPipeline();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("a.txt", "A")),
                List.of(),
                "summary",
                "raw"
        );

        when(evolutionAdapter.evolve(any()))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");

        CycleContextData ctx = baseCtx()
                .withLastEvolutionFingerprint("fp-x")
                .withRecentEvolutionFingerprints(List.of("fp-1", "fp-0"));

        CycleContextData out = sut.execute(ctx);

        verify(fingerprintService).computeEvolutionFingerprint(outcome);

        assertThat(out.signals().keySet()).anyMatch(s -> s.equals(BasicSignals.REPETITION_DETECTED));
        assertThat(out.signals().keySet()).anyMatch(s -> s.equals(BasicSignals.DIVERGENCE_REQUEST));

        assertThat(out.recentEvolutionFingerprints().get(0)).isEqualTo("fp-1");
        assertThat(out.recentEvolutionFingerprints()).doesNotHaveDuplicates();
    }

    @Test
    void execute_whenFingerprintNotInRecent_setsFingerprintChanged_andDoesNotSetRepetitionDetected() throws Exception {
        EvolutionAgent sut = newAgent();
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

        CycleContextData ctx = baseCtx()
                .withLastEvolutionFingerprint("fp-old")
                .withRecentEvolutionFingerprints(List.of("fp-1", "fp-0"));

        CycleContextData out = sut.execute(ctx);

        assertThat(out.signals().keySet()).noneMatch(s -> s.equals(BasicSignals.REPETITION_DETECTED));
        assertThat(out.signals().keySet()).anyMatch(s -> s.equals(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED));

        assertThat(out.recentEvolutionFingerprints()).startsWith("fp-new");
        assertThat(out.recentEvolutionFingerprints()).contains("fp-1", "fp-0");
        assertThat(out.recentEvolutionFingerprints()).doesNotHaveDuplicates();
    }

    @Test
    void execute_trimsRecentEvolutionFingerprintsToMaxWindow_10() throws Exception {
        EvolutionAgent sut = newAgent();
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

        CycleContextData ctx = baseCtx()
                .withCycleName("cycle-2")
                .withRecentEvolutionFingerprints(old);

        CycleContextData out = sut.execute(ctx);

        assertThat(out.recentEvolutionFingerprints().get(0)).isEqualTo("fp-new");
        assertThat(out.recentEvolutionFingerprints().size()).isLessThanOrEqualTo(10);
    }
}
