package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-EB: an existing file changed by edits -- a piece of it as it is now, and its replacement -- applied
 * exactly, everything else untouched; a piece that does not occur exactly once is refused with the reason.
 */
class EditHunksTest {

    private static final String PATH = "src/main/java/org/ink/Quill.java";
    private static final String QUILL = """
            package org.ink;

            class Quill {
                private static final String TYPE = "\\\\w+";
                int ink() { return 1; }
                int nib() { return 1; }
            }""";

    private static String edit(String... hunks) {
        StringBuilder sb = new StringBuilder("```edit [" + PATH + "]\n");
        for (int i = 0; i < hunks.length; i += 2) {
            sb.append("<<<<<<< SEARCH\n").append(hunks[i]).append("\n=======\n").append(hunks[i + 1]).append("\n>>>>>>> REPLACE\n");
        }
        return sb.append("```\n").toString();
    }

    @Test
    void aOneLineFixChangesOneLine() {
        var applied = EditHunks.apply(edit("    private static final String TYPE = \"\\\\w+\";",
                "    private static final String TYPE = \"[\\\\w<>,. ]+\";"), Map.of(PATH, QUILL));

        assertThat(applied.problems()).isEmpty();
        String after = applied.files().get(PATH);
        assertThat(after).contains("TYPE = \"[\\\\w<>,. ]+\"");
        assertThat(after.lines().count()).isEqualTo(QUILL.lines().count());
        assertThat(after.replace("[\\\\w<>,. ]+", "\\\\w+")).as("nothing else touched").isEqualTo(QUILL);
    }

    @Test
    void hunksApplyInOrderEachToWhatTheOneBeforeLeft() {
        var applied = EditHunks.apply(edit("int ink() { return 1; }", "int ink() { return 2; }",
                "int ink() { return 2; }\n    int nib() { return 1; }", "int ink() { return 2; }\n    int nib() { return 3; }"),
                Map.of(PATH, QUILL));

        assertThat(applied.problems()).isEmpty();
        assertThat(applied.files().get(PATH)).contains("int ink() { return 2; }", "int nib() { return 3; }");
    }

    @Test
    void aPieceThatIsAbsentOrAmbiguousIsRefusedWithTheReasonAndTheFileLeftOut() {
        var absent = EditHunks.apply(edit("int well() { return 1; }", "x"), Map.of(PATH, QUILL));
        assertThat(absent.files()).isEmpty();
        assertThat(absent.problems()).singleElement().asString().contains("hunk 1", "is not in the file as it is now");

        var twice = EditHunks.apply(edit("{ return 1; }", "{ return 9; }"), Map.of(PATH, QUILL));
        assertThat(twice.files()).isEmpty();
        assertThat(twice.problems()).singleElement().asString().contains("occurs 2 times");

        var halfway = EditHunks.apply(edit("int ink() { return 1; }", "int ink() { return 2; }", "absent", "x"), Map.of(PATH, QUILL));
        assertThat(halfway.files()).as("half an edit is not applied").isEmpty();
        assertThat(halfway.problems()).singleElement().asString().contains("hunk 2");
    }

    @Test
    void anEditNamesAnExistingFile() {
        var applied = EditHunks.apply(edit("a", "b"), Map.of());
        assertThat(applied.problems()).singleElement().asString().contains("no such file", "whole-file block");
    }

    @Test
    void theParserTurnsAnEditIntoTheWholeNewFileAndNeverTakesTheBlockForAFile() {
        String response = "```java [src/main/java/org/ink/Nib.java]\npackage org.ink;\nclass Nib {}\n```\n"
                + edit("int nib() { return 1; }", "int nib() { return 2; }");

        EvolutionOutcome outcome = new EvolutionResponseParser().parse(response, Map.of(PATH, QUILL));

        assertThat(outcome.addedOrChangedFiles()).extracting(FileMutation::path)
                .containsExactlyInAnyOrder("src/main/java/org/ink/Nib.java", PATH);
        String quill = outcome.addedOrChangedFiles().stream().filter(f -> f.path().equals(PATH)).findFirst().orElseThrow().content();
        assertThat(quill).contains("package org.ink;", "int ink() { return 1; }", "int nib() { return 2; }")
                .doesNotContain("<<<<<<<", ">>>>>>>");
    }

    @Test
    void withoutTheTreeARefusedEditChangesNothing() {
        EvolutionOutcome outcome = new EvolutionResponseParser().parse(edit("int nib() { return 1; }", "x"));
        assertThat(outcome.addedOrChangedFiles()).isEmpty();
    }

    @Test
    void anEditBlockIsAValidResponseShape() {
        assertThat(EvolutionProtocolContract.isValidResponse(edit("int nib() { return 1; }", "x"))).isTrue();
    }
    @Test
    void hunksSentUnderTheSourceListingsTagAreStillAnEditNeverTheWholeFile() {
        // participation-totals run 2026-09-30_12-48-02: both hunks came in a java fence and were written as
        // the file, markers and all, then rejected at the compile probe.
        String response = edit("int nib() { return 1; }", "int nib() { return 2; }").replace("```edit [", "```java [");

        EvolutionOutcome outcome = new EvolutionResponseParser().parse(response, Map.of(PATH, QUILL));

        assertThat(outcome.addedOrChangedFiles()).singleElement().satisfies(f -> {
            assertThat(f.path()).isEqualTo(PATH);
            assertThat(f.content()).isEqualTo(QUILL.replace("int nib() { return 1; }", "int nib() { return 2; }"));
        });
        assertThat(EditHunks.apply(response.replace("return 1; }\n=", "return 9; }\n="), Map.of(PATH, QUILL)).problems())
                .as("a misfenced hunk is refused with the reason like any other edit").singleElement().asString()
                .contains("not in the file");
    }

    @Test
    void aFileThatMentionsTheMarkerBelowItsFirstLineIsStillAFile() {
        String file = "package org.ink;\nclass Diff {\n    static final String OPEN = \"<<<<<<< SEARCH\";\n}";
        EvolutionOutcome outcome = new EvolutionResponseParser()
                .parse("```java [src/main/java/org/ink/Diff.java]\n" + file + "\n```\n", Map.of());

        assertThat(outcome.addedOrChangedFiles()).singleElement().extracting(FileMutation::content).isEqualTo(file);
        assertThat(EditHunks.isEdit("java", "\n" + file)).isFalse();
        assertThat(EditHunks.isEdit("java", "\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE")).isTrue();
    }

    /**
     * parameterized-rows run 2026-10-02_16-26-44: the right fix came back ending on its last REPLACE marker with no
     * closing fence. The answer's end closes a final edit whose hunks are whole -- never a cut hunk, never a file.
     */
    @Test
    void anAnswerThatEndsOnAWholeEditIsClosedByItsEnd_aCutOneOrAFileIsNot() {
        String closed = edit("int nib() { return 1; }", "int nib() { return 2; }");
        String unclosed = closed.substring(0, closed.lastIndexOf("```"));
        assertThat(EvolutionProtocolContract.isValidResponse(unclosed)).isTrue();
        assertThat(new EvolutionResponseParser().parse(unclosed, Map.of(PATH, QUILL)).addedOrChangedFiles())
                .extracting(FileMutation::content).singleElement().asString().contains("int nib() { return 2; }");

        String cut = unclosed.substring(0, unclosed.lastIndexOf(">>>>>>> REPLACE"));
        assertThat(EvolutionProtocolContract.isValidResponse(cut)).isFalse();
        String file = "```java [src/main/java/org/ink/Nib.java]\npackage org.ink;\nclass Nib {\n";
        assertThat(EvolutionProtocolContract.isValidResponse(file)).isFalse();
    }

    /**
     * T6-T: a whole-file block that keeps less than half of an existing file is refused -- written from the parts a
     * prompt showed (ladder-grind field50 run 1: a 1,079-line class came back as a fraction of itself); a new file,
     * a small file, and a whole rewrite that keeps the file are ordinary.
     */
    @Test
    void aWholeFileBlockThatDropsMostOfAnExistingFile_isRefused() {
        String big = "class Ink {\n" + "  int a;\n".repeat(100) + "}\n";
        java.util.Map<String, String> tree = java.util.Map.of("src/main/java/Ink.java", big, "src/main/java/Nib.java", "class Nib {}\n");
        // a rewrite cut short does not parse: it cannot be applied member by member, so it is refused (a whole short
        // rewrite that parses is applied member by member instead -- MemberMergeTest)
        String partial = "```java [src/main/java/Ink.java]\nclass Ink {\n  int b;\n  void cut(\n```\n";
        String whole = "```java [src/main/java/Ink.java]\n" + big.replace("int a;", "int b;") + "```\n";
        String small = "```java [src/main/java/Nib.java]\nclass Nib { int c; }\n```\n";
        String fresh = "```java [src/main/java/Quill.java]\nclass Quill {}\n```\n";

        assertThat(EditHunks.partialRewrites(partial, tree)).singleElement().asString()
                .contains("src/main/java/Ink.java: written whole with 3 of its 102 lines", "edit blocks");
        assertThat(EditHunks.partialRewrites(whole + small + fresh, tree)).isEmpty();
    }
}
