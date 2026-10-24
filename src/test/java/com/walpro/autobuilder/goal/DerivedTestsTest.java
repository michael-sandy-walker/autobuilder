package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.build.DerivedTestsFile;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T6-U: the tests a change is held to, written from the requirement's wording before the code changes, checked
 * against that wording, then frozen. Invented coordinates; the failure each test names is field105's.
 */
class DerivedTestsTest {

    private static final String SEED = """
              An existing project, adopted, and asked to change. A refused edit names its reason.

              OBJECTIVE: edits are applied as above, and the whole suite passes.
            """;
    private static final String REQUIREMENT = "A piece that occurs more than once is refused with \"occurs N times\".";
    private static final String PATH = "src/test/java/org/ink/DerivedRequirement01Test.java";

    @TempDir
    Path base;

    private FileService files;
    private final Deque<String> answers = new ArrayDeque<>();
    private LLMClient llm;

    @BeforeEach
    void setUp() throws Exception {
        files = new FileService(base.toString(), new ObjectMapper());
        files.writeFile(DerivedTests.SEED_PATH, SEED);
        files.writeFile(SeedObligationsFile.PATH, SeedObligationsFile.render(SEED, List.of(
                "rule: Keep the project's naming.",
                SeedRequirements.GATE_MARKER + " " + SeedRequirements.GATE_DERIVED + " " + REQUIREMENT)));
        files.writeFile("output/src/main/java/org/ink/Quill.java",
                "package org.ink;\npublic class Quill {\n  public int ink() { return 1 + 1; }\n}\n");
        files.writeFile("output/src/test/java/org/ink/QuillTest.java", "package org.ink;\nclass QuillTest {}\n");
        llm = mock(LLMClient.class);
        when(llm.ask(any())).thenAnswer(inv -> {
            ChatResponse body = mock(ChatResponse.class);
            when(body.getFirstContent()).thenReturn(answers.isEmpty() ? "" : answers.poll());
            return ResponseEntity.ok(body);
        });
    }

    private static String file(String body) {
        return "```java [" + PATH + "]\npackage org.ink;\nclass DerivedRequirement01Test {\n" + body + "\n}\n```\n";
    }

    @Test
    void aTestLeavingOutTheQuotedWordingIsWrittenAgainWithTheReasonThenAcceptedAndWrittenIntoTheTree() throws Exception {
        answers.add(file("  @Test void twice() { assertThat(refusal()).contains(\"duplicate\"); }"));
        answers.add(file("  @Test void twice() { assertThat(refusal()).contains(\"occurs 2 times\"); }"));
        answers.add("VERDICT: PASS\nIt checks the refusal's wording with the count.");
        DerivedTests derived = new DerivedTests(llm, files);

        derived.ensureFor(SEED);

        List<DerivedTestsFile.Entry> entries = DerivedTestsFile.read(files);
        assertThat(entries).singleElement().satisfies(e -> {
            assertThat(e.accepted()).isTrue();
            assertThat(e.requirement()).isEqualTo(REQUIREMENT);
            assertThat(e.path()).isEqualTo(PATH);
        });
        assertThat(Files.readString(base.resolve("output/" + PATH))).contains("occurs 2 times");
        assertThat(derived.settled()).isTrue();
        assertThat(derived.acceptedRequirements()).containsExactly(REQUIREMENT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> asked = ArgumentCaptor.forClass(List.class);
        verify(llm, atLeastOnce()).ask(asked.capture());
        assertThat(asked.getAllValues().get(1).get(1).getContent())
                .as("the second writing is told why the first was not accepted")
                .contains("the requirement quotes \"occurs N times\"");
        assertThat(asked.getAllValues().get(2).get(1).getContent())
                .as("the reviewer sees the requirement and the test, never the code")
                .contains(REQUIREMENT).doesNotContain("return 1 + 1");
    }

    @Test
    void aRequirementTheReviewNeverPassesIsLeftWithoutATestAndNothingIsSettled() throws Exception {
        for (int i = 0; i < DerivedTests.ATTEMPTS; i++) {
            answers.add(file("  @Test void twice() { assertThat(refusal()).contains(\"occurs 2 times\"); }"));
            answers.add("VERDICT: FAIL\nIt never tests a piece that occurs more than twice.");
        }
        DerivedTests derived = new DerivedTests(llm, files);

        derived.ensureFor(SEED);

        assertThat(DerivedTestsFile.read(files)).singleElement().satisfies(e -> assertThat(e.accepted()).isFalse());
        assertThat(Files.exists(base.resolve("output/" + PATH))).isFalse();
        assertThat(derived.settled()).isFalse();
    }

    @Test
    void anAcceptedTestIsKeptAcrossARestartAndPutBackWhenTheTreeLosesOrChangesIt() throws Exception {
        answers.add(file("  @Test void twice() { assertThat(refusal()).contains(\"occurs 2 times\"); }"));
        answers.add("VERDICT: PASS\nfine");
        new DerivedTests(llm, files).ensureFor(SEED);
        String accepted = Files.readString(base.resolve("output/" + PATH));

        DerivedTests restarted = new DerivedTests(llm, files);
        restarted.ensureFor(SEED);
        verify(llm, org.mockito.Mockito.times(2)).ask(any());

        Files.writeString(base.resolve("output/" + PATH), "class DerivedRequirement01Test {}");
        restarted.restore();
        assertThat(Files.readString(base.resolve("output/" + PATH))).isEqualTo(accepted);
        Files.delete(base.resolve("output/" + PATH));
        restarted.restore();
        assertThat(Files.readString(base.resolve("output/" + PATH))).isEqualTo(accepted);
    }

    @Test
    void aWritersCorrectionIsJudgedAgainstTheWordingAndOnlyAnAcceptedOneReplacesTheTest() throws Exception {
        answers.add(file("  @Test void twice() { assertThat(refusal()).contains(\"occurs 2 times\"); }"));
        answers.add("VERDICT: PASS\nfine");
        DerivedTests derived = new DerivedTests(llm, files);
        derived.ensureFor(SEED);
        String accepted = DerivedTestsFile.read(files).get(0).content();

        assertThat(derived.contest("evolution", PATH, accepted)).as("the accepted text itself").isTrue();

        String weaker = "package org.ink;\nclass DerivedRequirement01Test {\n  @Test void twice() {}\n}\n";
        assertThat(derived.contest("evolution", PATH, weaker)).as("drops the quoted wording").isFalse();
        assertThat(derived.drainNotes()).contains("NOT accepted", "occurs N times");
        assertThat(DerivedTestsFile.read(files).get(0).content()).isEqualTo(accepted);

        String corrected = "package org.ink;\nclass DerivedRequirement01Test {\n"
                + "  @Test void twice() { assertThat(refusal(\"a\")).contains(\"occurs 2 times\"); }\n}\n";
        answers.add("VERDICT: PASS\nThe count is still checked.");
        assertThat(derived.contest("evolution", PATH, corrected)).isTrue();
        assertThat(DerivedTestsFile.read(files).get(0).content()).isEqualTo(corrected);
        assertThat(derived.drainNotes()).contains("was accepted");
        assertThat(derived.drainNotes()).as("drained").isNull();
    }

    @Test
    void aQuotedTextIsFoundThroughTheTestsEscapesAndSpacing() {
        assertThat(DerivedTests.missingQuotes("Names it as \"Type.name(String)\" or \"\\w+\".",
                "assertThat(a).isEqualTo(\"Type.name(String)\"); assertThat(p).isEqualTo(\"\\\\w+\");")).isEmpty();
        assertThat(DerivedTests.missingQuotes("Refused with `no such   file`.", "contains(\"no such file\")")).isEmpty();
        assertThat(DerivedTests.missingQuotes("Refused with \"hunk 2\".", "contains(\"hunk\")")).isPresent();
        assertThat(DerivedTests.missingQuotes("Refused with \"occurs N times\".", "contains(\"occurs 2 times\")"))
                .as("a capital letter standing alone is a placeholder").isEmpty();
        assertThat(DerivedTests.missingQuotes("Answered as \"Map\".", "isEqualTo(\"Mop\")")).isPresent();
    }

    @Test
    void aReviewWithoutAVerdictNeverPasses() {
        assertThat(DerivedTests.verdict("VERDICT: PASS\nok").pass()).isTrue();
        assertThat(DerivedTests.verdict("verdict: pass").pass()).isTrue();
        assertThat(DerivedTests.verdict("VERDICT: FAIL\nmissing the Map form").reason()).isEqualTo("missing the Map form");
        assertThat(DerivedTests.verdict("Looks good to me.").pass()).isFalse();
        assertThat(DerivedTests.verdict(null).pass()).isFalse();
    }

    @Test
    void theOutlineKeepsHowTheCodeIsCalledAndLeavesOutWhatItDoes() {
        String outline = DerivedTests.outline("package org.ink;\n/** Counts ink. */\npublic class Quill {\n"
                + "  public Quill(int n) { this.n = n * 2; }\n  private final int n;\n"
                + "  public int ink(String colour) { return n + colour.length(); }\n}\n");
        assertThat(outline).contains("Counts ink.", "public Quill(int n)", "public int ink(String colour)")
                .doesNotContain("n * 2", "colour.length()");
    }

    @Test
    void aTargetMarkingNoLineIsSettledAndNothingIsAsked() throws Exception {
        files.writeFile(SeedObligationsFile.PATH, SeedObligationsFile.render(SEED, List.of("The suite passes.")));
        DerivedTests derived = new DerivedTests(llm, files);
        derived.ensureFor(SEED);
        assertThat(derived.settled()).isTrue();
        verify(llm, org.mockito.Mockito.never()).ask(any());
    }
}
