package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-DI pattern 5 regression tests — the run-5 (2026-07-02) shape: {@code BookServiceImpl}
 * (a @Service) manufactured its own {@code BookRepositoryImpl} (a @Repository) with {@code new}
 * instead of constructor injection, so the loan service read an empty container singleton while
 * books lived in the service's private instance. Compiles clean; fails only at runtime.
 */
class ManufacturedDependencyHeuristicTest {

    private final ManufacturedDependencyHeuristic heuristic = new ManufacturedDependencyHeuristic();

    private static final String REPOSITORY_IMPL = """
            package com.example.library.domain;
            import org.springframework.stereotype.Repository;
            @Repository
            public class BookRepositoryImpl implements BookRepository {
                public Book save(Book b) { return b; }
            }
            """;

    private static final String SERVICE_MANUFACTURING = """
            package com.example.library.domain;
            import org.springframework.stereotype.Service;
            @Service
            public class BookServiceImpl implements BookService {
                private final BookRepository bookRepository;
                public BookServiceImpl() {
                    this.bookRepository = new BookRepositoryImpl();
                }
            }
            """;

    private static final String SERVICE_INJECTING = """
            package com.example.library.domain;
            import org.springframework.stereotype.Service;
            @Service
            public class BookServiceImpl implements BookService {
                private final BookRepository bookRepository;
                public BookServiceImpl(BookRepository bookRepository) {
                    this.bookRepository = bookRepository;
                }
            }
            """;

    private static final String PLAIN_HELPER_USER = """
            package com.example.library.domain;
            import org.springframework.stereotype.Service;
            @Service
            public class LoanServiceImpl {
                public String format() { return new StringBuilder().append("x").toString(); }
                public Book blank() { return new Book(); }
            }
            """;

    private static CycleContextData ctx(Map<String, String> previous, Map<String, String> changed) {
        return CycleContextData.builder()
                .cycleName("c1")
                .previousOutput(previous)
                .projectDiff(new ProjectDiffResult(null, changed, Set.of()))
                .build();
    }

    private static EvaluationOutcome baseAcceptedOutcome(double score) {
        return new EvaluationOutcome(score, MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING, null, null);
    }

    @Test
    void run5Shape_serviceManufacturesRepository_introduced_REJECTED() {
        Map<String, String> previous = Map.of(
                "src/main/java/com/example/library/domain/BookRepositoryImpl.java", REPOSITORY_IMPL,
                "src/main/java/com/example/library/domain/BookServiceImpl.java", SERVICE_INJECTING
        );
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/domain/BookServiceImpl.java", SERVICE_MANUFACTURING
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.85));

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(opinion.rationale())
                .contains("BookServiceImpl manufactures BookRepositoryImpl")
                .contains("inject it through the constructor");
        assertThat(opinion.suggestedScore()).isEqualTo(0.75);
    }

    @Test
    void diffResolvesManufacture_ACCEPTED_withReward() {
        Map<String, String> previous = Map.of(
                "src/main/java/com/example/library/domain/BookRepositoryImpl.java", REPOSITORY_IMPL,
                "src/main/java/com/example/library/domain/BookServiceImpl.java", SERVICE_MANUFACTURING
        );
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/domain/BookServiceImpl.java", SERVICE_INJECTING
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.80));

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.rationale()).contains("resolves manufactured dependency");
        assertThat(opinion.suggestedScore()).isEqualTo(0.85, org.assertj.core.api.Assertions.withPrecision(1e-9));
    }

    @Test
    void newOfPlainTypes_neverFlagged() {
        // StringBuilder, entity POJOs, collections — new is normal for non-component types.
        Map<String, String> previous = Map.of(
                "src/main/java/com/example/library/domain/BookRepositoryImpl.java", REPOSITORY_IMPL
        );
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/domain/LoanServiceImpl.java", PLAIN_HELPER_USER
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.85));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void preexistingManufacture_untouchedByDiff_noOpinion() {
        // The violation predates the diff — this mutation neither introduced nor resolved it.
        Map<String, String> previous = Map.of(
                "src/main/java/com/example/library/domain/BookRepositoryImpl.java", REPOSITORY_IMPL,
                "src/main/java/com/example/library/domain/BookServiceImpl.java", SERVICE_MANUFACTURING
        );
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/domain/Unrelated.java",
                "package com.example.library.domain;\npublic class Unrelated {}\n"
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.85));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void nonContainerProject_noMarkers_neverFormsAnOpinion() {
        // Ecosystem-gated by construction (user steer 2026-07-02): this is a
        // dependency-injection-container rule — plain-Java projects with identical wiring
        // (a class calling new on another class) must never be judged by it.
        String plainService = """
                package com.example.plain;
                public class BookServiceImpl {
                    private final BookRepositoryImpl bookRepository = new BookRepositoryImpl();
                }
                """;
        String plainRepo = """
                package com.example.plain;
                public class BookRepositoryImpl {
                    public void save() {}
                }
                """;
        Map<String, String> previous = Map.of(
                "src/main/java/com/example/plain/BookRepositoryImpl.java", plainRepo
        );
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/plain/BookServiceImpl.java", plainService
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.85));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
        assertThat(heuristic.componentTypes(Map.of("A.java", plainService, "B.java", plainRepo))).isEmpty();
    }

    @Test
    void configurationClass_mayManufactureComponents_notFlagged() {
        // Factory/configuration classes are the container's sanctioned manufacture site —
        // @Bean methods returning new instances are the idiom, not the defect.
        String config = """
                package com.example.library.config;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                public class RepositoryConfig {
                    @Bean
                    public BookRepository bookRepository() {
                        return new BookRepositoryImpl();
                    }
                }
                """;
        Map<String, String> previous = Map.of(
                "src/main/java/com/example/library/domain/BookRepositoryImpl.java", REPOSITORY_IMPL
        );
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/config/RepositoryConfig.java", config
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.85));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void detection_findsSiteWithLocation() {
        Map<String, String> files = Map.of(
                "src/main/java/com/example/library/domain/BookRepositoryImpl.java", REPOSITORY_IMPL,
                "src/main/java/com/example/library/domain/BookServiceImpl.java", SERVICE_MANUFACTURING
        );
        List<ManufacturedDependencyHeuristic.ManufactureSite> sites =
                heuristic.manufacturedDependencies(files);

        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).enclosingType()).isEqualTo("BookServiceImpl");
        assertThat(sites.get(0).manufacturedType()).isEqualTo("BookRepositoryImpl");
        assertThat(sites.get(0).line()).isGreaterThan(1);
    }
}
