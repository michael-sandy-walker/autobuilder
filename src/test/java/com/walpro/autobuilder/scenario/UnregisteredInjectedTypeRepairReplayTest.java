package com.walpro.autobuilder.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;
import com.walpro.autobuilder.core.build.InjectedTypeRegistrationRepair;

/**
 * Replays the tree that ended run {@code 2026-08-30_18-44-37} unstartable.
 *
 * <p>The artifact compiled, packaged, and died on startup:
 * <pre>
 * APPLICATION FAILED TO START
 * Parameter 0 of constructor in ...web.BookController required a bean of type
 * '...repository.BookRepository' that could not be found.
 * </pre>
 * {@code BookRepository} was a plain class while both of its package peers carried
 * {@code @Repository}. The loop reported that fact twenty times and never acted on it, because the
 * only consumer was a paragraph in a prompt. These sources are the run's own, trimmed to the
 * members the rules read.
 */
class UnregisteredInjectedTypeRepairReplayTest {

    private static final String BOOK_REPOSITORY = """
            package com.example.library.domain.repository;

            import com.example.library.domain.Book;

            import java.util.ArrayList;
            import java.util.List;

            public class BookRepository {
                private final List<Book> books = new ArrayList<>();

                public Book save(Book book) {
                    books.add(book);
                    return book;
                }
            }
            """;

    private static final String MEMBER_REPOSITORY = """
            package com.example.library.domain.repository;

            import com.example.library.domain.model.Member;
            import org.springframework.stereotype.Repository;

            import java.util.ArrayList;

            @Repository
            public class MemberRepository {
                private final java.util.List<Member> members = new ArrayList<>();
            }
            """;

    private static final String LOAN_REPOSITORY = """
            package com.example.library.domain.repository;

            import com.example.library.domain.model.Loan;
            import org.springframework.stereotype.Repository;

            import java.util.ArrayList;

            @Repository
            public class LoanRepository {
                private final java.util.List<Loan> loans = new ArrayList<>();
            }
            """;

    private static final String BOOK_CONTROLLER = """
            package com.example.library.domain.web;

            import com.example.library.domain.repository.BookRepository;
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.web.bind.annotation.RequestMapping;

            @RestController
            @RequestMapping("/books")
            public class BookController {
                private final BookRepository bookRepository;

                public BookController(BookRepository bookRepository) {
                    this.bookRepository = bookRepository;
                }
            }
            """;

    private static Map<String, String> runTree() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/main/java/com/example/library/domain/repository/BookRepository.java", BOOK_REPOSITORY);
        sources.put("src/main/java/com/example/library/domain/repository/MemberRepository.java", MEMBER_REPOSITORY);
        sources.put("src/main/java/com/example/library/domain/repository/LoanRepository.java", LOAN_REPOSITORY);
        sources.put("src/main/java/com/example/library/domain/web/BookController.java", BOOK_CONTROLLER);
        return sources;
    }

    @Test
    void theRunsOwnTreeStillProducesTheFinding() {
        List<WiringFactsAnalyzer.UnregisteredInjectedType> findings =
                new WiringFactsAnalyzer().unregisteredInjectedTypes(runTree());

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.typeName()).isEqualTo("BookRepository");
            assertThat(f.missingAnnotation()).isEqualTo("Repository");
            assertThat(f.injectedBy()).isEqualTo("BookController");
            assertThat(f.peers()).containsExactlyInAnyOrder("MemberRepository", "LoanRepository");
        });
    }

    @Test
    void theRepairGivesTheTypeItsPeersRegistrationAndTheirImport() {
        Map<String, String> tree = runTree();
        List<InjectedTypeRegistrationRepair.Repair> plan = InjectedTypeRegistrationRepair.plan(
                tree, new WiringFactsAnalyzer().unregisteredInjectedTypes(tree));

        assertThat(plan).singleElement().satisfies(r -> {
            assertThat(r.path()).endsWith("BookRepository.java");
            assertThat(r.importLine())
                    .as("the spelling comes from a peer, not from a table this project maintains")
                    .isEqualTo("import org.springframework.stereotype.Repository;");
        });

        String repaired = InjectedTypeRegistrationRepair.apply(BOOK_REPOSITORY, plan.get(0)).orElseThrow();
        assertThat(repaired).contains("import org.springframework.stereotype.Repository;");
        assertThat(repaired).containsPattern("(?m)^@Repository\\s*$");
        assertThat(repaired.indexOf("@Repository\npublic class BookRepository"))
                .as("the annotation sits on the declaration it registers")
                .isGreaterThan(0);
    }

    @Test
    void therepairedTreeNoLongerReportsTheFinding() {
        Map<String, String> tree = runTree();
        var plan = InjectedTypeRegistrationRepair.plan(
                tree, new WiringFactsAnalyzer().unregisteredInjectedTypes(tree));
        for (var r : plan) {
            tree.put(r.path(), InjectedTypeRegistrationRepair.apply(tree.get(r.path()), r).orElseThrow());
        }

        assertThat(new WiringFactsAnalyzer().unregisteredInjectedTypes(tree))
                .as("the repair answers the fact that produced it")
                .isEmpty();
    }

    /** Re-running must be inert: the second pass has nothing to add. */
    @Test
    void applyingTwiceChangesNothingTheSecondTime() {
        Map<String, String> tree = runTree();
        var plan = InjectedTypeRegistrationRepair.plan(
                tree, new WiringFactsAnalyzer().unregisteredInjectedTypes(tree));
        String once = InjectedTypeRegistrationRepair.apply(BOOK_REPOSITORY, plan.get(0)).orElseThrow();

        Optional<String> twice = InjectedTypeRegistrationRepair.apply(once, plan.get(0));

        assertThat(twice).isEmpty();
    }

    /**
     * The guard that keeps this from inventing an import: when no peer names the annotation, there
     * is no authority for how it is spelled, so no repair is planned. An invented import is a
     * compile error, which is worse than the inert type the loop already has.
     */
    @Test
    void noRepairIsPlannedWhenNoPeerImportsTheAnnotation() {
        Map<String, String> tree = runTree();
        tree.put("src/main/java/com/example/library/domain/repository/MemberRepository.java",
                MEMBER_REPOSITORY.replace("import org.springframework.stereotype.Repository;\n", ""));
        tree.put("src/main/java/com/example/library/domain/repository/LoanRepository.java",
                LOAN_REPOSITORY.replace("import org.springframework.stereotype.Repository;\n", ""));

        assertThat(InjectedTypeRegistrationRepair.plan(
                tree, new WiringFactsAnalyzer().unregisteredInjectedTypes(tree))).isEmpty();
    }
}
