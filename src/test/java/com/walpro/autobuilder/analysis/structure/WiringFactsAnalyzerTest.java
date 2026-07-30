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
    void thrownAndMappedType_emitsPositiveCoverageFactWithStatus() {
        // Run 18 (2026-07-06): gap-only facts left the checker guessing on wiring that EXISTS —
        // it marked a working 422 mapping unmet and the banked ✗ walled the subgoal into a root
        // give-up. A thrown type covered by a handler must yield a positive fact naming the
        // status expression the handler declares.
        Map<String, String> files = Map.of(
                "src/main/java/LoanService.java", """
                        package a;
                        public class LoanService {
                            public String loan(int active) {
                                if (active >= 3) throw new MaxActiveLoansExceededException("limit");
                                return "ok";
                            }
                        }
                        """,
                "src/main/java/MaxActiveLoansExceededException.java", """
                        package a;
                        public class MaxActiveLoansExceededException extends RuntimeException {
                            public MaxActiveLoansExceededException(String m) { super(m); }
                        }
                        """,
                "src/main/java/H.java", """
                        package a;
                        import org.springframework.http.*;
                        import org.springframework.web.bind.annotation.*;
                        @ControllerAdvice
                        public class H {
                            @ExceptionHandler(MaxActiveLoansExceededException.class)
                            public ResponseEntity<String> h(MaxActiveLoansExceededException e) {
                                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
                            }
                        }
                        """);
        List<String> obs = analyzer.analyze(files);
        assertThat(obs).anyMatch(o -> o.contains("WITH a status mapping in place")
                && o.contains("MaxActiveLoansExceededException → HttpStatus.UNPROCESSABLE_ENTITY"));
        // The wiring is complete on both sides — neither gap fact may fire for this type.
        assertThat(obs).noneMatch(o -> o.contains("not covered by any mapped type"));
        assertThat(obs).noneMatch(o -> o.contains("never instantiated"));
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
    void projectDeclaredConstraint_countsAsAValidationSurface() {
        // T4-BM discovered-vocabulary slice: a project extending the constraint vocabulary via
        // the ecosystem's own meta-annotation (@Constraint) must not be reported as having "no
        // field constraint annotations" — that fact would be false.
        Map<String, String> files = Map.of(
                "src/main/java/ValidIsbn.java", """
                        package a;
                        import jakarta.validation.Constraint;
                        import java.lang.annotation.*;
                        @Constraint(validatedBy = {})
                        @Target(ElementType.FIELD)
                        @Retention(RetentionPolicy.RUNTIME)
                        public @interface ValidIsbn {
                            String message() default "invalid isbn";
                        }
                        """,
                "src/main/java/BookRequest.java", """
                        package a;
                        public class BookRequest {
                            @ValidIsbn
                            private String isbn;
                        }
                        """,
                "src/main/java/BookController.java", """
                        package a;
                        import org.springframework.web.bind.annotation.*;
                        import jakarta.validation.Valid;
                        @RestController
                        public class BookController {
                            @PostMapping("/books")
                            public String create(@Valid @RequestBody BookRequest request) { return "ok"; }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("declares no field constraint annotations"))
                .noneMatch(o -> o.contains("NO declarative validation surface"));
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

    // T3-BX: the fact reaching the agent that can act on it. Measured across six run corpora, the
    // analyzer produced a status-path fact on every one while peerAnnotationAnomalies -- the only
    // analyzer path into the evolution prompt -- produced none on any of them. The rule was right
    // and its output went only to the verifier.

    private static final String CONSTRAINED_BODY = """
            package a;
            import jakarta.validation.constraints.NotBlank;
            public class Book {
                @NotBlank private String title;
                public String getTitle() { return title; }
            }
            """;

    private static final String UNCONSTRAINED_BODY = """
            package a;
            public class Member {
                private String name;
                public String getName() { return name; }
            }
            """;

    private static String controllerBinding(String type, boolean validated) {
        return """
                package a;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;
                import jakarta.validation.Valid;
                @RestController
                public class %sController {
                    @PostMapping
                    public String create(%s@RequestBody %s body) { return body.toString(); }
                }
                """.formatted(type, validated ? "@Valid " : "", type);
    }

    // ---- T4-DC: an unmapped throw must reach EVOLUTION, not only the criteria verifier ----

    private static final String SERVICE_THROWING_RUNTIME = """
            package app;
            public class LoanService {
                public Object createLoan(Object loan) {
                    if (loan == null) throw new RuntimeException("Book not found");
                    throw new RuntimeException("Book is already on loan");
                }
            }
            """;

    /** The 14/23 shape: catches that can never match what the service throws. */
    private static final String CONTROLLER_CATCHING_SUBCLASSES = """
            package app;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            public class LoanController {
                private LoanService loanService;
                public ResponseEntity<Object> createLoan(Object loan) {
                    try {
                        return new ResponseEntity<>(loanService.createLoan(loan), HttpStatus.CREATED);
                    } catch (IllegalStateException e) {
                        return new ResponseEntity<>(HttpStatus.CONFLICT);
                    } catch (IllegalArgumentException e) {
                        return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
                    }
                }
            }
            """;

    /** The 23/23 shape: a named type, caught where it is raised, mapped to its status. */
    private static final String SERVICE_THROWING_NAMED = """
            package app;
            public class LoanService {
                public Object createLoan(Object loan) { throw new NotFoundException("Book not found"); }
            }
            """;

    private static final String CONTROLLER_CATCHING_NAMED = """
            package app;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            public class LoanController {
                private LoanService loanService;
                public ResponseEntity<Object> createLoan(Object loan) {
                    try {
                        return ResponseEntity.status(HttpStatus.CREATED).body(loanService.createLoan(loan));
                    } catch (NotFoundException e) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
                    }
                }
            }
            """;

    @Test
    void anUnmappedThrowIsReportedBeforeAnyHandlerExists() {
        // Run 2026-07-30_18-39-00 threw RuntimeException for six domain errors from early on, but
        // this method stayed silent until a GlobalExceptionHandler existed to satisfy its gate —
        // which happened at CYCLE 24 OF 25. The fact then reached an evolution prompt whose subgoal
        // was "spec-named endpoint(s) fail at runtime: GET /loans answers 500", one cycle before the
        // deadline. It was not ignored; it arrived too late to act on.
        Map<String, String> files = Map.of(
                "src/main/java/LoanService.java", SERVICE_THROWING_RUNTIME);

        assertThat(analyzer.statusRiskFacts(files))
                .anyMatch(f -> f.contains("no HTTP status mapping was found")
                        && f.contains("surface as 500s"));
    }

    @Test
    void catchesThatCannotMatchWhatIsThrownAreReportedAsUncovered() {
        Map<String, String> files = Map.of(
                "src/main/java/LoanService.java", SERVICE_THROWING_RUNTIME,
                "src/main/java/LoanController.java", CONTROLLER_CATCHING_SUBCLASSES);

        assertThat(analyzer.statusRiskFacts(files))
                .anyMatch(f -> f.contains("RuntimeException") && f.contains("not covered"));
    }

    @Test
    void mappingInAControllerCatchCountsAsMappingAtAll() {
        // The best artifact in the corpus (2026-07-24, SMOKE 23/23) declares NO advice class and no
        // @ResponseStatus: it throws named domain types and catches each one in the controller,
        // returning the status beside the logic that raised it. Counting only annotations would
        // report that artifact as having no status mapping whatsoever.
        Map<String, String> files = Map.of(
                "src/main/java/LoanService.java", SERVICE_THROWING_NAMED,
                "src/main/java/LoanController.java", CONTROLLER_CATCHING_NAMED);

        assertThat(analyzer.statusRiskFacts(files))
                .noneMatch(f -> f.contains("no HTTP status mapping was found"));
        assertThat(analyzer.statusRiskFacts(files))
                .noneMatch(f -> f.contains("NotFoundException") && f.contains("not covered"));
    }

    @Test
    void aCatchThatSwallowsWithoutAStatusMapsNothing() {
        String swallowing = """
                package app;
                public class LoanController {
                    private LoanService loanService;
                    public Object createLoan(Object loan) {
                        try { return loanService.createLoan(loan); }
                        catch (NotFoundException e) { return null; }
                    }
                }
                """;
        Map<String, String> files = Map.of(
                "src/main/java/LoanService.java", SERVICE_THROWING_NAMED,
                "src/main/java/LoanController.java", swallowing);

        assertThat(analyzer.statusRiskFacts(files))
                .anyMatch(f -> f.contains("no HTTP status mapping was found"));
    }

    @Test
    void bothConsumersReceiveTheSameWording() {
        // analyze() feeds the criteria verifier and statusRiskFacts() feeds the evolution prompt.
        // The fact existed in the first and not the second, which is the split T3-BX was filed to
        // close; a single constant is what stops it reopening.
        Map<String, String> files = Map.of(
                "src/main/java/LoanService.java", SERVICE_THROWING_RUNTIME);

        String fromVerifier = analyzer.analyze(files).stream()
                .filter(f -> f.contains("no HTTP status mapping")).findFirst().orElseThrow();
        String fromPrompt = analyzer.statusRiskFacts(files).stream()
                .filter(f -> f.contains("no HTTP status mapping")).findFirst().orElseThrow();

        assertThat(fromPrompt).isEqualTo(fromVerifier);
    }

    @Test
    void aProjectThatThrowsNothingGetsNoOpinion() {
        Map<String, String> files = Map.of("src/main/java/Plain.java", """
                package app;
                public class Plain { public int add(int a, int b) { return a + b; } }
                """);

        assertThat(analyzer.statusRiskFacts(files))
                .noneMatch(f -> f.contains("surface as 500s"));
    }

    @Test
    void statusRiskFacts_carryTheValidationGapThatMakesAStatusWrong() {
        Map<String, String> files = Map.of(
                "src/main/java/Member.java", UNCONSTRAINED_BODY,
                "src/main/java/MemberController.java", controllerBinding("Member", true));

        assertThat(analyzer.statusRiskFacts(files))
                .anyMatch(f -> f.contains("Member") && f.contains("no field constraint annotations"));
    }

    @Test
    void aTypeWhosePeerIsConstrainedIsNamedAsTheOddOneOut() {
        // The measured yield: run 2026-07-27_14-42-19 scored 21/23 and its one status failure was
        // POST /members answering 201, because Member carried no constraint while Book did. The bare
        // absence leaves the model free to read it as intentional; the comparison does not.
        Map<String, String> files = Map.of(
                "src/main/java/Book.java", CONSTRAINED_BODY,
                "src/main/java/Member.java", UNCONSTRAINED_BODY,
                "src/main/java/BookController.java", controllerBinding("Book", true),
                "src/main/java/MemberController.java", controllerBinding("Member", true));

        assertThat(analyzer.statusRiskFacts(files))
                .anyMatch(f -> f.contains("Member")
                        && f.contains("only request-body type here without them")
                        && f.contains("Book"));
    }

    @Test
    void withNoConstrainedPeerTheAbsenceIsReportedWithoutClaimingItIsAnomalous() {
        // Every body type lacking constraints is the run 2026-07-27_13-29-30 shape. It is still a
        // gap and still reported — but calling it an odd one out would be false.
        Map<String, String> files = Map.of(
                "src/main/java/Member.java", UNCONSTRAINED_BODY,
                "src/main/java/MemberController.java", controllerBinding("Member", true));

        assertThat(analyzer.statusRiskFacts(files))
                .anyMatch(f -> f.contains("Member"))
                .noneMatch(f -> f.contains("only request-body type"));
    }

    @Test
    void statusRiskFacts_leaveOutTheFactsThatAreNotAboutStatus() {
        // A subset by design: a stub body is a real fact and not a status-path one, and shipping all
        // of analyze() into the prompt would be the raw-payload passthrough the audit removed.
        Map<String, String> files = Map.of(
                "src/main/java/Member.java", UNCONSTRAINED_BODY,
                "src/main/java/MemberController.java", controllerBinding("Member", true),
                "src/main/java/Stubby.java", """
                        package a;
                        public class Stubby {
                            public String compute() { return null; }
                        }
                        """);

        assertThat(analyzer.analyze(files)).anyMatch(f -> f.contains("stub"));
        assertThat(analyzer.statusRiskFacts(files)).noneMatch(f -> f.contains("stub"));
    }

    @Test
    void aConsistentlyValidatedArtifactProducesNoStatusRiskFacts() {
        // Silence on a healthy artifact is what keeps this block conditional rather than a payload.
        Map<String, String> files = Map.of(
                "src/main/java/Book.java", CONSTRAINED_BODY,
                "src/main/java/BookController.java", controllerBinding("Book", true));

        assertThat(analyzer.statusRiskFacts(files)).isEmpty();
    }

    @Test
    void aProjectWithNoRequestBodiesGetsNoOpinion() {
        Map<String, String> files = Map.of("src/main/java/Plain.java", """
                package a;
                public class Plain {
                    public int add(int a, int b) { return a + b; }
                }
                """);
        assertThat(analyzer.statusRiskFacts(files)).isEmpty();
    }

    @Test
    void emptyOrNonJava_returnsEmpty() {
        assertThat(analyzer.analyze(null)).isEmpty();
        assertThat(analyzer.analyze(Map.of())).isEmpty();
        assertThat(analyzer.analyze(Map.of("README.md", "not java"))).isEmpty();
    }
}
