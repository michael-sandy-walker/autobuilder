package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.StuckQuestions.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SYMBOL question: where is a name that could not be resolved actually declared?
 *
 * <p>The three archived grinds slice A could not answer (2026-09-26: 4 of 7, bar 6) were bare
 * "cannot find symbol" signatures. With the detector keeping the symbol and its location, each is
 * asked here against the tree that run had; the answers are what those trees (and the JDK) declare.
 */
class StuckQuestionsSymbolTest {

    private static Map<String, String> tree(String project) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(StuckQuestionsSymbolTest.class.getResourceAsStream("/stuck/trees.zip"))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.isDirectory() || !e.getName().startsWith(project + "/")) continue;
                files.put(e.getName().substring(project.length() + 1), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void aRecordComponentTheCodeLostIsFoundInTheRecord() throws IOException {
        var a = StuckQuestions.answer("the build fails: ChannelRequest.java: cannot find symbol variable subGoalId "
                + "in class ChannelRequest", tree("participation")).orElseThrow();
        assertThat(a.kind()).isEqualTo(Kind.SYMBOL);
        assertThat(a.type()).isEqualTo("ChannelRequest");
        assertThat(a.member()).isEqualTo("subGoalId");
        assertThat(a.declared()).containsExactly("ChannelRequest.subGoalId (record component)");
    }

    @Test
    void aJdkClassTheCodeDidNotImportIsFoundInTheJdk() throws IOException {
        var a = StuckQuestions.answer("the build fails: ParticipationWindows.java: cannot find symbol class ArrayList "
                + "in class ParticipationWindows", tree("participation")).orElseThrow();
        assertThat(a.declared()).containsExactly("java.util.ArrayList (JDK class)");
    }

    @Test
    void aNameDeclaredElsewhereAndPrivateIsFoundWhereItIs() throws IOException {
        var a = StuckQuestions.answer("the build fails: StructuralDiffUtilsTest.java: cannot find symbol variable ChangeKind "
                + "in class StructuralDiffNode", tree("structdiff")).orElseThrow();
        assertThat(a.type()).isEqualTo("StructuralDiffNode");
        assertThat(a.declared()).containsExactly("DiffSummarizer.ChangeKind (private enum)");
    }

    @Test
    void theSameQuestionOverAnInventedVocabulary() {
        Map<String, String> coils = Map.of("src/main/java/org/warp/Coil.java", """
                package org.warp;
                public class Coil {
                    private int turns;
                    public int align(int strength) { return strength; }
                    static class Winding { }
                }
                """);
        assertThat(StuckQuestions.answer("the build fails: CoilTest.java: cannot find symbol variable turns in class Coil", coils)
                .orElseThrow().declared()).containsExactly("Coil.turns (private field)");
        assertThat(StuckQuestions.answer("the build fails: CoilTest.java: cannot find symbol method align(String) in variable c of type Coil", coils)
                .orElseThrow().declared()).containsExactly("Coil.align(int) (method)");
        assertThat(StuckQuestions.answer("the build fails: CoilTest.java: cannot find symbol class Winding in class CoilTest", coils)
                .orElseThrow().declared()).containsExactly("Coil.Winding (class)");
        assertThat(StuckQuestions.answer("the build fails: CoilTest.java: cannot find symbol variable plasma in class Coil", coils)).isEmpty();
    }
}
