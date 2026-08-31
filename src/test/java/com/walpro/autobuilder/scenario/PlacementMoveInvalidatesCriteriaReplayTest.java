package com.walpro.autobuilder.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.build.PeerPlacementRelocation;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;

/**
 * Replays the defect the A/B of 2026-08-31 found: the placement mover repairs the SOURCE TREE and
 * leaves the loop's own bookkeeping asserting where the type used to be.
 *
 * <p>Measured, not supposed. Five runs carried the mover; the three in which it ACTED scored 21, 6
 * and 15 of 23, while ten of the eleven in which it did not were perfect (Fisher one-sided
 * p = 0.011). In run {@code 2026-08-31_15-02-34} the criterion quoted below was still being
 * rendered after the move that falsified it.
 *
 * <p>The nine-tree replay that shipped the mover could never have caught this: it asserts
 * properties of the TREE, and the damaged state lives in the goal tree.
 */
class PlacementMoveInvalidatesCriteriaReplayTest {

    private static final String ROOT = "src/main/java/com/example/library/";

    private GoalPlannerService goals;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        FileService fileService = mock(FileService.class);
        GoalGraphService goalGraphService = mock(GoalGraphService.class);
        GoalPlannerConfigProperties config = mock(GoalPlannerConfigProperties.class);
        when(config.isSyncFromGraph()).thenReturn(false);
        when(config.isMigrateToGraph()).thenReturn(false);
        lenient().doNothing().when(goalGraphService).save(any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        Path goalFile = tempDir.resolve("GoalStack.json");
        when(fileService.resolveSecurePath("meta/GoalStack.json")).thenReturn(goalFile);
        when(fileService.existsResolved(goalFile)).thenReturn(false);
        goals = new GoalPlannerService(fileService, goalGraphService, config);
        goals.setCurrentCycleContext(CycleContextData.builder().build());
        goals.init();
    }

    /** The anomalous tree: Book apart from the peers that name it. */
    private static Map<String, String> tree() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put(ROOT + "domain/Application.java",
                "package com.example.library.domain;\n\npublic class Application {\n"
                        + "    public static void main(String[] args) {}\n}\n");
        s.put(ROOT + "domain/Book.java", "package com.example.library.domain;\n\npublic class Book {}\n");
        s.put(ROOT + "domain/model/Loan.java",
                "package com.example.library.domain.model;\n\npublic class Loan {}\n");
        s.put(ROOT + "domain/model/Member.java",
                "package com.example.library.domain.model;\n\npublic class Member {}\n");
        s.put(ROOT + "domain/repository/BookRepository.java",
                "package com.example.library.domain.repository;\n\nimport com.example.library.domain.Book;\n\n"
                        + "public class BookRepository { Book b; }\n");
        s.put(ROOT + "domain/repository/LoanRepository.java",
                "package com.example.library.domain.repository;\n\nimport com.example.library.domain.model.Loan;\n\n"
                        + "public class LoanRepository { Loan l; }\n");
        s.put(ROOT + "domain/repository/MemberRepository.java",
                "package com.example.library.domain.repository;\n\nimport com.example.library.domain.model.Member;\n\n"
                        + "public class MemberRepository { Member m; }\n");
        return s;
    }

    @SuppressWarnings("unchecked")
    private List<String> criteriaOf(String goalId) {
        return (List<String>) goals.getGoalById(goalId).getMetadata().get("acceptanceCriteria");
    }

    @Test
    void afterTheMoveNoGoalStillAssertsTheVacatedPath() {
        // The goal tree as the planner banked it, with the run's own criterion text.
        goals.resetInitialGoal("root", "Build a library book-lending REST API");
        goals.addSubGoal("book", "Implement the Book domain model with id, title and isbn fields "
                + "in the `com.example.library.domain` package.", false, "root");
        goals.setAcceptanceCriteria("book", List.of(
                "A Java class `Book` exists at `" + ROOT + "domain/Book.java`.",
                "The class contains private fields for `id` (Long or UUID), `title` (String), and `isbn` (String)."));

        Map<String, String> sources = tree();
        var analyzer = new WiringFactsAnalyzer();
        var plan = PeerPlacementRelocation.plan(sources, analyzer.peerPlacementFindings(sources));
        assertThat(plan).hasSize(1);
        var relocation = plan.get(0);

        goals.rewriteRelocatedFileReferences(relocation.fromPath(), relocation.toPath());

        assertThat(criteriaOf("book"))
                .describedAs("no banked criterion may assert the path the mover vacated")
                .noneMatch(c -> c.contains("domain/Book.java"));
        assertThat(criteriaOf("book").get(0)).contains("domain/model/Book.java");
        // The criterion that says nothing about location is left exactly as the planner wrote it.
        assertThat(criteriaOf("book").get(1)).contains("private fields for `id` (Long or UUID)");
    }

    @Test
    void aRunWhereTheMoverDoesNotActLeavesTheGoalTreeAlone() {
        // The control case: an internally consistent tree yields no finding, so nothing is
        // rewritten. Ten of the eleven field runs without a move scored full marks.
        goals.resetInitialGoal("root", "Build a library book-lending REST API");
        goals.addSubGoal("book", "Implement the Book domain model", false, "root");
        goals.setAcceptanceCriteria("book", List.of(
                "A Java class `Book` exists at `" + ROOT + "domain/model/Book.java`."));

        Map<String, String> consistent = tree();
        consistent.remove(ROOT + "domain/Book.java");
        consistent.put(ROOT + "domain/model/Book.java",
                "package com.example.library.domain.model;\n\npublic class Book {}\n");
        var analyzer = new WiringFactsAnalyzer();

        assertThat(analyzer.peerPlacementFindings(consistent)).isEmpty();
        assertThat(criteriaOf("book").get(0)).contains("domain/model/Book.java");
    }
}
