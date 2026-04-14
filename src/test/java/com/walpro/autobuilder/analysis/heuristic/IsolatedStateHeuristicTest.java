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

class IsolatedStateHeuristicTest {

    private final IsolatedStateHeuristic heuristic = new IsolatedStateHeuristic();

    private static final EvaluationOutcome ACCEPTED =
            new EvaluationOutcome(0.80, MutationAcceptanceState.ACCEPTED,
                    GoalProgressState.PROGRESSING, "", null);

    private static final String BOOK_CONTROLLER = """
            @RestController
            @RequestMapping("/books")
            public class BookController {
                private final List<Book> books = new CopyOnWriteArrayList<>();

                @GetMapping
                public List<Book> getAll() { return books; }

                @PostMapping
                public ResponseEntity<Book> addBook(@RequestBody Book book) {
                    books.add(book);
                    return ResponseEntity.status(201).body(book);
                }
            }
            """;

    private static final String MEMBER_CONTROLLER = """
            @RestController
            @RequestMapping("/members")
            public class MemberController {
                private final List<Member> members = new CopyOnWriteArrayList<>();

                @PostMapping
                public ResponseEntity<Member> addMember(@RequestBody Member member) {
                    members.add(member);
                    return ResponseEntity.status(201).body(member);
                }
            }
            """;

    // ── Null / missing context ────────────────────────────────────────────────

    @Test
    void nullContext_returnsNone() {
        assertThat(heuristic.evaluate(null, ACCEPTED).level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void noDiff_returnsNone() {
        CycleContextData ctx = CycleContextData.builder().build();
        assertThat(heuristic.evaluate(ctx, ACCEPTED).level()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void noPreviousOutput_returnsNone() {
        CycleContextData ctx = CycleContextData.builder()
                .projectDiff(new ProjectDiffResult(null,
                        Map.of("src/LoanController.java", "irrelevant"), Set.of()))
                .build();
        assertThat(heuristic.evaluate(ctx, ACCEPTED).level()).isEqualTo(ConfidenceLevel.NONE);
    }

    // ── (a) Isolated shadow → REJECTED ───────────────────────────────────────

    @Test
    void shadowList_forTypeOwnedByAnotherController_isRejected() {
        // LoanController declares its own List<Book> and List<Member>
        // even though BookController and MemberController already own them.
        String loanController = """
                @RestController
                @RequestMapping("/loans")
                public class LoanController {
                    private final List<Book> books = new CopyOnWriteArrayList<>();
                    private final List<Member> members = new CopyOnWriteArrayList<>();

                    @PostMapping
                    public ResponseEntity<?> createLoan(@RequestBody LoanRequest req) {
                        Book book = books.stream()
                                .filter(b -> b.getId().equals(req.getBookId()))
                                .findFirst().orElse(null);
                        if (book == null) return ResponseEntity.notFound().build();
                        return ResponseEntity.ok().build();
                    }
                }
                """;

        CycleContextData ctx = CycleContextData.builder()
                .previousOutput(Map.of(
                        "src/BookController.java", BOOK_CONTROLLER,
                        "src/MemberController.java", MEMBER_CONTROLLER
                ))
                .projectDiff(new ProjectDiffResult(null,
                        Map.of("src/LoanController.java", loanController), Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, ACCEPTED);

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.CRITICAL);
        assertThat(opinion.mutationState()).isEqualTo(MutationAcceptanceState.REJECTED);
        assertThat(opinion.goalProgress()).isEqualTo(GoalProgressState.STAGNATING);
        assertThat(opinion.rationale()).contains("List<Book>").contains("BookController");
    }

    // ── (b) Injected service field → abstain ─────────────────────────────────

    @Test
    void injectedServiceField_doesNotTrigger() {
        // LoanController uses a service injected via constructor — no new list declaration
        String loanController = """
                @RestController
                @RequestMapping("/loans")
                public class LoanController {
                    private final LibraryService libraryService;

                    public LoanController(LibraryService libraryService) {
                        this.libraryService = libraryService;
                    }

                    @PostMapping
                    public ResponseEntity<?> createLoan(@RequestBody LoanRequest req) {
                        Book book = libraryService.findBook(req.getBookId());
                        if (book == null) return ResponseEntity.notFound().build();
                        return ResponseEntity.ok().build();
                    }
                }
                """;

        CycleContextData ctx = CycleContextData.builder()
                .previousOutput(Map.of(
                        "src/BookController.java", BOOK_CONTROLLER,
                        "src/MemberController.java", MEMBER_CONTROLLER
                ))
                .projectDiff(new ProjectDiffResult(null,
                        Map.of("src/LoanController.java", loanController), Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, ACCEPTED);

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    // ── (c) Same-class list redeclaration → abstain ───────────────────────────

    @Test
    void sameClassRedeclaration_doesNotTrigger() {
        // BookController is being modified — it's adding its own List<Book>.
        // Owner of List<Book> is already BookController, so no shadow.
        String updatedBookController = """
                @RestController
                @RequestMapping("/books")
                public class BookController {
                    private final List<Book> books = new CopyOnWriteArrayList<>();

                    @GetMapping("/{id}")
                    public ResponseEntity<Book> getById(@PathVariable Long id) {
                        return books.stream().filter(b -> b.getId().equals(id))
                                .findFirst()
                                .map(ResponseEntity::ok)
                                .orElse(ResponseEntity.notFound().build());
                    }
                }
                """;

        CycleContextData ctx = CycleContextData.builder()
                .previousOutput(Map.of(
                        "src/BookController.java", BOOK_CONTROLLER
                ))
                .projectDiff(new ProjectDiffResult(null,
                        Map.of("src/BookController.java", updatedBookController), Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, ACCEPTED);

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }

    // ── Non-controller file with list init → abstain ──────────────────────────

    @Test
    void nonControllerFile_withListInit_doesNotTrigger() {
        // A service class that has its own list is fine — only controllers are checked
        String libraryService = """
                @Service
                public class LibraryService {
                    private final List<Book> books = new CopyOnWriteArrayList<>();

                    public void addBook(Book book) { books.add(book); }
                }
                """;

        CycleContextData ctx = CycleContextData.builder()
                .previousOutput(Map.of("src/BookController.java", BOOK_CONTROLLER))
                .projectDiff(new ProjectDiffResult(null,
                        Map.of("src/LibraryService.java", libraryService), Set.of()))
                .build();

        HeuristicOpinion opinion = heuristic.evaluate(ctx, ACCEPTED);

        assertThat(opinion.level()).isEqualTo(ConfidenceLevel.NONE);
    }
}
