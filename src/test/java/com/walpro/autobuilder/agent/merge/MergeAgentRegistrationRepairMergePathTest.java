package com.walpro.autobuilder.agent.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T1-FX: the registration repair must fire in the SAME cycle as the merge that introduces the
 * defect — driven through the real merge path, on a real filesystem.
 *
 * <p>WHY THIS EXISTS RATHER THAN A FIELD RUN. The property is deterministic ("does the hook's
 * disk read see what this merge just wrote?"), but the defect only appears in the field when a
 * model happens to generate it — 4 runs in 16. A 60-minute run bought a NO-OPPORTUNITY on
 * 2026-09-01. The three field-only risks are separately retired: the analyzer is
 * {@code @Autowired(required=false)} but demonstrably non-null (T3-CA fired in run
 * 2026-08-31_19-50-35), and the {@code outputPath} to disk-read resolution is field-proven by the
 * sibling relocation hook, which makes the identical call and moved a class in run
 * 2026-09-01_01-47-47.
 *
 * <p>Fidelity that the mocked unit test could not have: a REAL {@link FileService} over a real
 * directory, the real {@code mergeCompletedBranch} entry point writing the files, and an ABSOLUTE
 * {@code outputPath} exactly as {@code CycleContextCoordinator} builds it in production.
 */
class MergeAgentRegistrationRepairMergePathTest {

    @TempDir
    Path tempDir;

    private static final String PLAIN_BOOK_REPOSITORY = """
            package com.example.library.domain.repository;

            public class BookRepository {
                public void save(Object b) {}
            }
            """;

    private static final String MEMBER_REPOSITORY = """
            package com.example.library.domain.repository;

            import org.springframework.stereotype.Repository;

            @Repository
            public class MemberRepository {
            }
            """;

    private static final String LOAN_REPOSITORY = """
            package com.example.library.domain.repository;

            import org.springframework.stereotype.Repository;

            @Repository
            public class LoanRepository {
            }
            """;

    private static final String CONTROLLER = """
            package com.example.library.web;

            import org.springframework.stereotype.Controller;
            import com.example.library.domain.repository.BookRepository;

            @Controller
            public class BookController {
                private final BookRepository books;
                public BookController(BookRepository books) { this.books = books; }
            }
            """;

    private static final String REPO_PATH =
            "src/main/java/com/example/library/domain/repository/BookRepository.java";

    @Test
    void theTypeThisMergeIntroducesIsRegisteredBeforeTheMergeReturns() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        FileService fileService = new FileService(tempDir.toString(), om);
        fileService.ensureDefaultFolders();

        MutationSnapshotService snapshotService = new MutationSnapshotService(fileService, om);
        FileVariantCollector collector = new FileVariantCollector(
                fileService, snapshotService,
                com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        CycleMergeTracker tracker = new CycleMergeTracker(fileService, snapshotService);

        MergeProcessor mergeProcessor = mock(MergeProcessor.class);
        MergeActionRegistrar registrar = mock(MergeActionRegistrar.class);
        GoalPlannerService goalPlannerService = mock(GoalPlannerService.class);
        com.walpro.autobuilder.core.build.BuildErrorClassifierAgent classifierAgent =
                mock(com.walpro.autobuilder.core.build.BuildErrorClassifierAgent.class);

        // The post-merge gate must report COMPILE_OK: that is the branch the repair hangs off,
        // and it is the honest state here — an unregistered injected type compiles perfectly.
        com.walpro.autobuilder.core.build.BuildExecutor buildExecutor =
                mock(com.walpro.autobuilder.core.build.BuildExecutor.class);
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), anyString())).thenReturn(
                new BuildOutcome(BuildResult.COMPILE_OK, "", 10L, "compile", "cycle-merge"));

        BlockerSubgoalInjector injector = new BlockerSubgoalInjector(
                goalPlannerService, classifierAgent, fileService,
                com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());

        MergeAgent agent = new MergeAgent(
                fileService, collector, mergeProcessor, tracker, registrar, goalPlannerService,
                buildExecutor, injector, new MergeRollbackService(), snapshotService,
                mock(com.walpro.autobuilder.core.build.CompileProbeService.class), classifierAgent,
                new com.walpro.autobuilder.core.build.PackageFragmentationCheck(fileService),
                com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        agent.setWiringFactsAnalyzer(new WiringFactsAnalyzer());

        // THIS merge is what creates the finding: the injection site and both annotated peers
        // arrive together with the plain type, exactly as they did at 18:51:20 in the run the
        // ticket was filed from.
        writeMutation(fileService, snapshotService, "cycle-a", REPO_PATH, PLAIN_BOOK_REPOSITORY);
        writeMutation(fileService, snapshotService, "cycle-a",
                "src/main/java/com/example/library/domain/repository/MemberRepository.java", MEMBER_REPOSITORY);
        writeMutation(fileService, snapshotService, "cycle-a",
                "src/main/java/com/example/library/domain/repository/LoanRepository.java", LOAN_REPOSITORY);
        writeMutation(fileService, snapshotService, "cycle-a",
                "src/main/java/com/example/library/web/BookController.java", CONTROLLER);

        when(mergeProcessor.process(anyString(), anyString(), anyList(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    List<FileVariant> variants = inv.getArgument(2);
                    FileVariant v = variants.get(0);
                    return MergeResult.asMerged("", v.content(), java.util.Set.of(v.originCycleId()));
                });

        // outputPath as production builds it: the ABSOLUTE resolved path, not "output".
        String absoluteOutput = fileService.resolveSecurePath("output").toString();

        // The cycle-start snapshot is EMPTY — the merge is what introduces everything. If the
        // hook still detected on previousOutput it would find nothing and the assertion below
        // would fail, which is precisely the pre-fix behaviour.
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("cycle-merge")
                .outputPath(absoluteOutput)
                .currentSubGoalId("goal-1")
                .previousOutput(Map.of())
                .build()
                .withMergeTrace(new MergeTrace(new ArrayList<>(), new HashMap<>(), new HashMap<>()))
                .grantWindow(com.walpro.autobuilder.core.lifecycle.ParticipationWindow.of(
                        "merge", 1.0, 0.0, "test"));

        agent.mergeCompletedBranch(ctx);

        Path repoFile = fileService.resolveSecurePath("output/" + REPO_PATH);
        String merged = fileService.readStringResolved(repoFile);

        assertThat(merged)
                .as("the type introduced by THIS merge carries its peers' registration before the merge returns")
                .contains("@Repository")
                .contains("import org.springframework.stereotype.Repository;");
        assertThat(merged.indexOf("@Repository\npublic class BookRepository"))
                .as("annotation sits on the declaration, not adrift above a blank line")
                .isGreaterThan(0);
    }

    private static void writeMutation(FileService fileService, MutationSnapshotService snapshotService,
                                      String cycleId, String relPath, String content) throws Exception {
        Path file = fileService.resolveSecurePath("mutations/" + cycleId + "/" + relPath);
        fileService.writeFileResolved(file, content);
        CycleContextData meta = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath("output")
                .currentSubGoalId("goal-1")
                .currentSubGoalDescription("Introduce the repository layer")
                .mutationState(MutationAcceptanceState.ACCEPTED)
                .merged(false)
                .build();
        snapshotService.writeMutationMeta(meta);
    }
}
