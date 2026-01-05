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
