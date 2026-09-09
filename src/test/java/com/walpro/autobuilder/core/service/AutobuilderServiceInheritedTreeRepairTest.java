package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;
import com.walpro.autobuilder.core.build.StructuralTreeRepair;
import com.walpro.autobuilder.core.init.StartMode;
import com.walpro.autobuilder.goal.GoalPlannerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-DR: the repairs the loop owns reach the tree a run is HANDED, not only the tree a merge wrote.
 *
 * <p><b>The failure this pins.</b> Both structural repairs were reachable only from a merge, which
 * is the right trigger for a defect a merge introduces and no trigger at all for one a run
 * inherits. Run {@code 2026-09-03_20-25-22} opened on a tree the previous run had left unstartable,
 * named it in its own goal text, rendered the diagnosis eight times, and ran 44 cycles over 35
 * minutes with ZERO merges — so the repair for that exact tree was never once entered.
 *
 * <p><b>What these tests do and do not cover.</b> They pin the mode gating and that the pass
 * really repairs a real tree through the real analyzer. They do NOT pin the ordering claim
 * ("before it spends a cycle on anything else") — that is structural, one line above
 * {@code runAutobuilderCycle()} in {@code init()}, and driving {@code init()} far enough to
 * observe it would need most of the start path stubbed. The ordering is demonstrated instead on
 * the frozen tree of the filing run, which is what the ticket's DoD asks for.
 */
class AutobuilderServiceInheritedTreeRepairTest {

    private static final String PLAIN_REPOSITORY = """
            package com.example.library.domain.repository;

            public class BookRepository {
                public void save(Object b) {}
            }
            """;

    private static final String ANNOTATED_PEER = """
            package com.example.library.domain.repository;

            import org.springframework.stereotype.Repository;

            @Repository
            public class MemberRepository {
                public void save(Object m) {}
            }
            """;

    private static final String SECOND_ANNOTATED_PEER = """
            package com.example.library.domain.repository;

            import org.springframework.stereotype.Repository;

            @Repository
            public class LoanRepository {
                public void save(Object l) {}
            }
            """;

    private static final String INJECTION_SITE = """
            package com.example.library.web;

            import com.example.library.domain.repository.BookRepository;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class BookController {
                private final BookRepository repository;
                public BookController(BookRepository repository) { this.repository = repository; }
            }
            """;

    private static final String REPOSITORY_PATH =
            "src/main/java/com/example/library/domain/repository/BookRepository.java";

    /** The service with only the collaborators this path touches; the constructor takes sixteen. */
    private AutobuilderService serviceWith(FileService fileService, StructuralTreeRepair repair) {
        AutobuilderService service = new AutobuilderService(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "fileService", fileService);
        service.setStructuralTreeRepair(repair);
        return service;
    }

    /** Writes the shape the filing run inherited: a plain type, two annotated peers, a caller. */
    private Path inheritedTree(Path root) throws IOException {
        Path output = root.resolve("output");
        write(output, REPOSITORY_PATH, PLAIN_REPOSITORY);
        write(output, "src/main/java/com/example/library/domain/repository/MemberRepository.java",
                ANNOTATED_PEER);
        write(output, "src/main/java/com/example/library/domain/repository/LoanRepository.java",
                SECOND_ANNOTATED_PEER);
        write(output, "src/main/java/com/example/library/web/BookController.java", INJECTION_SITE);
        return output;
    }

    private void write(Path output, String relative, String content) throws IOException {
        Path file = output.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("a run continuing a lineage repairs the tree it was handed, having merged nothing")
    void aContinuedLineageRepairsTheTreeAsFound(@TempDir Path root) throws Exception {
        Path output = inheritedTree(root);
        FileService fileService = new FileService(root.toString(), new com.fasterxml.jackson.databind.ObjectMapper());
        StructuralTreeRepair repair =
                new StructuralTreeRepair(fileService, mock(GoalPlannerService.class));
        repair.setWiringFactsAnalyzer(new WiringFactsAnalyzer());

        // No merge has happened and none ever will in this test -- which is the whole point.
        serviceWith(fileService, repair).repairInheritedTree(StartMode.CONTINUE_LINEAGE);

        assertThat(Files.readString(output.resolve(REPOSITORY_PATH)))
                .as("the inherited unregistered type is registered before the first cycle")
                .contains("@Repository")
                .contains("import org.springframework.stereotype.Repository;");
    }

    @Test
    @DisplayName("the same tree under a mode that does not inherit is left exactly as found")
    void theSameTreeIsUntouchedWhenTheModeDoesNotInherit(@TempDir Path root) throws Exception {
        // The companion to the test above, and what stops it being vacuous: identical fixture,
        // identical real analyzer, only the mode differs. If the assertion above passed because
        // the fixture already carried the annotation, this one would fail too.
        Path output = inheritedTree(root);
        FileService fileService = new FileService(root.toString(), new com.fasterxml.jackson.databind.ObjectMapper());
        StructuralTreeRepair repair =
                new StructuralTreeRepair(fileService, mock(GoalPlannerService.class));
        repair.setWiringFactsAnalyzer(new WiringFactsAnalyzer());

        serviceWith(fileService, repair).repairInheritedTree(StartMode.NEW_PROJECT);

        assertThat(Files.readString(output.resolve(REPOSITORY_PATH)))
                .as("nothing was inherited, so nothing was repaired")
                .isEqualTo(PLAIN_REPOSITORY);
    }

    @Test
    @DisplayName("a new project has no inherited tree, so the pass does not run")
    void aNewProjectIsNotRepaired() {
        StructuralTreeRepair repair = mock(StructuralTreeRepair.class);
        FileService fileService = mock(FileService.class);

        serviceWith(fileService, repair).repairInheritedTree(StartMode.NEW_PROJECT);

        verify(repair, never()).repairTree(anyString());
    }

    @Test
    @DisplayName("an adopted codebase is not rewritten before anyone has asked for work")
    void anAdoptedProjectIsNotRepaired() {
        StructuralTreeRepair repair = mock(StructuralTreeRepair.class);
        FileService fileService = mock(FileService.class);

        serviceWith(fileService, repair).repairInheritedTree(StartMode.ADOPT_PROJECT);

        // T4-CQ: an adopted tree has no goal yet. Editing someone else's source before they have
        // asked for anything is an uninvited change, not a repair; the cold read reports instead.
        verify(repair, never()).repairTree(anyString());
    }

    @Test
    @DisplayName("the resting mode touches nothing, repairs included")
    void theRestingModeIsNotRepaired() {
        StructuralTreeRepair repair = mock(StructuralTreeRepair.class);

        serviceWith(mock(FileService.class), repair).repairInheritedTree(StartMode.ASK);

        verify(repair, never()).repairTree(anyString());
    }

    @Test
    @DisplayName("a repair that cannot run does not stop the run from starting")
    void aFailingRepairDoesNotPreventTheRunFromStarting() throws Exception {
        StructuralTreeRepair repair = mock(StructuralTreeRepair.class);
        FileService fileService = mock(FileService.class);
        when(fileService.resolveSecurePath("output")).thenReturn(Path.of("/nowhere/output"));
        doThrow(new RuntimeException("tree unreadable")).when(repair).repairTree(anyString());

        AutobuilderService service = serviceWith(fileService, repair);

        assertThatCode(() -> service.repairInheritedTree(StartMode.CONTINUE_LINEAGE))
                .doesNotThrowAnyException();
        verify(repair, times(1)).repairTree(anyString());
    }

    @Test
    @DisplayName("with no repair collaborator the pass is a no-op rather than a failure")
    void anAbsentCollaboratorIsTolerated() throws Exception {
        AutobuilderService service = serviceWith(mock(FileService.class), null);

        assertThatCode(() -> service.repairInheritedTree(StartMode.CONTINUE_LINEAGE))
                .doesNotThrowAnyException();
    }
}
