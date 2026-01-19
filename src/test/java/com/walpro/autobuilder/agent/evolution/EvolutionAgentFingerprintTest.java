package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
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
class EvolutionAgentFingerprintTest {

    @Mock private EvolutionLLMAdapter evolutionAdapter;
    @Mock private MutationApplier mutationApplier;
    @Mock private FingerprintService fingerprintService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ProjectDiffService projectDiffService;

    @Mock private ProjectDiffResult diffResult;

    private EvolutionAgent newAgent() {
        return new EvolutionAgent(evolutionAdapter, mutationApplier, fingerprintService, projectDiffService);
    }

    private CycleContextData baseContext() {
        return CycleContextData.builder()
                .cycleName("cycle-1")
                .previousAcceptedCycleName(null)
                .outputPath("build/out")
                .previousOutput(Map.of())
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .build()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));
    }

    @Test
    void execute_withSameFingerprintAsPreviousCycle_setsRepetitionDetectedSignal() throws Exception {
        EvolutionAgent agent = newAgent();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file",
                "#raw"
        );

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");

        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        doNothing().when(mutationApplier).applyRegularMutations(any());
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diffResult);

        CycleContextData context = baseContext()
                .withLastEvolutionFingerprint("fp-1"); // seed previous-cycle fingerprint

        CycleContextData result = agent.execute(context);

        assertThat(result.lastEvolutionFingerprint()).isEqualTo("fp-1");
        assertThat(result.signals().keySet()).anyMatch(s -> s.equals(BasicSignals.REPETITION_DETECTED));

        verify(fingerprintService, times(1)).computeEvolutionFingerprint(outcome);
    }

    @Test
    void execute_withDifferentFingerprintThanPreviousCycle_doesNotSetRepetitionDetectedSignal() throws Exception {
        EvolutionAgent agent = newAgent();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file",
                "#raw"
        );

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-2");

        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        doNothing().when(mutationApplier).applyRegularMutations(any());
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diffResult);

        CycleContextData context = baseContext()
                .withLastEvolutionFingerprint("fp-1");

        CycleContextData result = agent.execute(context);

        assertThat(result.lastEvolutionFingerprint()).isEqualTo("fp-2");
        assertThat(result.signals().keySet()).noneMatch(s -> s.equals(BasicSignals.REPETITION_DETECTED));

        verify(fingerprintService, times(1)).computeEvolutionFingerprint(outcome);
    }

    @Test
    void execute_afterStagnationSignal_usesDivergentEvolutionOnce() throws Exception {
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(inv -> {
                    CycleContextData ctx = inv.getArgument(0);
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main {}")),
                            List.of(),
                            "normal",
                            "#normal"
                    ));
                });

        when(evolutionAdapter.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(inv -> {
                    CycleContextData ctx = inv.getArgument(0);
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main { /* divergent */ }")),
                            List.of(),
                            "divergent",
                            "#divergent"
                    ));
                });

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        doNothing().when(mutationApplier).applyRegularMutations(any());
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        CycleContextData context = baseContext()
                .withSignal(Signal.of(BasicSignals.STAGNATION, 1.0, 0.2, "test"));

        // first execute() after stagnation → divergent
        CycleContextData first = agent.execute(context);

        assertThat(first.evolutionOutcome().rawResponse()).isEqualTo("#divergent");
        verify(evolutionAdapter, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAdapter, never()).evolve(any(CycleContextData.class));

        // second execute() → normal again (if STAGNATION is consumed by the agent)
        CycleContextData second = agent.execute(first);

        assertThat(second.evolutionOutcome().rawResponse()).isEqualTo("#normal");
        verify(evolutionAdapter, times(1)).evolve(any(CycleContextData.class));
    }

    @Test
    void execute_trimsRecentEvolutionFingerprintsToMaxWindow() throws Exception {
        EvolutionAgent agent = newAgent();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("a.txt", "A")),
                List.of(),
                "summary",
                "raw"
        );

        when(evolutionAdapter.evolve(any()))
                .thenAnswer(inv -> inv.<CycleContextData>getArgument(0).withEvolutionOutcome(outcome));
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-new");

//        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("output"));
        doNothing().when(mutationApplier).applyRegularMutations(any());
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diffResult);

        List<String> old = List.of("fp-1","fp-2","fp-3","fp-4","fp-5","fp-6","fp-7","fp-8","fp-9","fp-10","fp-11");

        CycleContextData ctx = baseContext()
                .withCycleName("c1")
                .withOutputPath("output")
                .withRecentEvolutionFingerprints(old);

        CycleContextData out = agent.execute(ctx);

        assertThat(out.recentEvolutionFingerprints().get(0)).isEqualTo("fp-new");
        assertThat(out.recentEvolutionFingerprints().size()).isLessThanOrEqualTo(10); // align with your constant
    }
}
