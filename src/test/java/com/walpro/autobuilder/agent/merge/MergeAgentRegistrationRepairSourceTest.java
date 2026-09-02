package com.walpro.autobuilder.agent.merge;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;
import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.service.merge.CycleMergeTracker;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.core.service.merge.MergeProcessor;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.merge.MergeActionRegistrar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * T3-CA: which tree the registration repair reads.
 *
 * <p>The repair's own defect class is a type INTRODUCED by a merge — that is the only way a
 * project acquires an unregistered injected type. The cycle-start snapshot cannot contain it,
 * because the merge that created it lands after the snapshot is taken. Detecting on the snapshot
 * therefore defers every repair by a cycle and skips it entirely when the merge that introduced
 * the defect is the run's last, which is the stall shape the ticket was filed on
 * ({@code 2026-08-30_18-44-37}, boot-failed with the diagnosis in hand).
 *
 * <p>Field evidence for the lag: run {@code 2026-08-31_19-50-35} announced the fact at 20:25:40
 * and repaired it at 20:26:28 — the next cycle, not the one that introduced it.
 *
 * <p>The sibling relocation hook two lines further on already reads from disk for exactly this
 * reason, and its javadoc names this repair as something that changes files the snapshot lacks.
 */
@ExtendWith(MockitoExtension.class)
class MergeAgentRegistrationRepairSourceTest {

    @Mock private FileService fileService;
    @Mock private FileVariantCollector fileVariantCollector;
    @Mock private MergeProcessor mergeProcessor;
    @Mock private CycleMergeTracker cycleMergeTracker;
    @Mock private MergeActionRegistrar mergeActionRegistrar;
    @Mock private GoalPlannerService goalPlannerService;
    @Mock private com.walpro.autobuilder.core.build.BuildExecutor buildExecutor;
    @Mock private com.walpro.autobuilder.core.persistence.MutationSnapshotService mutationSnapshotService;
    @Mock private com.walpro.autobuilder.core.build.CompileProbeService compileProbeService;
    @Mock private com.walpro.autobuilder.core.build.BuildErrorClassifierAgent buildErrorClassifierAgent;
    @Mock private com.walpro.autobuilder.core.build.PackageFragmentationCheck packageFragmentationCheck;

    private MergeAgent agent;

    private static final String PLAIN_REPOSITORY = """
            package com.example.library.domain.repository;

            public class BookRepository {
                public void save(Object b) {}
            }
            """;

    private static final String ANNOTATED_REPOSITORY = """
            package com.example.library.domain.repository;

            import org.springframework.stereotype.Repository;

            @Repository
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

    private static final String REPOSITORY_PATH =
            "src/main/java/com/example/library/domain/repository/BookRepository.java";

    @BeforeEach
    void setUp() {
        BlockerSubgoalInjector injector = new BlockerSubgoalInjector(
                goalPlannerService, buildErrorClassifierAgent, fileService,
                com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        agent = new MergeAgent(
                fileService, fileVariantCollector, mergeProcessor, cycleMergeTracker,
                mergeActionRegistrar, goalPlannerService, buildExecutor, injector,
                new MergeRollbackService(), mutationSnapshotService, compileProbeService,
                buildErrorClassifierAgent, packageFragmentationCheck,
                com.walpro.autobuilder.core.build.TestBuildToolRegistry.classifier());
        agent.setWiringFactsAnalyzer(new WiringFactsAnalyzer());
    }

    /** The peers and the injection site, without the type whose registration is in question. */
    private static Map<String, String> peersOnly() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("src/main/java/com/example/library/domain/repository/MemberRepository.java", MEMBER_REPOSITORY);
        m.put("src/main/java/com/example/library/domain/repository/LoanRepository.java", LOAN_REPOSITORY);
        m.put("src/main/java/com/example/library/web/BookController.java", CONTROLLER);
        return m;
    }

    private static void writeTree(Path root, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = root.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
    }

    /**
     * Lenient because it is the point under test: before the fix the hook never calls this at all,
     * and a strict stub would fail the two no-change cases for the wrong reason.
     */
    private void stubDiskRead(Path root, Map<String, String> onDisk) throws IOException {
        org.mockito.Mockito.lenient()
                .when(fileService.readFolderContentsResolved(any(Path.class))).thenReturn(onDisk);
    }

    @Test
    void aTypeIntroducedByThisCycleIsRepairedInThisCycle(@TempDir Path root) throws Exception {
        Map<String, String> onDisk = peersOnly();
        onDisk.put(REPOSITORY_PATH, PLAIN_REPOSITORY);
        writeTree(root, onDisk);
        stubDiskRead(root, onDisk);

        // The cycle-start snapshot holds only the peers — the merge is what introduced the
        // unregistered type, so the snapshot cannot name it and the repair must not depend on one.
        agent.applyInjectedTypeRegistrationRepair(root.toString());

        assertThat(Files.readString(root.resolve(REPOSITORY_PATH)))
                .contains("@Repository")
                .contains("import org.springframework.stereotype.Repository;");
    }

    @Test
    void anUnreadableTreeRepairsNothingRatherThanFailingTheMerge(@TempDir Path root) throws Exception {
        Map<String, String> onDisk = peersOnly();
        onDisk.put(REPOSITORY_PATH, PLAIN_REPOSITORY);
        writeTree(root, onDisk);
        org.mockito.Mockito.lenient().when(fileService.readFolderContentsResolved(any(Path.class)))
                .thenThrow(new IOException("tree unreadable"));

        agent.applyInjectedTypeRegistrationRepair(root.toString());

        assertThat(Files.readString(root.resolve(REPOSITORY_PATH))).isEqualTo(PLAIN_REPOSITORY);
    }

    @Test
    void aTreeThatRegistersEveryInjectedTypeIsUntouched(@TempDir Path root) throws Exception {
        Map<String, String> onDisk = peersOnly();
        onDisk.put(REPOSITORY_PATH, ANNOTATED_REPOSITORY);
        writeTree(root, onDisk);
        stubDiskRead(root, onDisk);

        agent.applyInjectedTypeRegistrationRepair(root.toString());

        assertThat(Files.readString(root.resolve(REPOSITORY_PATH))).isEqualTo(ANNOTATED_REPOSITORY);
    }
}
