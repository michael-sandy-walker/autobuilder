package com.walpro.autobuilder.agent.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.service.merge.CycleMergeTracker;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.core.service.merge.MergeProcessor;
import com.walpro.autobuilder.core.service.merge.MergeResult;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.graph.merge.MergeActionRegistrar;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.model.MergeTrace;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The merge walks the mutation directories, which hold only what was written, so a delete
 * directive had no variant to merge and was never applied (found 2026-09-14: the model removed
 * one of two parallel stores, the loop compiled, judged and would have merged the tree with both).
 * The delete list lives in the cycle's {@code meta.json} and in the live context; the merge must
 * honour it, and the build gate's rollback must be able to put the file back.
 */
class MergeAgentDeletionMergePathTest {

    @TempDir Path tempDir;

    private static final String STORE_PATH = "src/main/java/com/example/library/domain/store/BookStore.java";
    private static final String REPO_PATH = "src/main/java/com/example/library/domain/repository/BookRepository.java";

    @Test
    @DisplayName("a merged mutation's delete directive removes the file, and the gate's rollback restores it")
    void theDeleteDirectiveIsAppliedAtMerge() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        FileService fileService = new FileService(tempDir.toString(), om);
        fileService.ensureDefaultFolders();
        MutationSnapshotService snapshotService = new MutationSnapshotService(fileService, om);
        FileVariantCollector collector = new FileVariantCollector(
                fileService, snapshotService, com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        CycleMergeTracker tracker = new CycleMergeTracker(fileService, snapshotService);
        MergeProcessor mergeProcessor = mock(MergeProcessor.class);
        MergeActionRegistrar registrar = mock(MergeActionRegistrar.class);
        GoalPlannerService goalPlannerService = mock(GoalPlannerService.class);
        var classifierAgent = mock(com.walpro.autobuilder.core.build.BuildErrorClassifierAgent.class);
        var buildExecutor = mock(com.walpro.autobuilder.core.build.BuildExecutor.class);
        when(buildExecutor.isEnabled()).thenReturn(true);
        List<Boolean> repoPresentDuringGate = new ArrayList<>();
        when(buildExecutor.execute(any(), anyString())).thenAnswer(inv -> {
            repoPresentDuringGate.add(fileService.exists("output/" + REPO_PATH));
            return new BuildOutcome(BuildResult.COMPILE_OK, "", 10L, "compile", "cycle-merge");
        });
        BlockerSubgoalInjector injector = new BlockerSubgoalInjector(
                goalPlannerService, classifierAgent, fileService,
                com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        MergeAgent agent = new MergeAgent(
                fileService, collector, mergeProcessor, tracker, registrar, goalPlannerService,
                buildExecutor, injector, new MergeRollbackService(), snapshotService,
                mock(com.walpro.autobuilder.core.build.CompileProbeService.class), classifierAgent,
                new com.walpro.autobuilder.core.build.PackageFragmentationCheck(fileService),
                com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());

        // The tree before the merge holds the repository the mutation asks to delete.
        Path repo = fileService.resolveSecurePath("output/" + REPO_PATH);
        fileService.writeFileResolved(repo, "class BookRepository {}");
        // The accepted mutation: one written file, one deletion, recorded in its meta.json.
        Path storeFile = fileService.resolveSecurePath("mutations/cycle-a/" + STORE_PATH);
        fileService.writeFileResolved(storeFile, "class BookStore {}");
        snapshotService.writeMutationMeta(CycleContextData.builder()
                .cycleName("cycle-a").outputPath("output").currentSubGoalId("goal-1")
                .currentSubGoalDescription("Unify the store")
                .mutationState(MutationAcceptanceState.ACCEPTED).merged(false)
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation(STORE_PATH, "class BookStore {}")), List.of(REPO_PATH), "llm", ""))
                .build());
        when(mergeProcessor.process(anyString(), anyString(), anyList(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    List<FileVariant> variants = inv.getArgument(2);
                    FileVariant v = variants.get(0);
                    return MergeResult.asMerged("", v.content(), java.util.Set.of(v.originCycleId()));
                });
        String absoluteOutput = fileService.resolveSecurePath("output").toString();
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-merge").outputPath(absoluteOutput).currentSubGoalId("goal-1")
                .previousOutput(Map.of(REPO_PATH, "class BookRepository {}"))
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation(STORE_PATH, "class BookStore {}")), List.of(REPO_PATH), "llm", ""))
                .build()
                .withMergeTrace(new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>()))
                .grantWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of("merge", 1.0, 0.0, "test"));

        CycleContextData after = agent.mergeCompletedBranch(ctx);

        assertThat(fileService.exists("output/" + STORE_PATH)).isTrue();
        assertThat(fileService.exists("output/" + REPO_PATH))
                .as("the file the mutation asked to delete is gone from the merged tree")
                .isFalse();
        assertThat(repoPresentDuringGate).as("the post-merge build gate compiled the tree without it").containsExactly(false);
        assertThat(after.merged()).isTrue();
    }

    @Test
    @DisplayName("when the gate fails, the deleted file comes back with everything else")
    void aFailedGateRestoresTheDeletedFile() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        FileService fileService = new FileService(tempDir.toString(), om);
        fileService.ensureDefaultFolders();
        MutationSnapshotService snapshotService = new MutationSnapshotService(fileService, om);
        FileVariantCollector collector = new FileVariantCollector(
                fileService, snapshotService, com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        CycleMergeTracker tracker = new CycleMergeTracker(fileService, snapshotService);
        MergeProcessor mergeProcessor = mock(MergeProcessor.class);
        GoalPlannerService goalPlannerService = mock(GoalPlannerService.class);
        var classifierAgent = mock(com.walpro.autobuilder.core.build.BuildErrorClassifierAgent.class);
        // The rollback path classifies the failure for the next prompt; the pattern matcher is enough here.
        when(classifierAgent.classify(anyString()))
                .thenAnswer(inv -> com.walpro.autobuilder.core.build.BuildErrorClassifier.classify(inv.getArgument(0)));
        var buildExecutor = mock(com.walpro.autobuilder.core.build.BuildExecutor.class);
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), anyString())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_ERROR, "cannot find symbol BookRepository", 10L, "compile", "cycle-merge"));
        BlockerSubgoalInjector injector = new BlockerSubgoalInjector(
                goalPlannerService, classifierAgent, fileService,
                com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        MergeAgent agent = new MergeAgent(
                fileService, collector, mergeProcessor, tracker, mock(MergeActionRegistrar.class), goalPlannerService,
                buildExecutor, injector, new MergeRollbackService(), snapshotService,
                mock(com.walpro.autobuilder.core.build.CompileProbeService.class), classifierAgent,
                new com.walpro.autobuilder.core.build.PackageFragmentationCheck(fileService),
                com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        fileService.writeFileResolved(fileService.resolveSecurePath("output/" + REPO_PATH), "class BookRepository {}");
        fileService.writeFileResolved(fileService.resolveSecurePath("mutations/cycle-a/" + STORE_PATH), "class BookStore {}");
        snapshotService.writeMutationMeta(CycleContextData.builder()
                .cycleName("cycle-a").outputPath("output").currentSubGoalId("goal-1")
                .currentSubGoalDescription("Unify the store")
                .mutationState(MutationAcceptanceState.ACCEPTED).merged(false)
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation(STORE_PATH, "class BookStore {}")), List.of(REPO_PATH), "llm", ""))
                .build());
        when(mergeProcessor.process(anyString(), anyString(), anyList(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    List<FileVariant> variants = inv.getArgument(2);
                    FileVariant v = variants.get(0);
                    return MergeResult.asMerged("", v.content(), java.util.Set.of(v.originCycleId()));
                });
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-merge").outputPath(fileService.resolveSecurePath("output").toString())
                .currentSubGoalId("goal-1").previousOutput(Map.of(REPO_PATH, "class BookRepository {}"))
                .buildOutcome(new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "compile", "before"))
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation(STORE_PATH, "class BookStore {}")), List.of(REPO_PATH), "llm", ""))
                .build()
                .withMergeTrace(new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>()))
                .grantWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of("merge", 1.0, 0.0, "test"));

        CycleContextData after = agent.mergeCompletedBranch(ctx);

        assertThat(after.rolledBackThisCycle()).isTrue();
        assertThat(fileService.exists("output/" + REPO_PATH)).as("rolled back: the deleted file is restored").isTrue();
        assertThat(fileService.exists("output/" + STORE_PATH)).as("rolled back: the added file is removed").isFalse();
    }
}
