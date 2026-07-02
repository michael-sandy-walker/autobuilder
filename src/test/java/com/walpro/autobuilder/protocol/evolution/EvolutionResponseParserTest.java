package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class EvolutionResponseParserTest {

    @Test
    void shouldParseValidResponse() {
        String response = """
            ```java [src/test/java/com/example/agent/OpenAIAgentTest.java]
            package com.example.agent;
            
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            
            class OpenAIAgentTest {
            
                @Test
                void testInteractWithLLM() {
                    OpenAIAgent agent = new OpenAIAgent();
                    String input = "Hello, LLM!";
                    String response = agent.interactWithLLM(input);
                    assertNotNull(response);
                    assertFalse(response.isEmpty());
                }
            }
            ```
            #Delete [src/main/java/com/obsolete/OldFile.java]
            """;

        EvolutionResponseParser parser = new EvolutionResponseParser();
        EvolutionOutcome outcome = parser.parse(response);

        assertThat(outcome.addedOrChangedFiles())
                .anyMatch(f -> f.path().endsWith("OpenAIAgentTest.java"));

        assertThat(outcome.deletedFiles())
                .contains("src/main/java/com/obsolete/OldFile.java");
    }

    @Test
    void parseExtractsChangedAndDeletedFiles() {
        String content = """
                ```java [src/main/java/com/example/Hello.java]
                public class Hello {
                    public static void main(String[] args) {}
                }
                ```
                
                #Delete [src/test/java/com/example/HelloTest.java]
                """;

        EvolutionResponseParser parser = new EvolutionResponseParser();
        EvolutionOutcome outcome = parser.parse(content);

        List<FileMutation> changed = outcome.addedOrChangedFiles();
        List<String> deleted = outcome.deletedFiles();

        assertEquals(1, changed.size(), "Exactly one changed file should be detected");
        assertEquals("src/main/java/com/example/Hello.java", changed.getFirst().path());
        // Trim just to be robust against leading/trailing whitespace
        org.junit.jupiter.api.Assertions.assertTrue(
                changed.getFirst().content().contains("public class Hello"),
                "File content should contain the Java class body"
        );

        assertEquals(1, deleted.size(), "Exactly one deleted file should be detected");
        assertEquals("src/test/java/com/example/HelloTest.java", deleted.getFirst());

        String summary = outcome.summary();
        assertEquals("Parsed 1 changed file(s), 1 deleted file(s).", summary);
    }

    @Test
    void parseEmptyContentYieldsEmptyListsAndZeroSummary() {
        EvolutionResponseParser parser = new EvolutionResponseParser();
        EvolutionOutcome outcome = parser.parse("");

        assertEquals(0, outcome.addedOrChangedFiles().size());
        assertEquals(0, outcome.deletedFiles().size());
        assertEquals("Parsed 0 changed file(s), 0 deleted file(s).", outcome.summary());
    }

    @Test
    void directoryPathWithoutExtensionIsSkipped() {
        // LLM returns a package directory path instead of a file path — must be ignored
        String content = """
                ```java [src/main/java/com/example/booklendingapi]
                package com.example.booklendingapi;
                public class BookLendingApiApplication {}
                ```
                """;

        EvolutionResponseParser parser = new EvolutionResponseParser();
        EvolutionOutcome outcome = parser.parse(content);

        assertThat(outcome.addedOrChangedFiles()).isEmpty();
        assertEquals("Parsed 0 changed file(s), 0 deleted file(s).", outcome.summary());
    }

    @Test
    void mixOfValidAndDirectoryPathsKeepsOnlyValidOnes() {
        String content = """
                ```java [src/main/java/com/example/booklendingapi]
                package com.example.booklendingapi;
                public class App {}
                ```

                ```java [src/main/java/com/example/booklendingapi/model/Book.java]
                package com.example.booklendingapi.model;
                public class Book {}
                ```
                """;

        EvolutionResponseParser parser = new EvolutionResponseParser();
        EvolutionOutcome outcome = parser.parse(content);

        assertThat(outcome.addedOrChangedFiles()).hasSize(1);
        assertThat(outcome.addedOrChangedFiles().getFirst().path())
                .isEqualTo("src/main/java/com/example/booklendingapi/model/Book.java");
    }

    /**
     * T4-BU: a test-suffixed file under src/main/java whose content imports a test framework is
     * relocated to src/test/java at parse time (2026-07-03 run 2: misplaced tests hard-failed the
     * main compile probe every cycle and the whole mutation was rejected, impl included).
     */
    @Test
    void misplacedTestSourceIsRelocatedToTestRoot() {
        String content = """
                ```java [src/main/java/com/example/library/model/BookControllerTest.java]
                package com.example.library.model;
                import org.junit.jupiter.api.Test;
                import org.mockito.Mockito;
                class BookControllerTest {
                    @Test void listsBooks() {}
                }
                ```
                """;

        EvolutionOutcome outcome = new EvolutionResponseParser().parse(content);

        assertThat(outcome.addedOrChangedFiles()).hasSize(1);
        assertThat(outcome.addedOrChangedFiles().getFirst().path())
                .isEqualTo("src/test/java/com/example/library/model/BookControllerTest.java");
    }

    @Test
    void testSuffixedMainClassWithoutTestFrameworkImportsKeepsItsPath() {
        // A legitimate main class that merely ends in "Test" must not be moved.
        String content = """
                ```java [src/main/java/com/example/library/model/LoadTest.java]
                package com.example.library.model;
                public class LoadTest {
                    public void run() {}
                }
                ```
                """;

        EvolutionOutcome outcome = new EvolutionResponseParser().parse(content);

        assertThat(outcome.addedOrChangedFiles().getFirst().path())
                .isEqualTo("src/main/java/com/example/library/model/LoadTest.java");
    }

    @Test
    void testFileAlreadyUnderTestRootIsUntouched() {
        String content = """
                ```java [src/test/java/com/example/library/model/BookControllerTest.java]
                package com.example.library.model;
                import org.junit.jupiter.api.Test;
                class BookControllerTest {}
                ```
                """;

        EvolutionOutcome outcome = new EvolutionResponseParser().parse(content);

        assertThat(outcome.addedOrChangedFiles().getFirst().path())
                .isEqualTo("src/test/java/com/example/library/model/BookControllerTest.java");
    }

    @Test
    void filenameWithDotInParentSegmentButNoExtensionInLeafIsSkipped() {
        // e.g. "src/main/java/com/example.project/controller" — the leaf has no dot
        String content = """
                ```java [src/main/java/com/example.project/controller]
                public class BookController {}
                ```
                """;

        EvolutionResponseParser parser = new EvolutionResponseParser();
        EvolutionOutcome outcome = parser.parse(content);

        assertThat(outcome.addedOrChangedFiles()).isEmpty();
    }
}
