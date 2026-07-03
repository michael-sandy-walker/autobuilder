package com.walpro.autobuilder.analysis.structure;

import static org.assertj.core.api.Assertions.assertThat;

import com.walpro.autobuilder.testsupport.HollowVsRealFixtures;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WiringFactsAnalyzerTest {

    private final WiringFactsAnalyzer analyzer = new WiringFactsAnalyzer();

    @Test
    void hollowCheckout_flagsStubBodyAndNeverAssignedFields() {
        List<String> obs = analyzer.analyze(HollowVsRealFixtures.computedPricing().hollow());

        assertThat(obs).anyMatch(o -> o.contains("checkout") && o.contains("stub"));
        // The empty PriceBreakdown's fields are never assigned — the core hollowness signal.
        assertThat(obs).anyMatch(o -> o.contains("subtotal") && o.contains("never assigned"));
        assertThat(obs).anyMatch(o -> o.contains("total") && o.contains("never assigned"));
    }

    @Test
    void realCheckout_doesNotFlagStubOrUnassignedPricing() {
        List<String> obs = analyzer.analyze(HollowVsRealFixtures.computedPricing().real());

        assertThat(obs).noneMatch(o -> o.contains("checkout") && o.contains("stub"));
        assertThat(obs).noneMatch(o -> o.contains("subtotal") && o.contains("never assigned"));
    }

    @Test
    void thrownButUnmapped_flagsMissingStatusMapping() {
        Map<String, String> files = Map.of("src/main/java/A.java", """
                package a;
                public class A {
                    public String find(Long id) {
                        if (id == null) throw new IllegalArgumentException("bad");
                        return "ok";
                    }
                }
                """);
        assertThat(analyzer.analyze(files))
                .anyMatch(o -> o.contains("no HTTP status mapping"));
    }

    @Test
    void thrownWithControllerAdvice_doesNotFlagStatusMapping() {
        Map<String, String> files = Map.of(
                "src/main/java/A.java", """
                        package a;
                        public class A {
                            public String find(Long id) {
                                if (id == null) throw new IllegalArgumentException("bad");
                                return "ok";
                            }
                        }
                        """,
                "src/main/java/H.java", """
                        package a;
                        import org.springframework.web.bind.annotation.*;
                        @RestControllerAdvice
                        public class H {
                            @ExceptionHandler(IllegalArgumentException.class)
                            public String h(IllegalArgumentException e) { return e.getMessage(); }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("no HTTP status mapping"));
    }

    @Test
    void rawThrowsPastTypedMappings_flagsBothSidesOfTheGap() {
        // Field run 3 (2026-07-03) verbatim shape: 8 dedicated exception types all mapped in a
        // @ControllerAdvice, while the service throws raw RuntimeException for every rule — the
        // handlers are unreachable and every violation surfaces as a 500. The file-level check is
        // silent (mappings exist); the type-level comparison must name both halves.
        Map<String, String> files = Map.of(
                "src/main/java/LoanService.java", """
                        package a;
                        public class LoanService {
                            public String loan(Long bookId) {
                                if (bookId == null) throw new RuntimeException("Book is already on loan");
                                return "ok";
                            }
                        }
                        """,
                "src/main/java/BookAlreadyLoanedException.java", """
                        package a;
                        public class BookAlreadyLoanedException extends RuntimeException {
                            public BookAlreadyLoanedException(String m) { super(m); }
                        }
                        """,
                "src/main/java/H.java", """
                        package a;
                        import org.springframework.web.bind.annotation.*;
                        @ControllerAdvice
                        public class H {
                            @ExceptionHandler(BookAlreadyLoanedException.class)
                            public String h(BookAlreadyLoanedException e) { return e.getMessage(); }
                        }
                        """);
        List<String> obs = analyzer.analyze(files);
        assertThat(obs).anyMatch(o -> o.contains("RuntimeException")
                && o.contains("not covered by any mapped type"));
        assertThat(obs).anyMatch(o -> o.contains("BookAlreadyLoanedException")
                && o.contains("never instantiated"));
        assertThat(obs).noneMatch(o -> o.contains("no HTTP status mapping"));
    }

    @Test
    void thrownSubclass_coveredByMappedSupertype_staysSilent() {
        // A mapped ancestor covers thrown subclasses — supertype chains must resolve through the
        // project-declared extends edges, or every typed throw under a broad handler false-alarms.
        Map<String, String> files = Map.of(
                "src/main/java/A.java", """
                        package a;
                        public class A {
                            public String find(Long id) {
                                if (id == null) throw new BookNotFoundException("missing");
                                return "ok";
                            }
                        }
                        """,
                "src/main/java/BookNotFoundException.java", """
                        package a;
                        public class BookNotFoundException extends RuntimeException {
                            public BookNotFoundException(String m) { super(m); }
                        }
                        """,
                "src/main/java/H.java", """
                        package a;
                        import org.springframework.web.bind.annotation.*;
                        @ControllerAdvice
                        public class H {
                            @ExceptionHandler(RuntimeException.class)
                            public String h(RuntimeException e) { return e.getMessage(); }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("not covered by any mapped type"));
    }

    @Test
    void containerInjectedField_isNotReportedNeverAssigned() {
        // Field-injection style: the DI container assigns @Autowired fields — 8 false
        // "never assigned" facts on the run-3 artifact (2026-07-03) before this guard.
        Map<String, String> files = Map.of("src/main/java/C.java", """
                package a;
                import org.springframework.beans.factory.annotation.Autowired;
                public class C {
                    @Autowired
                    private Object service;
                    public Object get() { return service; }
                }
                """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("never assigned"));
    }

    @Test
    void mappedTypeInstantiatedViaOrElseThrow_isNotReportedUnreachable() {
        // Reachability counts any instantiation, not just `throw new` — the orElseThrow idiom
        // instantiates inside a lambda and must not read as "nothing can reach the mapping".
        Map<String, String> files = Map.of(
                "src/main/java/A.java", """
                        package a;
                        import java.util.Optional;
                        public class A {
                            public String find(Optional<String> v) {
                                return v.orElseThrow(() -> new BookNotFoundException("missing"));
                            }
                        }
                        """,
                "src/main/java/BookNotFoundException.java", """
                        package a;
                        public class BookNotFoundException extends RuntimeException {
                            public BookNotFoundException(String m) { super(m); }
                        }
                        """,
                "src/main/java/H.java", """
                        package a;
                        import org.springframework.web.bind.annotation.*;
                        @ControllerAdvice
                        public class H {
                            @ExceptionHandler(BookNotFoundException.class)
                            public String h(BookNotFoundException e) { return e.getMessage(); }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("never instantiated"));
    }

    @Test
    void entityWithoutPersistenceCall_flagsMissingPersistence() {
        Map<String, String> files = Map.of(
                "src/main/java/Order.java", """
                        package a;
                        import jakarta.persistence.Entity;
                        @Entity
                        public class Order { private Long id; public Long getId() { return id; } }
                        """,
                "src/main/java/Svc.java", """
                        package a;
                        public class Svc {
                            public Order make() { return new Order(); } // never saved
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .anyMatch(o -> o.contains("no save/persist call"));
    }

    @Test
    void entityWithSaveCall_doesNotFlagMissingPersistence() {
        Map<String, String> files = Map.of(
                "src/main/java/Order.java", """
                        package a;
                        import jakarta.persistence.Entity;
                        @Entity
                        public class Order { private Long id; }
                        """,
                "src/main/java/Svc.java", """
                        package a;
                        public class Svc {
                            private final OrderRepository repo;
                            public Svc(OrderRepository repo) { this.repo = repo; }
                            public Order make() { return repo.save(new Order()); }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("no save/persist call"));
    }

    @Test
    void unreadParameter_isFlagged() {
        Map<String, String> files = Map.of("src/main/java/A.java", """
                package a;
                public class A {
                    public int doubleIt(int value) { return 2; } // value ignored
                }
                """);
        assertThat(analyzer.analyze(files))
                .anyMatch(o -> o.contains("value") && o.contains("never reads"));
    }

    @Test
    void requestBodyWithoutConstraintsOrTrigger_flagsMissingValidationSurface() {
        Map<String, String> files = Map.of(
                "src/main/java/BookRequest.java", """
                        package a;
                        public class BookRequest {
                            private String isbn;
                            private String title;
                            public String getIsbn() { return isbn; }
                            public String getTitle() { return title; }
                            public void setIsbn(String isbn) { this.isbn = isbn; }
                            public void setTitle(String title) { this.title = title; }
                        }
                        """,
                "src/main/java/BookController.java", """
                        package a;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class BookController {
                            @PostMapping("/books")
                            public String create(@RequestBody BookRequest request) { return request.getIsbn(); }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .anyMatch(o -> o.contains("BookRequest") && o.contains("NO declarative validation surface"));
    }

    @Test
    void constraintsWithoutTrigger_flagsUnenforcedConstraints() {
        Map<String, String> files = Map.of(
                "src/main/java/BookRequest.java", """
                        package a;
                        import jakarta.validation.constraints.*;
                        public class BookRequest {
                            @Pattern(regexp = "\\\\d{13}")
                            private String isbn;
                            @NotBlank
                            private String title;
                        }
                        """,
                "src/main/java/BookController.java", """
                        package a;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class BookController {
                            @PostMapping("/books")
                            public String create(@RequestBody BookRequest request) { return "ok"; }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .anyMatch(o -> o.contains("BookRequest") && o.contains("not enforced"));
    }

    @Test
    void triggerWithoutConstraints_flagsEmptyConstraintSet() {
        Map<String, String> files = Map.of(
                "src/main/java/BookRequest.java", """
                        package a;
                        public class BookRequest { private String isbn; }
                        """,
                "src/main/java/BookController.java", """
                        package a;
                        import jakarta.validation.Valid;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class BookController {
                            @PostMapping("/books")
                            public String create(@Valid @RequestBody BookRequest request) { return "ok"; }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .anyMatch(o -> o.contains("BookRequest") && o.contains("constraint set is empty"));
    }

    @Test
    void constraintsWithTrigger_staysSilent() {
        Map<String, String> files = Map.of(
                "src/main/java/BookRequest.java", """
                        package a;
                        import jakarta.validation.constraints.*;
                        public class BookRequest {
                            @Pattern(regexp = "\\\\d{13}")
                            private String isbn;
                            @NotBlank
                            private String title;
                        }
                        """,
                "src/main/java/BookController.java", """
                        package a;
                        import jakarta.validation.Valid;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class BookController {
                            @PostMapping("/books")
                            public String create(@Valid @RequestBody BookRequest request) { return request.toString(); }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("validation") || o.contains("constraint"));
    }

    @Test
    void classLevelValidatedAnnotation_countsAsTrigger() {
        Map<String, String> files = Map.of(
                "src/main/java/BookRequest.java", """
                        package a;
                        import jakarta.validation.constraints.NotBlank;
                        public class BookRequest {
                            @NotBlank
                            private String title;
                        }
                        """,
                "src/main/java/BookController.java", """
                        package a;
                        import org.springframework.validation.annotation.Validated;
                        import org.springframework.web.bind.annotation.*;
                        @Validated
                        @RestController
                        public class BookController {
                            @PostMapping("/books")
                            public String create(@RequestBody BookRequest request) { return request.toString(); }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("not enforced"));
    }

    @Test
    void requestBodyTypeNotInFileSet_staysSilent() {
        Map<String, String> files = Map.of("src/main/java/BookController.java", """
                package a;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class BookController {
                    @PostMapping("/books")
                    public String create(@RequestBody ExternalDto request) { return request.toString(); }
                }
                """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("validation"));
    }

    @Test
    void noRequestBodyBindings_yieldsNoValidationObservations() {
        Map<String, String> files = Map.of("src/main/java/Plain.java", """
                package a;
                public class Plain {
                    private String isbn;
                    public String read(String input) { return input + isbn; }
                }
                """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("validation surface") || o.contains("constraint"));
    }

    @Test
    void emptyOrNonJava_returnsEmpty() {
        assertThat(analyzer.analyze(null)).isEmpty();
        assertThat(analyzer.analyze(Map.of())).isEmpty();
        assertThat(analyzer.analyze(Map.of("README.md", "not java"))).isEmpty();
    }
}
