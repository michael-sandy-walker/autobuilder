package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;

/**
 * T3-BZ: the goal tree must not go on asserting where a type used to live.
 *
 * <p>The defect this pins was measured, not imagined: in run {@code 2026-08-31_15-02-34} the
 * placement mover relocated {@code Book} and the banked criterion naming its old path was still
 * being rendered afterwards. Across the five runs in which the mover was present, the three where
 * it ACTED scored 21, 6 and 15 of 23; ten of the eleven where it did not were perfect.
 */
class GoalPlannerServiceRelocationRewriteTest {

    private static final String OLD = "src/main/java/com/example/library/domain/Book.java";
    private static final String NEW = "src/main/java/com/example/library/domain/model/Book.java";

    private GoalPlannerService service;

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
        service = new GoalPlannerService(fileService, goalGraphService, config);
        service.setCurrentCycleContext(CycleContextData.builder().build());
        service.init();
    }

    @SuppressWarnings("unchecked")
    private List<String> criteriaOf(String goalId) {
        Object raw = service.getGoalById(goalId).getMetadata().get("acceptanceCriteria");
        return (List<String>) raw;
    }

    @Test
    void aBankedCriterionFollowsTheRelocatedFile() {
        service.resetInitialGoal("root", "Build a library API");
        service.addSubGoal("book", "Implement the Book domain model", false, "root");
        service.setAcceptanceCriteria("book", List.of(
                "A Java class `Book` exists at `" + OLD + "`.",
                "The class contains private fields for `id`, `title` and `isbn`."));

        int changed = service.rewriteRelocatedFileReferences(OLD, NEW);

        assertThat(changed).isEqualTo(1);
        assertThat(criteriaOf("book").get(0)).contains("domain/model/Book.java");
        assertThat(criteriaOf("book").get(0)).doesNotContain("domain/Book.java`");
        assertThat(criteriaOf("book").get(1)).isEqualTo(
                "The class contains private fields for `id`, `title` and `isbn`.");
    }

    @Test
    void aCompletedGoalsCriteriaAreRewrittenToo() {
        // The closure edge still reads criteria banked on COMPLETED goals, so a stale path there
        // is as harmful as one on the active goal.
        service.resetInitialGoal("root", "Build a library API");
        service.addSubGoal("done", "Implement the Book domain model", false, "root");
        service.setAcceptanceCriteria("done", List.of("A Java class `Book` exists at `" + OLD + "`."));
        service.markGoalCompleted("done");

        service.rewriteRelocatedFileReferences(OLD, NEW);

        assertThat(criteriaOf("done").get(0)).contains("domain/model/Book.java");
    }

    @Test
    void goalsThatNeverNamedTheFileAreUntouched() {
        service.resetInitialGoal("root", "Build a library API");
        service.addSubGoal("member", "Implement the Member domain model", false, "root");
        service.setAcceptanceCriteria("member", List.of(
                "A Java class `Member` exists at `src/main/java/com/example/library/domain/Member.java`."));

        int changed = service.rewriteRelocatedFileReferences(OLD, NEW);

        assertThat(changed).isZero();
        assertThat(criteriaOf("member").get(0)).contains("domain/Member.java");
    }

    @Test
    void aGoalDescriptionNamingTheTypeByFullyQualifiedNameIsRewritten() {
        service.resetInitialGoal("root", "Build a library API");
        service.addSubGoal("ctl", "Return com.example.library.domain.Book from the controller", false, "root");

        service.rewriteRelocatedFileReferences(OLD, NEW);

        assertThat(service.getGoalById("ctl").getDescription())
                .contains("com.example.library.domain.model.Book");
    }
}
