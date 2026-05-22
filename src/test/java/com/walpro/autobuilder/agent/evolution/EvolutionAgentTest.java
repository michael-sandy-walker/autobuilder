package com.walpro.autobuilder.agent.evolution;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphService;
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
 * High-level behavior tests for EvolutionAgent (pipeline + signals),
 * complementary to EvolutionAgentDiffPathTest and EvolutionAgentFingerprintTest.
 */
@ExtendWith(MockitoExtension.class)
class EvolutionAgentTest {

    @Mock
    private EvolutionLLMAdapter evolutionAdapter;

    @Mock
    private MutationApplier mutationApplier;

    @Mock
    private FingerprintService fingerprintService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ProjectDiffService projectDiffService;
    @Mock private EvolutionGraphService evolutionGraphService;
    @Mock private EvaluationGraphFacade evaluationGraphFacade;
    @Mock private GoalPlannerService goalPlannerService;

    @Mock
    private ProjectDiffResult diffResult;

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
        EvolutionAgent agent = newAgent();

        CycleContextData ctx = baseContext();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file",
                "#raw"
        );

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(outcome);
                });

        when(fingerprintService.computeEvolutionFingerprint(any()))
                .thenReturn("fp-1");

        Path outPath = Path.of("build/out");
        when(mutationApplier.resolveOutputPath()).thenReturn(outPath);
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));

        when(projectDiffService.computeDiff(null, outPath)).thenReturn(diffResult);

        CycleContextData result = agent.execute(ctx);

        // evolution agent was called
        verify(evolutionAdapter).evolve(any(CycleContextData.class));

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

    @Test
    void evolve_withoutChanges_doesNotSetEvolutionOccurredSignal() throws Exception {
        EvolutionAgent agent = newAgent();

        CycleContextData ctx = baseContext();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(),       // no added/changed files
                List.of(),       // no deletions
                "no-op",
                "#raw"
        );

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(outcome);
                });

        when(fingerprintService.computeEvolutionFingerprint(any()))
                .thenReturn("fp-1");

        Path outPath = Path.of("build/out");
        when(mutationApplier.resolveOutputPath()).thenReturn(outPath);
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));
        when(projectDiffService.computeDiff(null, outPath)).thenReturn(diffResult);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.signals().keySet())
                .noneMatch(t -> t.equals(BasicSignals.EVOLUTION_OCCURRED));
    }

    @Test
    void execute_afterStagnationInsight_usesDivergentEvolutionOnce() throws Exception {
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main {}")),
                            List.of(),
                            "normal",
                            "#normal"
                    ));
                });

        when(evolutionAdapter.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
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

        CycleContextData context = baseContext()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"));

        // simulate stagnation
        context = context.withSignal(Signal.of(BasicSignals.STAGNATION, 1.0, 0.1, "stagnating"));

        // first execute() → divergent
        CycleContextData first = agent.execute(context);
        assertThat(first.evolutionOutcome().rawResponse()).isEqualTo("#divergent");
        verify(evolutionAdapter, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAdapter, never()).evolve(any(CycleContextData.class));

        // second execute() → normal again (flag consumed)
        CycleContextData second = agent.execute(first);
        assertThat(second.evolutionOutcome().rawResponse()).isEqualTo("#normal");
        verify(evolutionAdapter, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAdapter, atLeastOnce()).evolve(any(CycleContextData.class));
    }

    @Test
    void execute_usesDivergentWhenEvaluationInterpretationIsRegressing() throws Exception {
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main { /* divergent */ }")),
                            List.of(),
                            "divergent",
                            "#divergent"
                    ));
                });

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        // mock interpretation -> REGRESSING
        EvaluationInterpreter.InterpretationResult interpretation = mock(EvaluationInterpreter.InterpretationResult.class);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.REGRESSING);

        CycleContextData context = baseContext()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                .withEvaluationInterpretation(interpretation);

        CycleContextData result = agent.execute(context);

        assertThat(result.evolutionOutcome().rawResponse()).isEqualTo("#divergent");
        verify(evolutionAdapter, times(1)).evolveDivergent(any(CycleContextData.class));
        verify(evolutionAdapter, never()).evolve(any(CycleContextData.class));
    }

    @Test
    void execute_usesNormalEvolutionWhenInterpretationIsNotRegressing() throws Exception {
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData ctx = invocation.getArgument(0);
                    return ctx.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/Main.java", "class Main {}")),
                            List.of(),
                            "normal",
                            "#normal"
                    ));
                });

        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        EvaluationInterpreter.InterpretationResult interpretation = mock(EvaluationInterpreter.InterpretationResult.class);
        when(interpretation.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        CycleContextData context = baseContext()
                .withSignal(Signal.of(BasicSignals.ROLE_EVOLUTION, 1.0, 0.1, "test"))
                .withEvaluationInterpretation(interpretation);

        CycleContextData result = agent.execute(context);

        assertThat(result.evolutionOutcome().rawResponse()).isEqualTo("#normal");
        verify(evolutionAdapter, times(1)).evolve(any(CycleContextData.class));
        verify(evolutionAdapter, never()).evolveDivergent(any());
    }

    // ── T2-CC: #NoDiff server-side surface-scan guard ────────────────────────

    @Test
    void noDiff_withNoGraphEvaluation_emitsEvolutionNoopDirectly() throws Exception {
        // LLM says #NoDiff and the graph has NO prior evaluation for this subgoal.
        // Cross-subgoal deferred-merge case: previousOutput contains code from a prior subgoal's
        // merge that already covers this feature — evidence exists, so we trust the LLM and emit
        // EVOLUTION_NOOP immediately (single-cycle promotion).
        // T1-CS: the null-eval noOp path now requires output evidence before trusting #NoDiff.
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any())).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(
                    new EvolutionOutcome(List.of(), List.of(), "#NoDiff — implementation appears complete", "#NoDiff", false, true));
        });
        when(evaluationGraphFacade.findLatestEvaluationStatusForSubgoal("sub-1")).thenReturn(null);

        // Provide output evidence for the subgoal — simulates deferred-merge case
        CycleContextData ctx = baseContext()
                .withCurrentSubGoalId("sub-1")
                .withCurrentSubGoalDescription("GET /books endpoint")
                .withPreviousOutput(Map.of("BookController.java", "public class BookController { @GetMapping(\"/books\") public List<Book> getBooks() {} }"));

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP))
                .as("deferred-merge #NoDiff with output evidence must emit EVOLUTION_NOOP directly")
                .isTrue();
        assertThat(result.hasSignal(BasicSignals.REPETITION_DETECTED)).isFalse();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isFalse();
        assertThat(result.evolutionRanThisCycle()).as("T2-DH: #NoDiff must not set evolutionRanThisCycle").isFalse();
    }

    @Test
    void noDiff_T1CS_withNoGraphEvaluation_andNoOutputEvidence_injectsDivergenceRequest() throws Exception {
        // T1-CS: LLM says #NoDiff, graph has NO prior evaluation, but previousOutput is empty
        // (brand-new subgoal, code was never written). Must NOT emit EVOLUTION_NOOP — instead
        // emit DIVERGENCE_REQUEST so the next cycle runs a real divergent evolution.
        EvolutionAgent agent = newAgent();

        EvolutionOutcome noDiffOutcome = new EvolutionOutcome(List.of(), List.of(), "#NoDiff — implementation appears complete", "#NoDiff", false, true);

        when(evolutionAdapter.evolve(any())).thenAnswer(inv ->
                ((CycleContextData) inv.getArgument(0)).withEvolutionOutcome(noDiffOutcome));
        when(evaluationGraphFacade.findLatestEvaluationStatusForSubgoal("sub-1")).thenReturn(null);

        // No output evidence — empty previousOutput simulates a fresh subgoal
        CycleContextData ctx = baseContext()
                .withCurrentSubGoalId("sub-1")
                .withCurrentSubGoalDescription("POST /members endpoint with name and email validation");

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP))
                .as("T1-CS: #NoDiff with no output evidence must NOT emit EVOLUTION_NOOP")
                .isFalse();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST))
                .as("T1-CS: must inject DIVERGENCE_REQUEST so next cycle runs real evolution")
                .isTrue();
        assertThat(result.evolutionRanThisCycle()).as("T2-DH: #NoDiff must not set evolutionRanThisCycle").isFalse();
        verify(evolutionAdapter, never()).evolveDivergent(any());
    }

    @Test
    void noDiff_withRejectedGraphEvaluation_rejectsUnverifiedNoDiffAndForcesDivergence() throws Exception {
        // LLM says #NoDiff but the graph shows last evaluation was REJECTED → unverified.
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any())).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(
                    new EvolutionOutcome(List.of(), List.of(), "#NoDiff — implementation appears complete", "#NoDiff", false, true));
        });
        when(evaluationGraphFacade.findLatestEvaluationStatusForSubgoal("sub-1")).thenReturn("REJECTED");

        CycleContextData ctx = baseContext().withCurrentSubGoalId("sub-1");

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP)).isFalse();
        assertThat(result.hasSignal(BasicSignals.REPETITION_DETECTED)).isTrue();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
        assertThat(result.evolutionRanThisCycle()).as("T2-DH: #NoDiff must not set evolutionRanThisCycle").isFalse();
    }

    @Test
    void noDiff_withAcceptedGraphEvaluation_emitsEvolutionNoop() throws Exception {
        // LLM says #NoDiff and the graph confirms last evaluation was ACCEPTED → trust it.
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any())).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(
                    new EvolutionOutcome(List.of(), List.of(), "#NoDiff — implementation appears complete", "#NoDiff", false, true));
        });
        when(evaluationGraphFacade.findLatestEvaluationStatusForSubgoal("sub-1")).thenReturn("ACCEPTED");

        CycleContextData ctx = baseContext().withCurrentSubGoalId("sub-1");

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP))
                .as("#NoDiff confirmed by graph must emit EVOLUTION_NOOP")
                .isTrue();
        assertThat(result.hasSignal(BasicSignals.REPETITION_DETECTED)).isFalse();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isFalse();
        assertThat(result.evolutionRanThisCycle()).as("T2-DH: #NoDiff must not set evolutionRanThisCycle").isFalse();
    }

    @Test
    void noDiff_T1DO_stopWordOnlyDescription_hasNoEvidence_doesNotEmitNoOp() throws Exception {
        // T1-DO fail-closed: the description consists entirely of stop-words and short tokens so
        // extractSurfaceScanTerms returns an empty set. The old fail-open path would return true
        // (conservatively trust #NoDiff) even though no evidence exists. After T1-DO the empty
        // set is treated as "no anchor — refuse" and DIVERGENCE_REQUEST is emitted instead.
        EvolutionAgent agent = newAgent();

        EvolutionOutcome noDiffOutcome = new EvolutionOutcome(
                List.of(), List.of(), "#NoDiff — already done", "#NoDiff", false, true);
        when(evolutionAdapter.evolve(any())).thenAnswer(inv ->
                ((CycleContextData) inv.getArgument(0)).withEvolutionOutcome(noDiffOutcome));
        when(evaluationGraphFacade.findLatestEvaluationStatusForSubgoal("sg-generic")).thenReturn(null);

        // Description: all stop-words — extractSurfaceScanTerms yields empty terms.
        // previousOutput: a Spring Boot controller that contains common English words (e.g. "status",
        // "request") — the old ≥4-char tokeniser would have matched these against the description.
        CycleContextData ctx = baseContext()
                .withCurrentSubGoalId("sg-generic")
                .withCurrentSubGoalDescription("When the request returns a valid status")
                .withPreviousOutput(Map.of(
                        "OrderController.java",
                        "public class OrderController { public ResponseEntity<Order> getOrder(@RequestParam String status) { return ResponseEntity.ok(null); } }"));

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP))
                .as("T1-DO: stop-word-only description must NOT emit EVOLUTION_NOOP")
                .isFalse();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST))
                .as("T1-DO: empty anchor set must emit DIVERGENCE_REQUEST")
                .isTrue();
        assertThat(result.evolutionRanThisCycle()).as("T2-DH: #NoDiff must not set evolutionRanThisCycle").isFalse();
    }

    @Test
    void noDiff_T1DO_featureNamedSubgoal_findsEvidence_emitsNoOp() throws Exception {
        // T1-DO regression: a feature-named subgoal with an HTTP path in the description
        // produces a non-empty anchor set and finds matching evidence in previousOutput —
        // EVOLUTION_NOOP must still be emitted (deferred-merge trust preserved).
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any())).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(
                    new EvolutionOutcome(List.of(), List.of(), "#NoDiff — endpoint exists", "#NoDiff", false, true));
        });
        when(evaluationGraphFacade.findLatestEvaluationStatusForSubgoal("sg-books")).thenReturn(null);

        CycleContextData ctx = baseContext()
                .withCurrentSubGoalId("sg-books")
                .withCurrentSubGoalDescription("Implement POST /books endpoint")
                .withPreviousOutput(Map.of(
                        "BookController.java",
                        "public class BookController { @PostMapping(\"/books\") public Book create(@RequestBody Book b) { return b; } }"));

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP))
                .as("T1-DO: feature subgoal with matching evidence must emit EVOLUTION_NOOP")
                .isTrue();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isFalse();
        assertThat(result.evolutionRanThisCycle()).as("T2-DH: #NoDiff must not set evolutionRanThisCycle").isFalse();
    }

    @Test
    void noDiff_onActiveBuildFixSubgoal_T1DN_forcesDivergence_andDoesNotEmitEvolutionNoOp() throws Exception {
        // T1-DN inner gate: LLM says #NoDiff on a BUILD_FIX subgoal (freshly injected, lastEvalStatus=null).
        // The build being broken is concrete evidence — must force divergence regardless of graph state.
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any())).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(
                    new EvolutionOutcome(List.of(), List.of(), "#NoDiff — build already fixed", "#NoDiff", false, true));
        });
        // No graph stub needed — the BUILD_FIX gate fires before the graph facade is ever consulted.

        GoalEntity buildFixGoal = new GoalEntity();
        buildFixGoal.setStatus(GoalStatus.ACTIVE);
        java.util.Map<String, Object> meta = new java.util.HashMap<>();
        meta.put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        buildFixGoal.setMetadata(meta);
        when(goalPlannerService.getGoalById("bf-1")).thenReturn(buildFixGoal);

        CycleContextData ctx = baseContext()
                .withCurrentSubGoalId("bf-1")
                .withCurrentSubGoalDescription("Fix compile error: no POM in this directory");

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP))
                .as("T1-DN: BUILD_FIX #NoDiff must NOT emit EVOLUTION_NOOP")
                .isFalse();
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST))
                .as("T1-DN: must inject DIVERGENCE_REQUEST")
                .isTrue();
        assertThat(result.hasSignal(BasicSignals.REPETITION_DETECTED))
                .as("T1-DN: must also set REPETITION_DETECTED")
                .isTrue();
        assertThat(result.evolutionRanThisCycle()).as("T2-DH: #NoDiff must not set evolutionRanThisCycle").isFalse();
        // Graph lookup must NOT have been called — inner gate fires before it.
        verify(evaluationGraphFacade, never()).findLatestEvaluationStatusForSubgoal(any());
    }

    // ── T2-AO: DIVERGENCE_REQUEST lifecycle ──────────────────────────────────

    @Test
    void divergenceRequest_consumed_when_fingerprintChanges() throws Exception {
        EvolutionAgent agent = newAgent();

        // LLM produces a new fingerprint → divergence happened → signal should be consumed
        when(evolutionAdapter.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/A.java", "new content")), List.of(), "diverged", "#raw"));
                });
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-new");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        doNothing().when(mutationApplier).applyRegularMutations(any());
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        CycleContextData ctx = baseContext()
                .withLastEvolutionFingerprint("fp-old")  // different from fp-new → actually diverged
                .withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "test"));

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST))
                .as("DIVERGENCE_REQUEST should be consumed after actual divergence")
                .isFalse();
    }

    @Test
    void divergenceRequest_retained_when_fingerprintUnchanged() throws Exception {
        EvolutionAgent agent = newAgent();

        // LLM produces the same fingerprint → no real divergence → signal should be kept alive
        when(evolutionAdapter.evolveDivergent(any(CycleContextData.class)))
                .thenAnswer(invocation -> {
                    CycleContextData c = invocation.getArgument(0);
                    return c.withEvolutionOutcome(new EvolutionOutcome(
                            List.of(new FileMutation("src/A.java", "same content")), List.of(), "no change", "#raw"));
                });
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-same");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        doNothing().when(mutationApplier).applyRegularMutations(any());
        when(projectDiffService.computeDiff(any(), any()))
                .thenReturn(new ProjectDiffResult(null, Map.of(), Set.of()));

        CycleContextData ctx = baseContext()
                .withLastEvolutionFingerprint("fp-same")  // same as produced → no divergence
                .withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "test"));

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST))
                .as("DIVERGENCE_REQUEST should be kept when LLM did not actually diverge")
                .isTrue();
    }

    // ── T1-EF: ROLE_EVALUATION emitted when evolution writes files ──────────

    @Test
    void evolve_withChanges_emitsRoleEvaluationSignal() throws Exception {
        // T1-EF: after a compile-probe rejection, ROLE_EVALUATION decays below the eligibility
        // threshold. Meta-step-only evolution cycles must re-emit ROLE_EVALUATION whenever they
        // write files so evaluation is eligible again on the next signal-driven phase.
        EvolutionAgent agent = newAgent();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "changed 1 file",
                "#raw"
        );

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> ((CycleContextData) invocation.getArgument(0)).withEvolutionOutcome(outcome));
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diffResult);

        CycleContextData result = agent.execute(baseContext());

        assertThat(result.hasSignal(BasicSignals.ROLE_EVALUATION))
                .as("T1-EF: writing files must reinstate ROLE_EVALUATION so evaluation is eligible")
                .isTrue();
        assertThat(result.getSignal(BasicSignals.ROLE_EVALUATION).strength())
                .as("T1-EF: ROLE_EVALUATION must clear the 0.10 eligibility threshold")
                .isGreaterThanOrEqualTo(0.10);
    }

    @Test
    void evolve_withoutChanges_doesNotEmitRoleEvaluationSignal() throws Exception {
        // Symmetric to evolve_withoutChanges_doesNotSetEvolutionOccurredSignal — when nothing is
        // written, ROLE_EVALUATION is not reinstated. Prevents accidental over-emission.
        EvolutionAgent agent = newAgent();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(), List.of(), "no-op", "#raw");

        when(evolutionAdapter.evolve(any(CycleContextData.class)))
                .thenAnswer(invocation -> ((CycleContextData) invocation.getArgument(0)).withEvolutionOutcome(outcome));
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-1");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        doNothing().when(mutationApplier).applyRegularMutations(any(CycleContextData.class));
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diffResult);

        CycleContextData result = agent.execute(baseContext());

        assertThat(result.hasSignal(BasicSignals.ROLE_EVALUATION))
                .as("T1-EF: noOp evolution must not reinstate ROLE_EVALUATION")
                .isFalse();
    }

    // ── T1-EE: EVOLUTION_NOOP signal carries subGoalId in meta ──────────────

    @Test
    void noDiff_emittedNoOpSignal_carriesSubGoalIdInMeta() throws Exception {
        // T1-EE: EVOLUTION_NOOP signal must carry the originating subGoalId so AutobuilderService
        // can reject a stale signal if evaluation has advanced the pointer mid-cycle.
        EvolutionAgent agent = newAgent();

        when(evolutionAdapter.evolve(any())).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(
                    new EvolutionOutcome(List.of(), List.of(), "#NoDiff — already present", "#NoDiff", false, true));
        });
        when(evaluationGraphFacade.findLatestEvaluationStatusForSubgoal("sg-test")).thenReturn("ACCEPTED");

        CycleContextData ctx = baseContext().withCurrentSubGoalId("sg-test");

        CycleContextData result = agent.execute(ctx);

        assertThat(result.hasSignal(BasicSignals.EVOLUTION_NOOP)).isTrue();
        com.walpro.autobuilder.core.lifecycle.Signal noOpSignal =
                result.getSignal(BasicSignals.EVOLUTION_NOOP);
        assertThat(noOpSignal.meta()).isNotNull();
        assertThat(noOpSignal.meta().get("subGoalId"))
                .as("T1-EE: EVOLUTION_NOOP must carry originating subGoalId")
                .isEqualTo("sg-test");
    }

    // ── T2-DN: incomplete_symbol_coverage guard ────────────────────────────

    @Test
    void coverageGuard_rejectsProposalThatMissesAnyRequiredSymbol() throws Exception {
        EvolutionAgent agent = newAgent();

        // T4-BD: previous cycle's failed probe outcome is now read from ctx.lastProbeOutcome().
        com.walpro.autobuilder.core.build.BuildOutcome probeFailed =
                new com.walpro.autobuilder.core.build.BuildOutcome(
                        com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                        "[ERROR] cannot find symbol\n  symbol: class BookRepository\n"
                                + "[ERROR] cannot find symbol\n  symbol: class MemberRepository\n"
                                + "[ERROR] cannot find symbol\n  symbol: class BookNotFoundException\n",
                        100L, "mvn -q compile", "prev-cycle");

        // Proposal only touches BookRepository — leaves MemberRepository + BookNotFoundException
        // unresolved.
        EvolutionOutcome partial = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/library/repository/BookRepository.java",
                        "package com.example.library.repository;\npublic interface BookRepository {}")),
                List.of(),
                "partial",
                "#raw");

        when(evolutionAdapter.evolve(any(CycleContextData.class))).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(partial);
        });

        CycleContextData ctx = baseContext().withLastProbeOutcome(probeFailed);

        CycleContextData result = agent.execute(ctx);

        EvolutionOutcome cleared = result.evolutionOutcome();
        assertThat(cleared.addedOrChangedFiles()).isEmpty();
        assertThat(cleared.summary()).contains("incomplete_symbol_coverage")
                .contains("MemberRepository")
                .contains("BookNotFoundException")
                .doesNotContain("BookRepository,"); // BookRepository was addressed
        assertThat(result.hasSignal(BasicSignals.DIVERGENCE_REQUEST)).isTrue();
        assertThat(result.hasSignal(BasicSignals.REPETITION_DETECTED)).isTrue();
        // Mutation apply must NOT have been called on a rejected proposal.
        verify(mutationApplier, never()).applyRegularMutations(any());
    }

    @Test
    void coverageGuard_acceptsProposalThatAddressesEveryRequiredSymbol() throws Exception {
        EvolutionAgent agent = newAgent();

        com.walpro.autobuilder.core.build.BuildOutcome probeFailed =
                new com.walpro.autobuilder.core.build.BuildOutcome(
                        com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR,
                        "[ERROR] cannot find symbol\n  symbol: class BookRepository\n"
                                + "[ERROR] cannot find symbol\n  symbol: class MemberRepository\n",
                        100L, "mvn -q compile", "prev-cycle");

        EvolutionOutcome full = new EvolutionOutcome(
                List.of(
                        new FileMutation("src/main/java/com/example/library/repository/BookRepository.java",
                                "package com.example.library.repository;\npublic interface BookRepository {}"),
                        new FileMutation("src/main/java/com/example/library/repository/MemberRepository.java",
                                "package com.example.library.repository;\npublic interface MemberRepository {}")
                ),
                List.of(),
                "full",
                "#raw");

        when(evolutionAdapter.evolve(any(CycleContextData.class))).thenAnswer(inv -> {
            CycleContextData c = inv.getArgument(0);
            return c.withEvolutionOutcome(full);
        });
        when(fingerprintService.computeEvolutionFingerprint(any())).thenReturn("fp-full");
        when(mutationApplier.resolveOutputPath()).thenReturn(Path.of("build/out"));
        when(projectDiffService.computeDiff(any(), any())).thenReturn(diffResult);

        CycleContextData ctx = baseContext().withLastProbeOutcome(probeFailed);

        CycleContextData result = agent.execute(ctx);

        assertThat(result.evolutionOutcome().addedOrChangedFiles()).hasSize(2);
        assertThat(result.evolutionOutcome().summary()).doesNotContain("incomplete_symbol_coverage");
        verify(mutationApplier).applyRegularMutations(any());
    }

    @Test
    void unresolvedSymbols_addressedByImportOrTypeDeclarationOrPath() {
        // Helper-level coverage for the addressedness heuristic.
        EvolutionOutcome o = new EvolutionOutcome(
                List.of(
                        new FileMutation("a.java", "import com.foo.MemberRepository; class A {}"),
                        new FileMutation("b/BookRepository.java", "package b; public interface BookRepository {}"),
                        new FileMutation("c.java", "class MyException extends RuntimeException {}")),
                List.of(),
                "ok",
                "#raw");

        Set<String> required = new java.util.LinkedHashSet<>();
        required.add("BookRepository");
        required.add("MemberRepository");
        required.add("MissingClass"); // unresolved
        Set<String> unresolved = EvolutionAgent.unresolvedSymbols(required, o);

        assertThat(unresolved).containsExactly("MissingClass");
    }

}
