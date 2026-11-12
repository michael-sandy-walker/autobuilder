package com.walpro.autobuilder.core.investigate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-EU: what leaves the evidence reader is the lines of the files, read here -- never the investigating
 * model's sentence about them, and never a line no file holds.
 */
class EvidenceReaderTest {

    @TempDir
    Path project;

    private final LogInvestigator investigator = mock(LogInvestigator.class);

    private void tree() throws Exception {
        Path test = project.resolve("src/test/java/quill/QuillSpecTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, String.join("\n",
                "package quill;",
                "class QuillSpecTest {",
                "    @Test void namesTheFirstQuill() {",
                "        assertThat(shelf.nameOf(1)).contains(\"Q-0001\");",
                "    }",
                "}"));
    }

    private void answers(String answer) {
        when(investigator.investigate(any(), eq(Map.of()), eq(project), anyInt(), any()))
                .thenReturn(new LogInvestigator.Investigation("q",
                        List.of(new LogInvestigator.Step("#Probe find nameOf", "...")), answer, answer != null));
    }

    @Test
    void theCitedLinesAreReadFromTheFiles_andTheModelsSentenceIsKeptApart() throws Exception {
        tree();
        answers("src/test/java/quill/QuillSpecTest.java:3-5\nThose lines compare the value with a literal.");

        var reading = new EvidenceReader(investigator, true).read("nameOf is asserted by no test", project, "cycle-4");

        assertThat(reading.found()).isTrue();
        assertThat(reading.places()).hasSize(1);
        var place = reading.places().get(0);
        assertThat(place.lines()).hasSize(3);
        assertThat(place.render()).contains("4:         assertThat(shelf.nameOf(1)).contains(\"Q-0001\");")
                .doesNotContain("compare the value");
        assertThat(reading.modelReading()).contains("compare the value with a literal");
        assertThat(reading.probes()).containsExactly("#Probe find nameOf");
        verify(investigator).investigate(contains("nameOf is asserted by no test"), eq(Map.of()), eq(project), anyInt(), eq("cycle-4"));
    }

    @Test
    void aPlaceNoFileHoldsIsLeftOut_andARangeIsCutToTheFile() throws Exception {
        tree();
        answers("src/test/java/quill/Invented.java:1-9 and ./src/test/java/quill/QuillSpecTest.java:5-400,"
                + " also src/test/java/quill/QuillSpecTest.java:99");

        var reading = new EvidenceReader(investigator, true).read("a statement", project, null);

        assertThat(reading.places()).hasSize(1);
        assertThat(reading.places().get(0).first()).isEqualTo(5);
        assertThat(reading.places().get(0).last()).isEqualTo(6);
    }

    @Test
    void anAnswerCitingNothing_orNoAnswer_findsNothing() throws Exception {
        tree();
        answers("The test asserts the value; the check is mistaken.");
        assertThat(new EvidenceReader(investigator, true).read("a statement", project, null).found()).isFalse();
        answers(null);
        assertThat(new EvidenceReader(investigator, true).read("a statement", project, null).found()).isFalse();
    }

    @Test
    void switchedOff_nothingIsAsked() throws Exception {
        tree();
        assertThat(new EvidenceReader(investigator, false).read("a statement", project, null).found()).isFalse();
        verify(investigator, never()).investigate(any(), any(), any(), anyInt(), any());
    }
}
