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
}
