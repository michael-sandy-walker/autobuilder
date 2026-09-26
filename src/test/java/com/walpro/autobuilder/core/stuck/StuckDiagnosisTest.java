package com.walpro.autobuilder.core.stuck;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-DY slice B: the questions and their answers from structure. Invented coordinates only. */
class StuckDiagnosisTest {

    private static final String QUILL_NOW = "package org.ink;\npublic record Quill(String nib) {}\n";
    private static final String QUILL_ADOPTED = "package org.ink;\npublic record Quill(String nib, int reach) {}\n";

    @Test
    void theFirstBuildQuestionAsksTheCurrentTreeAndTheAdoptedOriginalAndSaysWhereTheyDiffer() {
        var a = new StuckDiagnosis.Authorities(Map.of("src/main/java/org/ink/Quill.java", QUILL_NOW),
                Map.of("src/main/java/org/ink/Quill.java", QUILL_ADOPTED), "");

        var asked = StuckDiagnosis.ask(StuckObservation.Source.BUILD,
                "the build fails: Quill.java: cannot find symbol variable reach in class Quill", List.of(), 0, a).orElseThrow();

        assertThat(asked.answered()).as("not declared now, declared at adoption").isTrue();
        assertThat(asked.answer()).contains("Not declared in the current tree").contains("Quill.reach (record component)");
    }

    @Test
    void theDetectorsOwnConstructorWordingIsAnswered() {
        var a = new StuckDiagnosis.Authorities(Map.of("src/main/java/org/ink/Quill.java", QUILL_ADOPTED), Map.of(), "");

        var asked = StuckDiagnosis.ask(StuckObservation.Source.BUILD,
                "the build fails: Quill has no constructor Quill(String)", List.of(), 0, a).orElseThrow();

        assertThat(asked.answer()).isEqualTo("Quill is constructed only as: Quill(String nib, int reach).");
    }

    @Test
    void theThirdBuildQuestionShowsTheDeclaringFileWholeAsAdopted() {
        var a = new StuckDiagnosis.Authorities(Map.of("src/main/java/org/ink/Quill.java", QUILL_NOW),
                Map.of("src/main/java/org/ink/Quill.java", QUILL_ADOPTED), "");

        var asked = StuckDiagnosis.ask(StuckObservation.Source.BUILD,
                "the build fails: Quill.java: incompatible types: inference variable E has incompatible bounds", List.of(), 2, a)
                .orElseThrow();

        assertThat(asked.answer()).contains("as it was adopted").contains("record Quill(String nib, int reach)");
        assertThat(StuckDiagnosis.ask(StuckObservation.Source.BUILD, "x", List.of(), 3, a).orElseThrow().answered())
                .as("the fourth question is the observer's; none wired here").isFalse();
        assertThat(StuckDiagnosis.ask(StuckObservation.Source.BUILD, "x", List.of(), 4, a)).as("four questions only").isEmpty();
    }

    @Test
    void aProtectedWriteIsAnsweredWithTheFileItMayNotChange() {
        var a = new StuckDiagnosis.Authorities(Map.of("src/test/java/org/ink/QuillTest.java", "class QuillTest { /* reach() == 3 */ }"),
                Map.of(), "");

        var asked = StuckDiagnosis.ask(StuckObservation.Source.PROTECTED_WRITE,
                "write to src/test/java/org/ink/QuillTest.java refused: protected", List.of(), 0, a).orElseThrow();

        assertThat(asked.answer()).contains("stays exactly as it is").contains("reach() == 3");
    }

    @Test
    void aRepeatedRefusalNamesTheRequirementAndAsksTheOperatorWhenTheSeedNeverNamesIt() {
        String seed = "Build a quill registry. GET /quills lists every Quill.";
        var a = new StuckDiagnosis.Authorities(Map.of(), Map.of(), seed);

        var asked = StuckDiagnosis.ask(StuckObservation.Source.CLOSURE_REFUSAL,
                "closing 'the registry' was refused: 1 acceptance criterion(s) not confirmed",
                List.of("GET /quills returns every Quill", "QuillIndex.rebuild() is called on start"), 0, a).orElseThrow();

        assertThat(asked.answer()).contains("GET /quills returns every Quill").doesNotContain("never names: /quills");
        assertThat(asked.answer()).contains("[the seed never names: QuillIndex.rebuild]");
        assertThat(asked.needsOperator()).isTrue();
    }

    @Test
    void plainWordsAndSentenceEndsAreNeverCountedAgainstTheSeed() {
        assertThat(StuckDiagnosis.namesTheSeedLacks("The test must assert what the route actually does.", "a seed"))
                .isEmpty();
    }

    @Test
    void theBrakesHaveNoQuestion() {
        var a = new StuckDiagnosis.Authorities(Map.of(), Map.of(), "");
        assertThat(StuckDiagnosis.ask(StuckObservation.Source.FLAT_ROOT, "flat", List.of(), 0, a)).isEmpty();
        assertThat(StuckDiagnosis.ask(StuckObservation.Source.EXHAUSTION, "given up", List.of(), 0, a)).isEmpty();
    }

    @Test
    void anAmbiguousNameIsResolvedByWhatTheFileImportsAsAdopted() {
        String adopted = "package org.ink;\nimport java.util.List;\npublic class Well { List<String> drops; }\n";
        String now = "package org.ink;\npublic class Well { List<String> drops; }\n";
        var a = new StuckDiagnosis.Authorities(Map.of("src/main/java/org/ink/Well.java", now),
                Map.of("src/main/java/org/ink/Well.java", adopted), "");

        var asked = StuckDiagnosis.ask(StuckObservation.Source.BUILD,
                "the build fails: Well.java: cannot find symbol class List in class Well", List.of(), 0, a).orElseThrow();

        assertThat(asked.answer()).contains("java.awt.List").contains("java.util.List")
                .contains("Well.java imports java.util.List as adopted -- that is the one meant");
    }

    @Test
    void withoutAnImportInTheFileTheRestOfTheProjectDecides() {
        var a = new StuckDiagnosis.Authorities(Map.of(
                "src/main/java/org/ink/Well.java", "package org.ink;\nclass Well { List<String> d; }\n",
                "src/main/java/org/ink/Pen.java", "package org.ink;\nimport java.util.*;\nclass Pen {}\n"), Map.of(), "");

        var asked = StuckDiagnosis.ask(StuckObservation.Source.BUILD,
                "the build fails: Well.java: cannot find symbol class List in class Well", List.of(), 0, a).orElseThrow();

        assertThat(asked.answer()).contains("the project imports java.util.List (1 file(s)) and never the other");
    }

    @Test
    void theFailingLineIsSetBesideTheDeclarationAsAdoptedAndAsTheChangeHadIt() {
        String adopted = "package org.ink;\npublic class Well {\n  public record Drop(String colour, java.util.List<Well> wells) {}\n}\n";
        var a = new StuckDiagnosis.Authorities(Map.of(), Map.of("src/main/java/org/ink/Well.java", adopted), "");
        List<String> evidence = List.of("line=return new Drop(\"red\", List.of());", "declared-by-change=Drop(String colour, int count)",
                "constructs=Drop");

        var asked = StuckDiagnosis.ask(StuckObservation.Source.BUILD,
                "the build fails: Well.java: incompatible types: inference variable E has incompatible bounds", evidence, 1, a)
                .orElseThrow();

        assertThat(asked.answer()).contains("return new Drop(\"red\", List.of());")
                .contains("declared as adopted as: Drop(String colour, java.util.List<Well> wells)")
                .contains("The change redeclared it as: Drop(String colour, int count)");
        assertThat(StuckDiagnosis.ask(StuckObservation.Source.BUILD, "x", List.of(), 1, a).orElseThrow().answered())
                .as("no line known: nothing to answer, the ladder moves on").isFalse();
    }
}
