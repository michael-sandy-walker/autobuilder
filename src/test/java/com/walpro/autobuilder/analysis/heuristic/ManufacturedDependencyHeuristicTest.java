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

        // CRITICAL, not HIGH: deterministic runtime-fatal findings take the negotiator's
        // overrule path (T4-BW, from run 13 2026-07-04 where a HIGH vote was averaged away).
        // Defect-marked: run 14 proved the score echo can outrank a plain CRITICAL.
        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(opinion.defectFinding()).isTrue();
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
    void adviceClass_manufacturingAComponent_isFlagged() {
        // One-home regression (T4-BM, 2026-07-14): this class's own marker list had drifted
        // from the shared container-managed vocabulary — the advice stereotypes were missing,
        // so an advice class manufacturing a sibling component went undetected.
        String adviceManufacturing = """
                package com.example.library.web;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                @RestControllerAdvice
                public class ApiExceptionHandler {
                    private final BookRepositoryImpl bookRepository = new BookRepositoryImpl();
                }
                """;
        Map<String, String> previous = Map.of(
                "src/main/java/com/example/library/domain/BookRepositoryImpl.java", REPOSITORY_IMPL
        );
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/web/ApiExceptionHandler.java", adviceManufacturing
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.85));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(opinion.rationale()).contains("ApiExceptionHandler manufactures BookRepositoryImpl");
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

    // ── T1-FK: duplicate manufacture across factory methods (run 23, 2026-07-07) ──────────

    private static final String PLAIN_REPOSITORY = """
            package com.example.library;
            public class BookRepository {
                public void save() {}
            }
            """;

    private static final String OTHER_PLAIN_REPOSITORY = """
            package com.example.library;
            public class MemberRepository {
                public void save() {}
            }
            """;

    /** The run-23 shape: entry class, marker-free repos, same type new'd in TWO factory methods. */
    private static final String ENTRY_DUPLICATE_MANUFACTURE = """
            package com.example.library;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            import org.springframework.context.annotation.Bean;
            @SpringBootApplication
            public class LibraryApplication {
                public static void main(String[] args) {}
                @Bean
                public BookService bookService() {
                    return new BookService(new BookRepository());
                }
                @Bean
                public LoanService loanService() {
                    return new LoanService(new BookRepository(), new MemberRepository());
                }
            }
            """;

    private static final String ENTRY_SINGLE_MANUFACTURE = """
            package com.example.library;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            import org.springframework.context.annotation.Bean;
            @SpringBootApplication
            public class LibraryApplication {
                public static void main(String[] args) {}
                @Bean
                public BookService bookService() {
                    return new BookService(new BookRepository());
                }
            }
            """;

    private static final String ENTRY_SHARED_BEAN = """
            package com.example.library;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            import org.springframework.context.annotation.Bean;
            @SpringBootApplication
            public class LibraryApplication {
                public static void main(String[] args) {}
                @Bean
                public BookRepository bookRepository() {
                    return new BookRepository();
                }
                @Bean
                public BookService bookService(BookRepository repo) {
                    return new BookService(repo);
                }
                @Bean
                public LoanService loanService(BookRepository repo) {
                    return new LoanService(repo, new MemberRepository());
                }
            }
            """;

    private static final String SERVICE_CLASSES = """
            package com.example.library;
            public class BookService {
                public BookService(BookRepository r) {}
            }
            class LoanService2 {}
            """;

    private Map<String, String> run23Previous() {
        return Map.of(
                "src/main/java/com/example/library/BookRepository.java", PLAIN_REPOSITORY,
                "src/main/java/com/example/library/MemberRepository.java", OTHER_PLAIN_REPOSITORY,
                "src/main/java/com/example/library/BookService.java", SERVICE_CLASSES,
                "src/main/java/com/example/library/LibraryApplication.java", ENTRY_SINGLE_MANUFACTURE
        );
    }

    @Test
    void run23Shape_sameTypeManufacturedInTwoFactoryMethods_introduced_REJECTED() {
        // The entry class carries @SpringBootApplication (no plain configuration marker) and the
        // manufactured repository types carry NO markers at all — both gates must still engage.
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/LibraryApplication.java", ENTRY_DUPLICATE_MANUFACTURE
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(run23Previous(), changed), baseAcceptedOutcome(0.85));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(opinion.defectFinding()).isTrue();
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.rationale())
                .contains("LibraryApplication duplicate-manufactures BookRepository")
                .contains("bookService")
                .contains("loanService")
                .contains("shared component");
    }

    @Test
    void distinctTypesPerFactoryMethod_sanctionedIdiom_notFlagged() {
        // Multiple factory methods each manufacturing their own distinct types stays the idiom.
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/LibraryApplication.java", ENTRY_SINGLE_MANUFACTURE
        );
        Map<String, String> previous = Map.of(
                "src/main/java/com/example/library/BookRepository.java", PLAIN_REPOSITORY,
                "src/main/java/com/example/library/BookService.java", SERVICE_CLASSES
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.85));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void duplicateResolvedIntoSharedBean_ACCEPTED_withReward() {
        Map<String, String> previous = new java.util.LinkedHashMap<>(run23Previous());
        previous.put("src/main/java/com/example/library/LibraryApplication.java", ENTRY_DUPLICATE_MANUFACTURE);
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/LibraryApplication.java", ENTRY_SHARED_BEAN
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.80));

        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.ACCEPTED);
        assertThat(opinion.rationale())
                .contains("resolves manufactured dependency")
                .contains("LibraryApplication duplicate-manufactures BookRepository");
    }

    @Test
    void libraryType_manufacturedInTwoFactoryMethods_notFlagged() {
        // A type NOT declared in the project (library/framework class) is unjudgeable — building
        // one per factory method may be entirely intentional.
        String config = """
                package com.example.library;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                public class JsonConfig {
                    @Bean
                    public Object readerMapper() { return new ObjectMapper(); }
                    @Bean
                    public Object writerMapper() { return new ObjectMapper(); }
                }
                """;
        Map<String, String> previous = Map.of(
                "src/main/java/com/example/library/BookRepository.java", PLAIN_REPOSITORY
        );
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/JsonConfig.java", config
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.85));

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void detection_duplicateFactoryManufactures_reportsMethodsAndTypes() {
        Map<String, String> files = new java.util.LinkedHashMap<>(run23Previous());
        files.put("src/main/java/com/example/library/LibraryApplication.java", ENTRY_DUPLICATE_MANUFACTURE);

        List<ManufacturedDependencyHeuristic.FactoryDuplicate> duplicates =
                heuristic.duplicateFactoryManufactures(files);

        assertThat(duplicates).hasSize(1);
        assertThat(duplicates.get(0).factoryType()).isEqualTo("LibraryApplication");
        assertThat(duplicates.get(0).manufacturedType()).isEqualTo("BookRepository");
        assertThat(duplicates.get(0).methodNames()).containsExactlyInAnyOrder("bookService", "loanService");
        // MemberRepository appears in only ONE factory method — not a duplicate.
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

    /**
     * T2-ES: the sibling of the store-coherence identity. The site key already excludes the
     * declaration line, so this finding survives a rewrite that moves the code — and the remedy
     * the rationale used to state only as trailing advice is now a requirement about the code,
     * which is what lets an escalated work item be confirmable once the defect is gone.
     */
    @Test
    void defectFinding_carriesAStableSubjectAndTheWayOut() {
        Map<String, String> previous = Map.of(
                "src/main/java/com/example/library/domain/BookRepositoryImpl.java", REPOSITORY_IMPL,
                "src/main/java/com/example/library/domain/BookServiceImpl.java", SERVICE_INJECTING
        );
        Map<String, String> changed = Map.of(
                "src/main/java/com/example/library/domain/BookServiceImpl.java", SERVICE_MANUFACTURING
        );

        HeuristicOpinion opinion = heuristic.evaluate(ctx(previous, changed), baseAcceptedOutcome(0.85));

        assertThat(opinion.defect()).isNotNull();
        assertThat(opinion.defect().subject())
                .isEqualTo("BookServiceImpl manufactures BookRepositoryImpl");
        assertThat(opinion.identityKey())
                .isEqualTo("ManufacturedDependencyHeuristic|BookServiceImpl manufactures BookRepositoryImpl");
        assertThat(opinion.defect().resolutionPaths()).singleElement().asString()
                .isEqualTo("BookServiceImpl receives its BookRepositoryImpl through its constructor, "
                        + "and creates no BookRepositoryImpl of its own");
    }
}
