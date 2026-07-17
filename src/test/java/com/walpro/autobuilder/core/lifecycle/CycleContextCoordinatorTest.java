package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.structure.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.goal.AbandonedGoalQueryService;
import com.walpro.autobuilder.graph.evaluation.EvaluationGraphFacade;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.walpro.autobuilder.core.lifecycle.BasicSignals.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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

    @Test
    void prepareRuntimeContext_carriesRecentEvolutionFingerprintsFromPrevious() throws IOException {
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
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
    void prepareRuntimeContext_seedsEndReportOnFirstContext_carriesUntilMerge() throws IOException {
        // T4-CD slice 2: the parent's end report is seeded on the first context of a resumed run,
        // carried across non-merged cycles, and dropped after the run's first merge.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
        );
        var resumeService = org.mockito.Mockito.mock(com.walpro.autobuilder.core.init.RunResumeService.class);
        when(resumeService.priorEndReportPromptBlock()).thenReturn("--- PRIOR RUN OF THIS LINEAGE (advisory) ---\n...");
        sut.setRunResumeService(resumeService);

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

        // first context (previous == null): seeded from the holder
        CycleContextData first = sut.prepareRuntimeContext(null).orElseThrow();
        assertThat(first.priorRunEndReport()).startsWith("--- PRIOR RUN OF THIS LINEAGE");

        // carried while no merge happened
        CycleContextData second = sut.prepareRuntimeContext(first).orElseThrow();
        assertThat(second.priorRunEndReport()).isEqualTo(first.priorRunEndReport());

        // dropped after the first merged cycle
        CycleContextData third = sut.prepareRuntimeContext(second.withMerged(true)).orElseThrow();
        assertThat(third.priorRunEndReport()).isNull();
    }

    @Test
    void prepareRuntimeContext_freshStart_hasNoEndReport() throws IOException {
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
        );
        // no RunResumeService wired at all (pure-unit shape) — must behave as before
        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("output"));
        when(fileService.readFolderContentsResolved(Path.of("output"))).thenReturn(Map.of());
        when(projectStructureSummarizer.summarizeProjectStructure("output")).thenReturn("summary");
        when(goalPlannerService.getRootGoal()).thenReturn(new GoalEntity("g1", "root", "role", GoalType.GOAL, null));
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());
        when(goalPlannerService.getCompletedGoals()).thenReturn(List.of());
        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(sut.prepareRuntimeContext(null).orElseThrow().priorRunEndReport()).isNull();
    }

    @Test
    void prepareRuntimeContext_subgoalSwitchGivesCleanSignalSlate() throws IOException {
        // New subgoal entity has no persisted signals → subgoal-scoped signals from the previous
        // context must be absent; structural signals must pass through unchanged.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
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

        when(lineageTracker.emptyMergeTrace()).thenReturn(null);
        when(cycleStartSignalSeeder.seedIfEmpty(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineageTracker.enrichLineage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        CycleContextData previous = CycleContextData.builder()
                .cycleName("prev")
                .currentSubGoalId("sg-old")
                .build();
        previous = issue(previous, STAGNATION, 0.7, 0.2);
        previous = issue(previous, REPETITION_DETECTED, 0.6, 0.2);
        previous = issue(previous, DIVERGENCE_REQUEST, 0.5, 0.2);
        previous = issue(previous, LOW_CONFIDENCE, 0.4, 0.2);
        previous = issue(previous, SCORE_PLATEAU, 0.3, 0.2);
        previous = issue(previous, REPLAN_REQUESTED, 0.8, 0.1);

        Optional<CycleContextData> outOpt = sut.prepareRuntimeContext(previous);

        assertThat(outOpt).isPresent();
        CycleContextData out = outOpt.get();
        // Subgoal-scoped requests must be absent (entity returned nothing for new subgoal)
        assertThat(out.hasOpenRequest(STAGNATION)).isFalse();
        assertThat(out.hasOpenRequest(REPETITION_DETECTED)).isFalse();
        assertThat(out.hasOpenRequest(DIVERGENCE_REQUEST)).isFalse();
        assertThat(out.hasOpenRequest(LOW_CONFIDENCE)).isFalse();
        assertThat(out.hasOpenRequest(SCORE_PLATEAU)).isFalse();
        // Structural kind must be preserved (not in SUBGOAL_SCOPED_SIGNALS)
        assertThat(out.hasOpenRequest(REPLAN_REQUESTED)).isTrue();
    }

    @Test
    void prepareRuntimeContext_restoresSubgoalScopedSignalsFromEntity() throws IOException {
        // Entity has STAGNATION persisted → it is restored regardless of what the carried context had.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
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
        // Entity has an open STAGNATION request persisted from a previous cycle
        when(goalPlannerService.getSubgoalRequests(subgoal)).thenReturn(java.util.List.of(
                com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(STAGNATION, 0.5, "restored",
                        com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(0.5, 0.2))));

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
        // STAGNATION must be open, sourced from the entity
        assertThat(outOpt.get().hasOpenRequest(STAGNATION)).isTrue();
        assertThat(outOpt.get().requestUrgency(STAGNATION)).isEqualTo(0.5);
    }

    @Test
    void prepareRuntimeContext_clearsLastAttemptIdOnSubgoalChange() throws IOException {
        // T1-BS: a stale lastAttemptId from a previous subgoal must not carry over into the new
        // subgoal, or isAcceptedNotMergedAttempt will find the old ACCEPTED+unmerged attempt in
        // Neo4j and permanently block evolution on the incoming subgoal.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
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
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
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
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
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
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
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
        assertThat(DerivedFacts.goalExhausted(outOpt.get())).isTrue();
        assertThat(outOpt.get().hasWindow("planner")).isFalse();
    }

    @Test
    void prepareRuntimeContext_subgoalNullRootActiveAutoCompleteNoOp_fallsBackToReplanSignal() throws IOException {
        // T1-DH fallback: when autoCompleteRootIfSatisfied leaves root ACTIVE (e.g. not all
        // direct children are terminal), the coordinator must still emit ROLE_PLANNER to allow
        // the T1-AD/B re-plan attempt. This preserves the legacy recovery path for genuine
        // exhaustion gaps where the planner does need to inject more work.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
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
        assertThat(outOpt.get().hasWindowAtLeast("planner", 0.10)).isTrue();
        assertThat(DerivedFacts.goalExhausted(outOpt.get())).isFalse();
    }

    @Test
    void prepareRuntimeContext_initializesRecentEvolutionFingerprintsToEmptyListWhenNoPrevious() throws IOException {
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder, evaluationGraphFacade, abandonedGoalQueryService
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

    private static CycleContextData issue(CycleContextData ctx, SignalType kind, double urgency, double decay) {
        return ctx.issueRequest(com.walpro.autobuilder.core.lifecycle.ChannelRequest.of(
                kind, urgency, "test", com.walpro.autobuilder.core.lifecycle.ChannelRequest.horizonFromDecay(urgency, decay)));
    }
}