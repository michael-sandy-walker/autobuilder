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
