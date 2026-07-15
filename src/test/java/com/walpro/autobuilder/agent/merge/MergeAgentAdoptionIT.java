package com.walpro.autobuilder.agent.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.build.BuildExecutor;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MergeAgentAdoptionIT {

    @TempDir
    Path tempDir;

    @Test
    void mergeCompletedBranch_promotesSingleNewFile_writesOutput_tracksPreAndPost_andMarksCycleMerged() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();

        // Real filesystem services
        FileService fileService = new FileService(tempDir.toString(), om);
        fileService.ensureDefaultFolders();

        MutationSnapshotService snapshotService = new MutationSnapshotService(fileService, om);
        FileVariantCollector collector = new FileVariantCollector(fileService, snapshotService, com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        CycleMergeTracker tracker = new CycleMergeTracker(fileService, snapshotService);

        // Mock merge processor and registrar: force adoption behavior deterministically
        MergeProcessor mergeProcessor = mock(MergeProcessor.class);
        MergeActionRegistrar mergeActionRegistrar = mock(MergeActionRegistrar.class);

        GoalPlannerService goalPlannerService = mock(GoalPlannerService.class);
        BuildExecutor buildExecutor =
                mock(BuildExecutor.class);
        com.walpro.autobuilder.core.build.BuildErrorClassifierAgent classifierAgent =
                mock(com.walpro.autobuilder.core.build.BuildErrorClassifierAgent.class);
        BlockerSubgoalInjector blockerSubgoalInjector = new BlockerSubgoalInjector(goalPlannerService, classifierAgent, fileService, com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        MergeAgent agent = new MergeAgent(fileService, collector, mergeProcessor, tracker, mergeActionRegistrar, goalPlannerService, buildExecutor, blockerSubgoalInjector, new MergeRollbackService(), snapshotService, mock(com.walpro.autobuilder.core.build.CompileProbeService.class), classifierAgent, new com.walpro.autobuilder.core.build.PackageFragmentationCheck(fileService), com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());

        // Prepare exactly one mutation cycle with exactly one variant file
        writeMutation(fileService, snapshotService, "cycle-a", "newfile.txt", "variant-a");

        // Root context for the "current merge cycle" includes merge trace
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-merge")
                .outputPath("output")
                .currentSubGoalId("goal-1")
                .build()
                .withMergeTrace(new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>()))
                .grantWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of("merge", 1.0, 0.0, "test"));

        // Ensure output doesn't exist, so MergeAgent passes fileExistsInOutput=false
        // (FileService.exists(rel) checks relative paths; output/newfile.txt does not exist here)
        assertThat(fileService.exists("output/newfile.txt")).isFalse();

        when(mergeProcessor.process(
                eq("newfile.txt"),
                eq(""),
                anyList(),
                eq(false),
                any(CycleContextData.class)
        )).thenAnswer(inv -> {
            List<FileVariant> variants = inv.getArgument(2);
            assertThat(variants).hasSize(1);
            assertThat(variants.get(0).originCycleId()).isEqualTo("cycle-a");

            return MergeResult.asAdopted("", "ADOPTED", Set.of("cycle-a"));
        });

        // Act
        CycleContextData out = agent.mergeCompletedBranch(ctx);

        // Assert output written
        Path outFile = fileService.resolveSecurePath("output/newfile.txt");
        assertThat(fileService.existsResolved(outFile)).isTrue();
        assertThat(fileService.readStringResolved(outFile)).isEqualTo("ADOPTED");

        // Assert merge trace tracked both pre and post for adopted file
        assertThat(out.mergeTrace().preMergeContent()).containsEntry("newfile.txt", "");
        assertThat(out.mergeTrace().postMergeContent()).containsEntry("newfile.txt", "ADOPTED");

        // Assert cycle-a marked merged in its meta.json
        CycleContextData metaA = snapshotService.readMutationMeta("mutations/cycle-a/meta.json");
        assertThat(metaA.merged()).isTrue();

        // markCyclesAsMerged always sets merged=true on the context to signal that this
        // cycle completed a merge, regardless of whether its own cycleName was a contributor.
        assertThat(out.merged()).isTrue();
        assertThat(out.hasWindow("merge")).isFalse();
    }

    private static void writeMutation(
            FileService fileService,
            MutationSnapshotService snapshotService,
            String cycleId,
            String relPath,
            String content
    ) throws Exception {
        Path file = fileService.resolveSecurePath("mutations/" + cycleId + "/" + relPath);
        fileService.writeFileResolved(file, content);

        CycleContextData meta = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath("output")
                .currentSubGoalId("goal-1")
                .currentSubGoalDescription("Adoption test")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .merged(false)
                .build();

        snapshotService.writeMutationMeta(meta);
    }
}