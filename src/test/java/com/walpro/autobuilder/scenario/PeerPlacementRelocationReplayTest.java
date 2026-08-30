package com.walpro.autobuilder.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;
import com.walpro.autobuilder.core.build.PeerPlacementRelocation;

/**
 * Replays the tree that nine runs were told about and never changed.
 *
 * <p>{@code Book} sits in {@code …domain} while {@code Loan} and {@code Member} sit in
 * {@code …domain.model}, and the three repositories that name them share one package. The
 * artifact compiles and boots, so no gate has reason to speak — and across
 * {@code 2026-08-24_22-14-05} through {@code 2026-08-30_19-42-35} the placement fact was stated
 * for the whole life of every run in which it fired, with the type still misplaced in the final
 * artifact each time. These sources are run {@code 2026-08-30_19-42-35}'s own, trimmed to the
 * members the rules read.
 */
class PeerPlacementRelocationReplayTest {

    private static final String ROOT = "src/main/java/com/example/library/";

    private static final String APPLICATION = """
            package com.example.library.domain;

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class Application {
                public static void main(String[] args) {
                    SpringApplication.run(Application.class, args);
                }
            }
            """;

    private static final String BOOK = """
            package com.example.library.domain;

            public class Book {
                private Long id;
                private String title;
                private String isbn;

                public Book() {}

                public Book(Long id, String title, String isbn) {
                    this.id = id;
                    this.title = title;
                    this.isbn = isbn;
                }

                public Long getId() { return id; }
                public String getIsbn() { return isbn; }
            }
            """;

    private static final String LOAN = """
            package com.example.library.domain.model;

            public class Loan {
                private Long id;
                private Long bookId;

                public Loan() {}
            }
            """;

    private static final String MEMBER = """
            package com.example.library.domain.model;

            public class Member {
                private Long id;
                private String name;

                public Member() {}
            }
            """;

    private static final String BOOK_REPOSITORY = """
            package com.example.library.domain.repository;

            import com.example.library.domain.Book;
            import org.springframework.stereotype.Repository;

            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;

            @Repository
            public class BookRepository {
                private final Map<Long, Book> store = new ConcurrentHashMap<>();

                public Book save(Book book) {
                    store.put(book.getId(), book);
                    return book;
                }
            }
            """;

    private static final String LOAN_REPOSITORY = """
            package com.example.library.domain.repository;

            import com.example.library.domain.model.Loan;
            import org.springframework.stereotype.Repository;

            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;

            @Repository
            public class LoanRepository {
                private final Map<Long, Loan> store = new ConcurrentHashMap<>();
            }
            """;

    private static final String MEMBER_REPOSITORY = """
            package com.example.library.domain.repository;

            import com.example.library.domain.model.Member;
            import org.springframework.stereotype.Repository;

            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;

            @Repository
            public class MemberRepository {
                private final Map<Long, Member> store = new ConcurrentHashMap<>();
            }
            """;

    private static final String BOOK_CONTROLLER = """
            package com.example.library.domain.controller;

            import com.example.library.domain.Book;
            import com.example.library.domain.service.BookService;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class BookController {
                private final BookService bookService;

                public BookController(BookService bookService) {
                    this.bookService = bookService;
                }

                public Book create(Book book) {
                    return bookService.create(book);
                }
            }
            """;

    private static final String BOOK_SERVICE = """
            package com.example.library.domain.service;

            import com.example.library.domain.Book;
            import com.example.library.domain.repository.BookRepository;
            import org.springframework.stereotype.Service;

            @Service
            public class BookService {
                private final BookRepository bookRepository;

                public BookService(BookRepository bookRepository) {
                    this.bookRepository = bookRepository;
                }

                public Book create(Book book) {
                    return bookRepository.save(book);
                }
            }
            """;

    private static Map<String, String> tree() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(ROOT + "domain/Application.java", APPLICATION);
        sources.put(ROOT + "domain/Book.java", BOOK);
        sources.put(ROOT + "domain/model/Loan.java", LOAN);
        sources.put(ROOT + "domain/model/Member.java", MEMBER);
        sources.put(ROOT + "domain/repository/BookRepository.java", BOOK_REPOSITORY);
        sources.put(ROOT + "domain/repository/LoanRepository.java", LOAN_REPOSITORY);
        sources.put(ROOT + "domain/repository/MemberRepository.java", MEMBER_REPOSITORY);
        sources.put(ROOT + "domain/controller/BookController.java", BOOK_CONTROLLER);
        sources.put(ROOT + "domain/service/BookService.java", BOOK_SERVICE);
        return sources;
    }

    private final WiringFactsAnalyzer analyzer = new WiringFactsAnalyzer();

    @Test
    void theFieldTreeYieldsOneFinding_andItIsBook() {
        var findings = analyzer.peerPlacementFindings(tree());

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).typeName()).isEqualTo("Book");
        assertThat(findings.get(0).actualPackage()).isEqualTo("com.example.library.domain");
        assertThat(findings.get(0).majorityPackage()).isEqualTo("com.example.library.domain.model");
        assertThat(findings.get(0).agreeing()).containsExactlyInAnyOrder("Member", "Loan");
    }

    @Test
    void theMoveGoesWhereThePeersAlreadyLive() {
        Map<String, String> sources = tree();

        var plan = PeerPlacementRelocation.plan(sources, analyzer.peerPlacementFindings(sources));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).fromPath()).isEqualTo(ROOT + "domain/Book.java");
        assertThat(plan.get(0).toPath()).isEqualTo(ROOT + "domain/model/Book.java");
    }

    @Test
    void everyReferenceFollowsTheType_andTheOldCoordinateIsGoneFromTheTree() {
        Map<String, String> sources = tree();
        var plan = PeerPlacementRelocation.plan(sources, analyzer.peerPlacementFindings(sources));

        var applied = PeerPlacementRelocation.apply(sources, plan.get(0)).orElseThrow();
        Map<String, String> after = new LinkedHashMap<>(sources);
        after.remove(applied.vacatedPath());
        after.putAll(applied.writes());

        assertThat(after).containsKey(ROOT + "domain/model/Book.java");
        assertThat(after).doesNotContainKey(ROOT + "domain/Book.java");
        assertThat(after.get(ROOT + "domain/model/Book.java"))
                .contains("package com.example.library.domain.model;");
        // The three files that named it across a package boundary now name it where it lives.
        assertThat(after.get(ROOT + "domain/repository/BookRepository.java"))
                .contains("import com.example.library.domain.model.Book;");
        assertThat(after.get(ROOT + "domain/controller/BookController.java"))
                .contains("import com.example.library.domain.model.Book;");
        assertThat(after.get(ROOT + "domain/service/BookService.java"))
                .contains("import com.example.library.domain.model.Book;");
        assertThat(after.values()).noneMatch(s -> s.contains("com.example.library.domain.Book"));
        // Sibling types whose name merely starts with the moved one's are untouched.
        assertThat(after.get(ROOT + "domain/service/BookService.java"))
                .contains("import com.example.library.domain.repository.BookRepository;");
    }

    @Test
    void afterTheMoveThereIsNothingLeftToReport_soTheLoopIsNotToldAgain() {
        Map<String, String> sources = tree();
        var plan = PeerPlacementRelocation.plan(sources, analyzer.peerPlacementFindings(sources));
        var applied = PeerPlacementRelocation.apply(sources, plan.get(0)).orElseThrow();
        Map<String, String> after = new LinkedHashMap<>(sources);
        after.remove(applied.vacatedPath());
        after.putAll(applied.writes());

        assertThat(analyzer.peerPlacementFindings(after)).isEmpty();
        assertThat(PeerPlacementRelocation.plan(after, analyzer.peerPlacementFindings(after))).isEmpty();
    }
}
