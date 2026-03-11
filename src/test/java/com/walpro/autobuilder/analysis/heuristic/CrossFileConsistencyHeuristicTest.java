package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrossFileConsistencyHeuristicTest {

    private final CrossFileConsistencyHeuristic heuristic = new CrossFileConsistencyHeuristic();

    private static final EvaluationOutcome ACCEPTED =
            new EvaluationOutcome(0.80, MutationAcceptanceState.ACCEPTED,
                    GoalProgressState.PROGRESSING, "", null);

    // ── No diff / null context ────────────────────────────────────────────────

    @Test
    void nullContext_returnsNone() {
        assertThat(heuristic.evaluate(null, ACCEPTED).level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void noDiff_returnsNone() {
        CycleContextData ctx = CycleContextData.builder().build();
        assertThat(heuristic.evaluate(ctx, ACCEPTED).level()).isEqualTo(ConfidenceLevel.NONE);
    }

    // ── Missing method detected ───────────────────────────────────────────────

    @Test
    void missingMethod_onKnownType_reportsStagnating() {
        // Task class has no setId() method
        String taskCode = """
                package com.example;
                public class Task {
                    private Long id;
                    public Long getId() { return id; }
                    public String getTitle() { return null; }
                }
                """;

        // TaskRepository calls task.setId() which doesn't exist in Task
        String repoCode = """
                package com.example;
                public class TaskRepository {
                    public Task save(Task task) {
                        task.setId(1L);
                        return task;
                    }
                }
                """;

        CycleContextData ctx = CycleContextData.builder()
                .previousOutput(Map.of("src/Task.java", taskCode))
                .projectDiff(new ProjectDiffResult(null,
                        Map.of("src/TaskRepository.java", repoCode), Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, ACCEPTED);

        assertThat(opinion.level()).isNotEqualTo(ConfidenceLevel.NONE);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        // Mutation acceptance is NOT_EVALUATED (abstain — code-first philosophy)
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.NOT_EVALUATED);
        assertThat(opinion.rationale()).contains("setId");
    }

    // ── All methods present — no violation ───────────────────────────────────

    @Test
    void allMethodsPresent_returnsNone() {
        String taskCode = """
                package com.example;
                public class Task {
                    private Long id;
                    public void setId(Long id) { this.id = id; }
                    public Long getId() { return id; }
                }
                """;

        String repoCode = """
                package com.example;
                public class TaskRepository {
                    public Task save(Task task) {
                        task.setId(1L);
                        return task;
                    }
                }
                """;

        CycleContextData ctx = CycleContextData.builder()
                .previousOutput(Map.of("src/Task.java", taskCode))
                .projectDiff(new ProjectDiffResult(null,
                        Map.of("src/TaskRepository.java", repoCode), Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, ACCEPTED);

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    // ── Unknown type — no false positive ─────────────────────────────────────

    @Test
    void unknownType_noFalsePositive() {
        // 'repo' does not resolve to any known type in the registry → no flag
        String repoCode = """
                package com.example;
                public class TaskService {
                    public void doSomething(Object repo) {
                        repo.someMethod();
                    }
                }
                """;

        CycleContextData ctx = CycleContextData.builder()
                .projectDiff(new ProjectDiffResult(null,
                        Map.of("src/TaskService.java", repoCode), Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, ACCEPTED);

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    // ── Test files are skipped ────────────────────────────────────────────────

    @Test
    void testFiles_areSkipped() {
        String taskCode = """
                package com.example;
                public class Task {
                    public Long getId() { return null; }
                }
                """;

        // Test file calls task.setId() which doesn't exist — but test files are excluded
        String testCode = """
                package com.example;
                public class TaskTest {
                    public void test() {
                        Task task = new Task();
                        task.setId(1L); // mock framework would handle this
                    }
                }
                """;

        CycleContextData ctx = CycleContextData.builder()
                .previousOutput(Map.of("src/main/java/Task.java", taskCode))
                .projectDiff(new ProjectDiffResult(null,
                        Map.of("src/test/java/TaskTest.java", testCode), Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, ACCEPTED);

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }
}
