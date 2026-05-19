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

/**
 * T2-DI regression tests. The 20:35:35 Book scenario: BookController and BookService each declare
 * a static {@code List<Book>} field. Both are used, so file-level structural checks pass; the
 * dataflow only fails at runtime when writes to controller.books are read from service.books.
 */
class StoreCoherenceHeuristicTest {

    private final StoreCoherenceHeuristic heuristic = new StoreCoherenceHeuristic();

    private static final String BOOK_ENTITY = """
            package com.example.library.model;
            public class Book {
                private Long id;
                private String title;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getTitle() { return title; }
                public void setTitle(String t) { this.title = t; }
            }
            """;

    private static final String CONTROLLER_WITH_STORE = """
            package com.example.library.controller;
            import com.example.library.model.Book;
            import java.util.*;
            public class BookController {
                private static final List<Book> books = new ArrayList<>();
                public void add(Book b) { books.add(b); }
            }
            """;

    private static final String SERVICE_WITH_STORE = """
            package com.example.library.service;
            import com.example.library.model.Book;
            import java.util.*;
            public class BookService {
                private static final List<Book> books = new ArrayList<>();
                public Book getBookById(Long id) { return null; }
            }
            """;

    private static final String SERVICE_WITHOUT_STORE = """
            package com.example.library.service;
            import com.example.library.model.Book;
            public class BookService {
                public Book getBookById(Long id) { return null; }
            }
            """;

    @Test
    void parallelStores_bookScenario_introducesViolation_REJECTED_HIGH() {
        // Previous output: controller alone has a store. This cycle adds the service store too.
        Map<String, String> previousOutput = Map.of(
                "src/main/java/com/example/library/model/Book.java", BOOK_ENTITY,
                "src/main/java/com/example/library/controller/BookController.java", CONTROLLER_WITH_STORE,
                "src/main/java/com/example/library/service/BookService.java", SERVICE_WITHOUT_STORE
        );
        Map<String, String> changedFiles = Map.of(
                "src/main/java/com/example/library/service/BookService.java", SERVICE_WITH_STORE
        );

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .previousOutput(previousOutput)
                .projectDiff(new ProjectDiffResult(null, changedFiles, Set.of()))
                .build();

        EvaluationOutcome outcome = baseAcceptedOutcome(0.85);

        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome);

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(opinion.rationale()).contains("parallel store").contains("Book");
        assertThat(opinion.suggestedScore()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void parallelStores_resolved_ACCEPTED_MEDIUM_reward() {
        // Previous output had the violation; this cycle removes the service store.
        Map<String, String> previousOutput = Map.of(
                "src/main/java/com/example/library/model/Book.java", BOOK_ENTITY,
                "src/main/java/com/example/library/controller/BookController.java", CONTROLLER_WITH_STORE,
                "src/main/java/com/example/library/service/BookService.java", SERVICE_WITH_STORE
        );
        Map<String, String> changedFiles = Map.of(
                "src/main/java/com/example/library/service/BookService.java", SERVICE_WITHOUT_STORE
        );

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c2")
                .previousOutput(previousOutput)
                .projectDiff(new ProjectDiffResult(null, changedFiles, Set.of()))
                .build();

        EvaluationOutcome outcome = baseAcceptedOutcome(0.80);

        HeuristicOpinion opinion = heuristic.evaluate(ctx, outcome);

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.PROGRESSING);
        assertThat(opinion.rationale()).contains("resolves").contains("Book");
        assertThat(opinion.suggestedScore()).isCloseTo(0.85, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void noViolation_singleStore_returnsNone() {
        Map<String, String> previousOutput = Map.of(
                "src/main/java/com/example/library/model/Book.java", BOOK_ENTITY,
                "src/main/java/com/example/library/service/BookService.java", SERVICE_WITHOUT_STORE
        );
        Map<String, String> changedFiles = Map.of(
                "src/main/java/com/example/library/controller/BookController.java", CONTROLLER_WITH_STORE
        );

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c3")
                .previousOutput(previousOutput)
                .projectDiff(new ProjectDiffResult(null, changedFiles, Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, baseAcceptedOutcome(0.80));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void noViolation_collectionsOfNonEntityTypes_returnsNone() {
        // String / Integer in collections must not be flagged as entity stores.
        String stringHolder = """
                package com.example.app;
                import java.util.*;
                public class CacheA {
                    private static final List<String> keys = new ArrayList<>();
                }
                """;
        String anotherStringHolder = """
                package com.example.app;
                import java.util.*;
                public class CacheB {
                    private static final List<String> keys = new ArrayList<>();
                }
                """;

        Map<String, String> previousOutput = Map.of(
                "CacheA.java", stringHolder
        );
        Map<String, String> changedFiles = Map.of(
                "CacheB.java", anotherStringHolder
        );

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c4")
                .previousOutput(previousOutput)
                .projectDiff(new ProjectDiffResult(null, changedFiles, Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, baseAcceptedOutcome(0.80));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void noChangedFiles_returnsNone() {
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c5")
                .projectDiff(new ProjectDiffResult(null, Map.of(), Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, baseAcceptedOutcome(0.80));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void extractStoredEntity_handlesListMapAndArrayShapes() {
        assertThat(StoreCoherenceHeuristic.extractStoredEntity("private static final List<Book> books = new ArrayList<>();"))
                .isEqualTo("Book");
        assertThat(StoreCoherenceHeuristic.extractStoredEntity("private Map<Long, Member> members = new HashMap<>();"))
                .isEqualTo("Member");
        assertThat(StoreCoherenceHeuristic.extractStoredEntity("private Set<Loan> loans;"))
                .isEqualTo("Loan");
        assertThat(StoreCoherenceHeuristic.extractStoredEntity("private Loan[] loans = new Loan[0];"))
                .isEqualTo("Loan");
        // Non-entity collection types should return null.
        assertThat(StoreCoherenceHeuristic.extractStoredEntity("private List<String> names;")).isNull();
        // Plain scalar should return null.
        assertThat(StoreCoherenceHeuristic.extractStoredEntity("private Book current;")).isNull();
    }

    private static EvaluationOutcome baseAcceptedOutcome(double score) {
        return new EvaluationOutcome(score, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, null, null);
    }
}
