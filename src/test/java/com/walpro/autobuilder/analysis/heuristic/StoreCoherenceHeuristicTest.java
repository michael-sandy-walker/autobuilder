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
    void parallelStores_bookScenario_introducesViolation_REJECTED_CRITICAL() {
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

        // CRITICAL, not HIGH: run 13 (2026-07-04) proved a correct HIGH vote from this
        // detector gets averaged away at exactly the merge that breaks the artifact.
        // Defect-marked: run 14 proved the score echo can outrank a plain CRITICAL.
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(opinion.defectFinding()).isTrue();
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(opinion.rationale()).contains("parallel store").contains("Book");
        assertThat(opinion.suggestedScore()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(1e-9));
    }

    /**
     * The run-4b (2026-07-03) field miss, verbatim shape: the parallel stores were declared as
     * {@code ConcurrentMap<Long, Book>} — the INTERFACE type, absent from the old enumerated
     * alternation — and the heuristic fired zero times all run. Suffix-based matching must flag
     * any *Map/*List/*Set/... simple name.
     */
    @Test
    void parallelStores_concurrentMapInterfaceType_isDetected() {
        String bookService = """
                package com.example.demo.service;
                import com.example.demo.model.Book;
                import java.util.concurrent.*;
                public class BookService {
                    private final ConcurrentMap<Long, Book> books = new ConcurrentHashMap<>();
                    public Book getBookById(Long id) { return books.get(id); }
                }
                """;
        String loanService = """
                package com.example.demo.service;
                import com.example.demo.model.Book;
                import java.util.concurrent.*;
                public class LoanService {
                    private final ConcurrentMap<Long, Book> books = new ConcurrentHashMap<>();
                    public Book findBook(Long id) { return books.get(id); }
                }
                """;
        Map<String, String> previousOutput = Map.of(
                "src/main/java/com/example/demo/model/Book.java", BOOK_ENTITY,
                "src/main/java/com/example/demo/service/BookService.java", bookService
        );
        Map<String, String> changedFiles = Map.of(
                "src/main/java/com/example/demo/service/LoanService.java", loanService
        );

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .previousOutput(previousOutput)
                .projectDiff(new ProjectDiffResult(null, changedFiles, Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, baseAcceptedOutcome(0.85));

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.rationale()).contains("parallel store").contains("Book");
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

    /**
     * T2-ES, the field shape made into a test. Run {@code 2026-09-13_09-44-03} rejected seven
     * consecutive mutations for one parallel store, and the rationale moved with the model's
     * rewrites — {@code BookStore:14}, then {@code :11}, then {@code :13}. Three strings, one
     * defect, and a content fingerprint of the prose counted it as three different things.
     *
     * <p>So the assertion that matters is the PAIR: the rationale must differ between the two
     * cycles (otherwise the test proves nothing about rewrites) while the identity key holds.
     */
    @Test
    void sameDefectAcrossARewriteThatMovesTheDeclaration_keepsOneIdentity() {
        String repository = """
                package com.example.library.repository;
                import com.example.library.model.Book;
                import java.util.*;
                public class BookRepository {
                    private final List<Book> books = new ArrayList<>();
                    public void save(Book b) { books.add(b); }
                }
                """;
        String storeLowLine = """
                package com.example.library.store;
                import com.example.library.model.Book;
                import java.util.*;
                public class BookStore {
                    private final List<Book> books = new ArrayList<>();
                    public List<Book> all() { return books; }
                }
                """;
        // The same class, rewritten: a javadoc block pushes the declaration further down the file.
        String storeHighLine = """
                package com.example.library.store;
                import com.example.library.model.Book;
                import java.util.*;

                /**
                 * Holds books.
                 * Rewritten by a later cycle.
                 */
                public class BookStore {
                    private final List<Book> books = new ArrayList<>();
                    public List<Book> all() { return books; }
                }
                """;

        Map<String, String> previousOutput = Map.of(
                "src/main/java/com/example/library/model/Book.java", BOOK_ENTITY,
                "src/main/java/com/example/library/repository/BookRepository.java", repository
        );

        HeuristicOpinion first = heuristic.evaluate(
                CycleContextData.builder()
                        .cycleName("c1")
                        .previousOutput(previousOutput)
                        .projectDiff(new ProjectDiffResult(null, Map.of(
                                "src/main/java/com/example/library/store/BookStore.java", storeLowLine),
                                Set.of()))
                        .build(),
                baseAcceptedOutcome(0.85));

        HeuristicOpinion second = heuristic.evaluate(
                CycleContextData.builder()
                        .cycleName("c2")
                        .previousOutput(previousOutput)
                        .projectDiff(new ProjectDiffResult(null, Map.of(
                                "src/main/java/com/example/library/store/BookStore.java", storeHighLine),
                                Set.of()))
                        .build(),
                baseAcceptedOutcome(0.85));

        assertThat(first.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(second.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);

        // The prose moved, exactly as it did in the field.
        assertThat(first.rationale()).isNotEqualTo(second.rationale());

        // The identity did not.
        assertThat(first.identityKey()).isEqualTo(second.identityKey());
        assertThat(first.identityKey()).isEqualTo("StoreCoherenceHeuristic|Book");
    }

    /**
     * T2-ES: the finding states the ways out, because only it knows the shape of its own defect.
     * One path per declaring type — keep this one, route the others through it — which is the
     * choice re-asking never offers.
     */
    @Test
    void defectFinding_offersOneResolutionPathPerDeclaringType() {
        Map<String, String> previousOutput = Map.of(
                "src/main/java/com/example/library/model/Book.java", BOOK_ENTITY,
                "src/main/java/com/example/library/controller/BookController.java", CONTROLLER_WITH_STORE,
                "src/main/java/com/example/library/service/BookService.java", SERVICE_WITHOUT_STORE
        );
        CycleContextData ctx = CycleContextData.builder()
                .cycleName("c1")
                .previousOutput(previousOutput)
                .projectDiff(new ProjectDiffResult(null, Map.of(
                        "src/main/java/com/example/library/service/BookService.java", SERVICE_WITH_STORE),
                        Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, baseAcceptedOutcome(0.85));

        assertThat(opinion.defect()).isNotNull();
        assertThat(opinion.defect().subject()).isEqualTo("Book");
        assertThat(opinion.defect().resolutionPaths()).hasSize(2);
        // Each path nominates a different survivor, and each mentions the other holder.
        assertThat(opinion.defect().resolutionPaths())
                .anySatisfy(p -> assertThat(p).startsWith("BookController is the only type")
                        .contains("BookService"))
                .anySatisfy(p -> assertThat(p).startsWith("BookService is the only type")
                        .contains("BookController"));
    }

    /**
     * T2-ES: a finding about two entities is not the same finding as one about either alone, so
     * the subject is the whole set and is order-independent.
     */
    @Test
    void defectSubject_isOrderIndependentAcrossTheWholeIntroducedSet() {
        assertThat(StoreCoherenceHeuristic.defectSubject(new java.util.LinkedHashSet<>(
                java.util.List.of("Member", "Book"))))
                .isEqualTo("Book,Member");
        assertThat(StoreCoherenceHeuristic.defectSubject(new java.util.LinkedHashSet<>(
                java.util.List.of("Book", "Member"))))
                .isEqualTo("Book,Member");
        assertThat(StoreCoherenceHeuristic.defectSubject(new java.util.LinkedHashSet<>(
                java.util.List.of("Book"))))
                .isNotEqualTo("Book,Member");
    }

    private static EvaluationOutcome baseAcceptedOutcome(double score) {
        return new EvaluationOutcome(score, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, null, null);
    }
}
