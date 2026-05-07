package com.walpro.autobuilder.agent.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.service.merge.CycleMergeTracker;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.core.service.merge.MergeProcessor;
import com.walpro.autobuilder.core.service.merge.MergeResult;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.merge.MergeActionRegistrar;
import com.walpro.autobuilder.model.MergeTrace;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MergeAgentIT {

    @TempDir
    Path tempDir;

    @Test
    void mergeCompletedBranch_writesMergedOutput_andMarksCyclesMerged_andConsumesMergeSignal() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();

        // Real filesystem services
        FileService fileService = new FileService(tempDir.toString(), om);
        fileService.ensureDefaultFolders();

        MutationSnapshotService snapshotService = new MutationSnapshotService(fileService, om);
        FileVariantCollector collector = new FileVariantCollector(fileService, snapshotService);
        CycleMergeTracker tracker = new CycleMergeTracker(fileService, snapshotService);

        // Mock merge processor and registrar (keeps IT deterministic)
        MergeProcessor mergeProcessor = mock(MergeProcessor.class);
        MergeActionRegistrar mergeActionRegistrar = mock(MergeActionRegistrar.class);

        GoalPlannerService goalPlannerService = mock(GoalPlannerService.class);
        com.walpro.autobuilder.core.build.BuildExecutor buildExecutor =
                mock(com.walpro.autobuilder.core.build.BuildExecutor.class);
        BlockerSubgoalInjector blockerSubgoalInjector = new BlockerSubgoalInjector(goalPlannerService);
        MergeAgent agent = new MergeAgent(fileService, collector, mergeProcessor, tracker, mergeActionRegistrar, goalPlannerService, buildExecutor, blockerSubgoalInjector, new MergeRollbackService());

        // Prepare mutation cycles that contain the same file variant
        // cycle-a and cycle-b both have a.txt, both have meta.json with valid subgoal
        writeMutation(fileService, snapshotService, "cycle-a", "a.txt", "variant-a");
        writeMutation(fileService, snapshotService, "cycle-b", "a.txt", "variant-b");

        // Root context for the "current merge cycle"
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-merge")
                .outputPath("output")
                .currentSubGoalId("goal-1")
                .build()
                .withMergeTrace(new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>()))
                .withSignal(com.walpro.autobuilder.core.lifecycle.Signal.of(BasicSignals.ROLE_MERGE, 1.0, 0.0, "test"));

        when(mergeProcessor.process(
                eq("a.txt"),
                eq(""),
                anyList(),
                eq(false),
                any(CycleContextData.class)
        )).thenAnswer(inv -> {
            List<FileVariant> variants = inv.getArgument(2);
            // sanity: should see both cycles
            assertThat(variants).extracting(FileVariant::originCycleId)
                    .containsExactlyInAnyOrder("cycle-a", "cycle-b");
            return MergeResult.asMerged("", "MERGED", java.util.Set.of("cycle-a", "cycle-b"));
        });

        // Act
        CycleContextData out = agent.mergeCompletedBranch(ctx);

        // Assert output written
        Path outFile = fileService.resolveSecurePath("output/a.txt");
        assertThat(fileService.existsResolved(outFile)).isTrue();
        assertThat(fileService.readStringResolved(outFile)).isEqualTo("MERGED");

        // Assert cycles marked as merged on disk
        CycleContextData metaA = snapshotService.readMutationMeta("mutations/cycle-a/meta.json");
        CycleContextData metaB = snapshotService.readMutationMeta("mutations/cycle-b/meta.json");
        assertThat(metaA.merged()).isTrue();
        assertThat(metaB.merged()).isTrue();

        // markCyclesAsMerged always sets merged=true on the context to signal that this
        // cycle completed a merge, regardless of whether its own cycleName was a contributor.
        assertThat(out.merged()).isTrue();
        assertThat(out.signals()).doesNotContainKey(BasicSignals.ROLE_MERGE);

        // Assert merge trace got post content
        assertThat(out.mergeTrace().postMergeContent()).containsEntry("a.txt", "MERGED");
    }

    private static void writeMutation(
            FileService fileService,
            MutationSnapshotService snapshotService,
            String cycleId,
            String relPath,
            String content
    ) throws Exception {
        // Write variant file
        Path file = fileService.resolveSecurePath("mutations/" + cycleId + "/" + relPath);
        fileService.writeFileResolved(file, content);

        // Write meta.json with a valid subgoal (so collector considers it)
        CycleContextData meta = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath("output")
                .currentSubGoalId("goal-1")
                .currentSubGoalDescription("Merge test")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .merged(false)
                .build();

        snapshotService.writeMutationMeta(meta);
    }
}