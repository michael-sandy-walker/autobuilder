package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.structure.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.goal.AbandonedGoalQueryService;
import com.walpro.autobuilder.goal.BuildFixClosureGate;
import com.walpro.autobuilder.goal.SubsumptionReason;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.walpro.autobuilder.core.lifecycle.BasicSignals.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CycleContextCoordinatorTest {

    @Mock private FileService fileService;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private DefaultProjectStructureSummarizer projectStructureSummarizer;
    @Mock private ContextLineageTracker lineageTracker;
    @Mock private CycleStartSignalSeeder cycleStartSignalSeeder;
    @Mock private EvaluationGraphFacade evaluationGraphFacade;
    @Mock private AbandonedGoalQueryService abandonedGoalQueryService;
    @Mock private com.walpro.autobuilder.goal.BuildFixClosureGate buildFixClosureGate;

    @Test
    void prepareRuntimeContext_carriesRecentEvolutionFingerprintsFromPrevious() throws IOException {
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService, buildFixClosureGate
        );

        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of("a.txt", "A"));
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");

        GoalEntity root = new GoalEntity("g1", "root goal", "role", GoalType.GOAL, null);
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        CycleContextData previous = CycleContextData.builder()
                .cycleName("prev")
                .recentEvolutionFingerprints(List.of("fp-1", "fp-2"))
                .build();

        Optional<CycleContextData> outOpt = sut.prepareRuntimeContext(previous);

        assertThat(outOpt).isPresent();
        assertThat(outOpt.get().recentEvolutionFingerprints()).containsExactly("fp-1", "fp-2");
//        assertThat(outOpt.get().recentEvolutionFingerprints()).isNotSameAs(previous.recentEvolutionFingerprints()); // TODO: Should this be a copy instead?
    }

    @Test
    void prepareRuntimeContext_subgoalSwitchGivesCleanSignalSlate() throws IOException {
        // New subgoal entity has no persisted signals → subgoal-scoped signals from the previous
        // context must be absent; structural signals must pass through unchanged.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService, buildFixClosureGate
        );

        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of());
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");

        GoalEntity root = new GoalEntity("g1", "root goal", "role", GoalType.GOAL, null);
        GoalEntity newSubgoal = new GoalEntity("sg-new", "new subgoal", "role", GoalType.SUBGOAL, "g1");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(newSubgoal);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        when(goalPlannerService.getGoalMetadataInt(newSubgoal, "cumulativeStagnatingCycles")).thenReturn(0);
        when(goalPlannerService.getCriteriaPassStatus(newSubgoal)).thenReturn(Map.of());
        // New subgoal has no previously persisted signals
        when(goalPlannerService.getSubgoalSignals(newSubgoal)).thenReturn(Map.of());

        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        CycleContextData previous = CycleContextData.builder()
                .cycleName("prev")
                .currentSubGoalId("sg-old")
                .build();
        previous = previous.withSignal(Signal.of(STAGNATION,           0.7, 0.2, "test"));
        previous = previous.withSignal(Signal.of(REPETITION_DETECTED,  0.6, 0.2, "test"));
        previous = previous.withSignal(Signal.of(DIVERGENCE_REQUEST,   0.5, 0.2, "test"));
        previous = previous.withSignal(Signal.of(LOW_CONFIDENCE,       0.4, 0.2, "test"));
        previous = previous.withSignal(Signal.of(SCORE_PLATEAU,        0.3, 0.2, "test"));
        previous = previous.withSignal(Signal.of(REPLAN_REQUESTED,     0.8, 0.1, "test"));

        Optional<CycleContextData> outOpt = sut.prepareRuntimeContext(previous);

        assertThat(outOpt).isPresent();
        CycleContextData out = outOpt.get();
        // Subgoal-scoped signals must be absent (entity returned nothing for new subgoal)
        assertThat(out.hasSignal(STAGNATION)).isFalse();
        assertThat(out.hasSignal(REPETITION_DETECTED)).isFalse();
        assertThat(out.hasSignal(DIVERGENCE_REQUEST)).isFalse();
        assertThat(out.hasSignal(LOW_CONFIDENCE)).isFalse();
        assertThat(out.hasSignal(SCORE_PLATEAU)).isFalse();
        // Structural signal must be preserved (not in SUBGOAL_SCOPED_SIGNALS)
        assertThat(out.hasSignal(REPLAN_REQUESTED)).isTrue();
    }

    @Test
    void prepareRuntimeContext_firesBuildFixClosureGate_onDiagnosticDisappeared() throws IOException {
        // T1-EX WIRING test: prove the gate fires THROUGH the coordinator on its unique slice —
        // a phantom [BUILD_FIX] subgoal whose referenced symbol/file no longer appears in the
        // previous probe's COMPILE_ERROR stderr. Uses the REAL gate (not the @Mock field) so the
        // CycleContextCoordinator → gate integration point is exercised, not just gate logic.
        // This is the case MergeAgent's intra-cycle COMPILE_OK clear does NOT cover, and the one
        // that didn't arise in the 06-03 / 06-05 runs (where replan/completion closed first).
        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        BuildFixClosureGate realGate = new BuildFixClosureGate(goalPlannerService);
        realGate.setBehaviourLogger(behaviourLogger);

        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker,
                cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService, realGate
        );

        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of());
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");

        GoalEntity root = new GoalEntity("g1", "root goal", "role", GoalType.GOAL, null);
        GoalEntity phantom = new GoalEntity();
        phantom.setId("goal-phantom");
        phantom.setDescription("[BUILD_FIX] Resolve missing symbol getBooks in Foo.java");
        phantom.setStatus(GoalStatus.ACTIVE);
        Map<String, Object> meta = new HashMap<>();
        meta.put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        meta.put(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_SYMBOL, "getBooks");
        meta.put(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_FILE, "src/main/java/Foo.java");
        phantom.setMetadata(meta);

        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(phantom);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        when(goalPlannerService.getGoalMetadataInt(phantom, "cumulativeStagnatingCycles")).thenReturn(0);
        when(goalPlannerService.getCriteriaPassStatus(phantom)).thenReturn(Map.of());
        when(goalPlannerService.getSubgoalSignals(phantom)).thenReturn(Map.of());

        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        // Previous cycle's build: still COMPILE_ERROR, but the diagnostic mentions neither
        // `getBooks` nor `Foo.java` — the phantom's referenced error has disappeared.
        CycleContextData previous = CycleContextData.builder()
                .cycleName("prev")
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "[ERROR] /x/src/main/java/Other.java:[1,5] cannot find symbol: class Qux",
                        100L, "mvn compile", null))
                .build();

        sut.prepareRuntimeContext(previous);

        // The gate fired through the coordinator: phantom SUBSUMED/COVERED + behaviour event emitted.
        verify(goalPlannerService).transitionGoal(eq("goal-phantom"), eq(GoalStatus.SUBSUMED),
                anyString(), eq(SubsumptionReason.COVERED));
        verify(behaviourLogger).buildFixSubgoalClosed(anyString(), eq("goal-phantom"),
                eq("getBooks"), eq("src/main/java/Foo.java"), eq("diagnostic_disappeared"));
    }

    @Test
    void prepareRuntimeContext_restoresSubgoalScopedSignalsFromEntity() throws IOException {
        // Entity has STAGNATION persisted → it is restored regardless of what the carried context had.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService, buildFixClosureGate
        );

        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of());
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");

        GoalEntity root = new GoalEntity("g1", "root goal", "role", GoalType.GOAL, null);
        GoalEntity subgoal = new GoalEntity("sg-1", "subgoal", "role", GoalType.SUBGOAL, "g1");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(subgoal);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        when(goalPlannerService.getGoalMetadataInt(subgoal, "cumulativeStagnatingCycles")).thenReturn(0);
        when(goalPlannerService.getCriteriaPassStatus(subgoal)).thenReturn(Map.of());
        // Entity has STAGNATION persisted from a previous cycle
        Signal persistedStagnation = Signal.of(STAGNATION, 0.5, 0.2, "restored");
        when(goalPlannerService.getSubgoalSignals(subgoal))
                .thenReturn(Map.of(STAGNATION, persistedStagnation));

        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        // Previous context has no subgoal-scoped signals (e.g. first run after restart)
        CycleContextData previous = CycleContextData.builder()
                .cycleName("prev")
                .currentSubGoalId("sg-1")
                .build();

        Optional<CycleContextData> outOpt = sut.prepareRuntimeContext(previous);

        assertThat(outOpt).isPresent();
        // STAGNATION must be present, sourced from the entity
        assertThat(outOpt.get().hasSignal(STAGNATION)).isTrue();
        assertThat(outOpt.get().getSignal(STAGNATION).strength()).isEqualTo(0.5);
    }

    @Test
    void prepareRuntimeContext_clearsLastAttemptIdOnSubgoalChange() throws IOException {
        // T1-BS: a stale lastAttemptId from a previous subgoal must not carry over into the new
        // subgoal, or isAcceptedNotMergedAttempt will find the old ACCEPTED+unmerged attempt in
        // Neo4j and permanently block evolution on the incoming subgoal.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService, buildFixClosureGate
        );

        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of());
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");

        GoalEntity root = new GoalEntity("g1", "root goal", "role", GoalType.GOAL, null);
        GoalEntity newSubgoal = new GoalEntity("sg-new", "new subgoal", "role", GoalType.SUBGOAL, "g1");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(newSubgoal);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        when(goalPlannerService.getGoalMetadataInt(newSubgoal, "cumulativeStagnatingCycles")).thenReturn(0);
        when(goalPlannerService.getCriteriaPassStatus(newSubgoal)).thenReturn(Map.of());
        when(goalPlannerService.getSubgoalSignals(newSubgoal)).thenReturn(Map.of());
        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        CycleContextData previous = CycleContextData.builder()
                .cycleName("prev")
                .currentSubGoalId("sg-old")
                .lastAttemptId("attempt-from-old-subgoal")
                .build();

        Optional<CycleContextData> outOpt = sut.prepareRuntimeContext(previous);

        assertThat(outOpt).isPresent();
        assertThat(outOpt.get().lastAttemptId()).isNull();
    }

    @Test
    void prepareRuntimeContext_preservesLastAttemptIdWhenSubgoalUnchanged() throws IOException {
        // T1-BS: lastAttemptId must be preserved when the subgoal has NOT changed.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService, buildFixClosureGate
        );

        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of());
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");

        GoalEntity root = new GoalEntity("g1", "root goal", "role", GoalType.GOAL, null);
        GoalEntity sameSubgoal = new GoalEntity("sg-same", "same subgoal", "role", GoalType.SUBGOAL, "g1");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(sameSubgoal);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        when(goalPlannerService.getGoalMetadataInt(sameSubgoal, "cumulativeStagnatingCycles")).thenReturn(0);
        when(goalPlannerService.getCriteriaPassStatus(sameSubgoal)).thenReturn(Map.of());
        when(goalPlannerService.getSubgoalSignals(sameSubgoal)).thenReturn(Map.of());
        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        CycleContextData previous = CycleContextData.builder()
                .cycleName("prev")
                .currentSubGoalId("sg-same")
                .lastAttemptId("attempt-123")
                .build();

        Optional<CycleContextData> outOpt = sut.prepareRuntimeContext(previous);

        assertThat(outOpt).isPresent();
        assertThat(outOpt.get().lastAttemptId()).isEqualTo("attempt-123");
    }

    @Test
    void prepareRuntimeContext_clearsLastAttemptIdWhenPreviousCyclePromotedSubgoal() throws IOException {
        // T1-CL: if the previous cycle set subgoalPromoted=true the coordinator has already advanced
        // currentSubGoalId to the next subgoal, so subgoalChanged() returns false (same ID) — but
        // the lastAttemptId still belongs to the completed subgoal and must be cleared.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService, buildFixClosureGate
        );

        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of());
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");

        GoalEntity root = new GoalEntity("g1", "root goal", "role", GoalType.GOAL, null);
        GoalEntity promotedSubgoal = new GoalEntity("sg-b", "promoted subgoal", "role", GoalType.SUBGOAL, "g1");
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(promotedSubgoal);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(goalPlannerService.getAbandonedGoals()).thenReturn(List.of());
        when(goalPlannerService.getGoalMetadataInt(promotedSubgoal, "cumulativeStagnatingCycles")).thenReturn(0);
        when(goalPlannerService.getCriteriaPassStatus(promotedSubgoal)).thenReturn(Map.of());
        when(goalPlannerService.getSubgoalSignals(promotedSubgoal)).thenReturn(Map.of());
        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        // previous already has currentSubGoalId = "sg-b" (promotion happened last cycle),
        // so subgoalChanged() returns false — only the subgoalPromoted flag triggers the clear.
        CycleContextData previous = CycleContextData.builder()
                .cycleName("prev")
                .currentSubGoalId("sg-b")
                .subgoalPromoted(true)
                .lastAttemptId("attempt-from-completed-subgoal")
                .build();

        Optional<CycleContextData> outOpt = sut.prepareRuntimeContext(previous);

        assertThat(outOpt).isPresent();
        assertThat(outOpt.get().lastAttemptId()).isNull();
    }

    @Test
    void prepareRuntimeContext_subgoalNullRootActive_autoCompletesAndEmitsGoalExhausted() throws IOException {
        // T1-DH: when the cycle starts with currentSubGoalId == null and root still ACTIVE, the
        // coordinator must call autoCompleteRootIfSatisfied BEFORE injecting ROLE_PLANNER. If
        // autoComplete promotes the root to COMPLETED, GOAL_EXHAUSTED should be emitted (so the
        // loop halts) and ROLE_PLANNER must NOT be raised.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService, buildFixClosureGate
        );

        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of());
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");

        GoalEntity root = new GoalEntity("g1", "root goal", "role", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        // Simulate autoCompleteRootIfSatisfied promoting root to COMPLETED.
        doAnswer(inv -> {
            root.setStatus(GoalStatus.COMPLETED);
            return null;
        }).when(goalPlannerService).autoCompleteRootIfSatisfied();

        Optional<CycleContextData> outOpt = sut.prepareRuntimeContext(null);

        assertThat(outOpt).isPresent();
        verify(goalPlannerService).autoCompleteRootIfSatisfied();
        assertThat(outOpt.get().hasSignal(GOAL_EXHAUSTED)).isTrue();
        assertThat(outOpt.get().hasSignal(ROLE_PLANNER)).isFalse();
    }

    @Test
    void prepareRuntimeContext_subgoalNullRootActiveAutoCompleteNoOp_fallsBackToReplanSignal() throws IOException {
        // T1-DH fallback: when autoCompleteRootIfSatisfied leaves root ACTIVE (e.g. not all
        // direct children are terminal), the coordinator must still emit ROLE_PLANNER to allow
        // the T1-AD/B re-plan attempt. This preserves the legacy recovery path for genuine
        // exhaustion gaps where the planner does need to inject more work.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService, buildFixClosureGate
        );

        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of());
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");

        GoalEntity root = new GoalEntity("g1", "root goal", "role", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        // autoComplete is a no-op (root has children that aren't all terminal).
        // Default Mockito behavior for void: do nothing.

        Optional<CycleContextData> outOpt = sut.prepareRuntimeContext(null);

        assertThat(outOpt).isPresent();
        verify(goalPlannerService).autoCompleteRootIfSatisfied();
        assertThat(outOpt.get().hasSignal(ROLE_PLANNER)).isTrue();
        assertThat(outOpt.get().hasSignal(GOAL_EXHAUSTED)).isFalse();
    }

    @Test
    void prepareRuntimeContext_initializesRecentEvolutionFingerprintsToEmptyListWhenNoPrevious() throws IOException {
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService, buildFixClosureGate
        );

        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of());
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");

        GoalEntity root = new GoalEntity("g1", "root goal", "role", GoalType.GOAL, null);
        when(goalPlannerService.getRootGoal()).thenReturn(root);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());

        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<CycleContextData> outOpt = sut.prepareRuntimeContext(null);

        assertThat(outOpt).isPresent();
        assertThat(outOpt.get().recentEvolutionFingerprints()).isNotNull();
        assertThat(outOpt.get().recentEvolutionFingerprints()).isEmpty();
    }
}
