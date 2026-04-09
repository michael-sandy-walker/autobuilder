package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.structure.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalType;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CycleContextCoordinatorTest {

    @Mock private FileService fileService;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private DefaultProjectStructureSummarizer projectStructureSummarizer;
    @Mock private ContextLineageTracker lineageTracker;
    @Mock private CycleStartSignalSeeder cycleStartSignalSeeder;

    @Test
    void prepareRuntimeContext_carriesRecentEvolutionFingerprintsFromPrevious() throws IOException {
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder
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
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder
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
    void prepareRuntimeContext_restoresSubgoalScopedSignalsFromEntity() throws IOException {
        // Entity has STAGNATION persisted → it is restored regardless of what the carried context had.
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder
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
    void prepareRuntimeContext_initializesRecentEvolutionFingerprintsToEmptyListWhenNoPrevious() throws IOException {
        CycleContextCoordinator sut = new CycleContextCoordinator(
                fileService, goalPlannerService, projectStructureSummarizer, lineageTracker, cycleStartSignalSeeder
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
