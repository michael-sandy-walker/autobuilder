package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodySynthesizerTest {

    @TempDir
    Path tempDir;

    private void model(String name, String content) throws IOException {
        Path p = tempDir.resolve("src/main/java/com/example/" + name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private void controller(String name, String content) throws IOException {
        Path p = tempDir.resolve("src/main/java/com/example/" + name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    /**
     * T1-FV, verbatim from run {@code 2026-08-28_21-47-39}: the artifact scored 23/23 and was
     * refused closure by ONE contradiction, because the body was derived from what the artifact
     * STORES rather than from what its interface ACCEPTS. The handler declares keyed {@code Long}
     * values; the entity carries {@code boolean returned}; the request was refused with 400 before
     * the requirement was reached and the refusal was recorded as the artifact failing.
     *
     * <p>RED against the pre-fix code, which emitted {@code "returned":true} and drew the 400.
     * Verified on the preserved artifact itself: the old body draws 400, this one draws the
     * specified {@code 404 {"message":"Book not found"}}.
     */
    @Test
    void dropsAComponentTheHandlersDeclarationWillNotBind() throws IOException {
        model("Loan.java", """
                public class Loan {
                    private Long id;
                    private Long bookId;
                    private Long memberId;
                    private boolean returned;
                }
                """);
        controller("LoanController.java", """
                @RestController
                @RequestMapping("/loans")
                public class LoanController {
                    @PostMapping
                    public ResponseEntity<Loan> create(@RequestBody Map<String, Long> body) {
                        return null;
                    }
                }
                """);

        List<String> bodies = RequestBodySynthesizer.candidateBodies("/loans", tempDir);

        assertThat(bodies.get(0))
                .contains("\"bookId\":1")
                .contains("\"memberId\":1")
                .doesNotContain("returned");
    }

    /**
     * The same reading drops a server-assigned identifier STRUCTURALLY when the declaration is
     * narrower than the entity -- here the handler takes keyed strings, so a numeric {@code id}
     * cannot bind. The ticket closed the name-based route ("extending the stripped-name list would
     * hardcode domain vocabulary"); this reaches the same result by reading the declaration, and
     * it generalises to any component the interface refuses rather than to the one field somebody
     * remembered to name.
     */
    @Test
    void aNarrowerDeclarationExcludesTheAssignedIdentifierWithoutNamingIt() throws IOException {
        model("Book.java", """
                public class Book {
                    private Long id;
                    private String title;
                    private String isbn;
                }
                """);
        controller("BookController.java", """
                @RestController
                @RequestMapping("/books")
                public class BookController {
                    @PostMapping
                    public ResponseEntity<Book> create(@RequestBody Map<String, String> body) {
                        return null;
                    }
                }
                """);

        assertThat(RequestBodySynthesizer.candidateBodies("/books", tempDir).get(0))
                .contains("\"title\":\"value\"")
                .contains("\"isbn\":\"value\"")
                .doesNotContain("\"id\"");
    }

    /**
     * No readable declaration means today's behaviour, deliberately. Declining here would trade a
     * wrong body for no probe at all, and no probe reads as success -- the failure mode T1-FW
     * nearly reintroduced. The abstention is logged instead, so its population can be counted
     * before anyone makes it binding.
     */
    @Test
    void anUnreadableDeclarationLeavesTheDerivedBodyAlone() throws IOException {
        model("Widget.java", """
                public class Widget {
                    private Long id;
                    private String name;
                    private boolean active;
                }
                """);
        // No controller at all: nothing declares what /widgets accepts.

        assertThat(RequestBodySynthesizer.candidateBodies("/widgets", tempDir).get(0))
                .contains("\"name\":\"value\"")
                .contains("\"active\":true");
    }

    @Test
    void derivesBodyFromMatchingModelSource() throws IOException {
        model("Order.java", """
                public class Order {
                    private Long id;
                    private String name;
                    private boolean active;
                    private java.time.LocalDate due;
                    private Member member; // nested type — must be omitted, never guessed
                }
                """);

        List<String> bodies = RequestBodySynthesizer.candidateBodies("/orders", tempDir);

        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(0))
                .contains("\"id\":1")
                .contains("\"name\":\"value\"")
                .contains("\"active\":true")
                .contains("\"due\":\"2026-01-01\"")
                .doesNotContain("member");
        assertThat(bodies.get(1)).isEqualTo("{}");
    }

    @Test
    void naivePluralStripping_matchesEsForm() throws IOException {
        model("Branch.java", "public class Branch { private String city; }");

        List<String> bodies = RequestBodySynthesizer.candidateBodies("/branches", tempDir);

        assertThat(bodies.get(0)).contains("\"city\":\"value\"");
    }

    @Test
    void fallbackOnlyWithoutMatchingModel() {
        assertThat(RequestBodySynthesizer.candidateBodies("/unknowns", tempDir))
                .containsExactly("{}");
    }

    @Test
    void pathVariableTailUsesLastStaticSegment() throws IOException {
        model("Item.java", "public class Item { private String label; }");

        // /items/{}/copies — last STATIC segment is "copies"; no Copy model exists → fallback only
        assertThat(RequestBodySynthesizer.candidateBodies("/items/{}/copies", tempDir))
                .containsExactly("{}");
    }

    @Test
    void modelWithOnlyUnknownTypes_fallsBack() throws IOException {
        model("Bundle.java", "public class Bundle { private Widget widget; private Gadget gadget; }");

        assertThat(RequestBodySynthesizer.candidateBodies("/bundles", tempDir))
                .containsExactly("{}");
    }

    @Test
    void nullOrMissingRoot_neverThrows() {
        assertThat(RequestBodySynthesizer.candidateBodies("/orders", null)).containsExactly("{}");
        assertThat(RequestBodySynthesizer.candidateBodies(null, tempDir)).containsExactly("{}");
    }

    // ---- T4-DB: acceptableBody, the stricter sibling ------------------------------------------

    @Test
    void acceptableBodyAppliesTheArtifactsOwnConstraintsToTheValues() throws IOException {
        model("Book.java", "public class Book { private String title; private String isbn; }");
        model("BookService.java", """
                import java.util.regex.Pattern;
                public class BookService {
                    private static final Pattern ISBN_PATTERN = Pattern.compile("\\\\d{13}");
                    public void create(String isbn) {
                        if (!ISBN_PATTERN.matcher(isbn).matches()) throw new IllegalArgumentException();
                    }
                }
                """);

        assertThat(RequestBodySynthesizer.acceptableBody("/books", tempDir))
                .contains("{\"title\":\"value\",\"isbn\":\"0000000000000\"}");
    }

    @Test
    void oneUnsatisfiableConstraintFailsTheWholeBody() throws IOException {
        // A body carrying one value the application rejects is not a body that can create
        // anything, however correct its other fields are — so this must be empty, not partial.
        model("Book.java", "public class Book { private String title; private String isbn; }");
        model("BookService.java", """
                import java.util.regex.Pattern;
                public class BookService {
                    private static final Pattern ISBN_PATTERN = Pattern.compile("(alpha|beta)+");
                    public void create(String isbn) {
                        if (!ISBN_PATTERN.matcher(isbn).matches()) throw new IllegalArgumentException();
                    }
                }
                """);

        assertThat(RequestBodySynthesizer.acceptableBody("/books", tempDir)).isEmpty();
    }

    @Test
    void aConstrainedNonTextualFieldIsDeclinedRatherThanShaped() throws IOException {
        // Only a textual value can be shaped to a pattern or a length; anything else is a shape
        // this class does not model, and guessing would send a body that gets refused.
        model("Book.java", "public class Book { private int copies; }");
        model("BookService.java", """
                public class BookService {
                    public void create(String copies) {
                        if (copies.length() > 3) throw new IllegalArgumentException();
                    }
                }
                """);

        assertThat(RequestBodySynthesizer.acceptableBody("/books", tempDir)).isEmpty();
    }

    @Test
    void acceptableBodyDeclinesWhenNoModelSourceMatchesThePath() {
        // candidateBodies falls back to {}; acceptableBody must not, because {} creates nothing.
        assertThat(RequestBodySynthesizer.acceptableBody("/unknowns", tempDir)).isEmpty();
    }

    @Test
    void anUnconstrainedProjectStillYieldsPlaceholders() throws IOException {
        // No discovered constraint is not evidence of no validation — the probe may still be
        // refused, and BootGate reads that refusal as an abstention rather than a contradiction.
        model("Book.java", "public class Book { private String title; }");

        assertThat(RequestBodySynthesizer.acceptableBody("/books", tempDir))
                .contains("{\"title\":\"value\"}");
    }
}
