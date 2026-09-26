package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.StuckQuestions.Answer;
import com.walpro.autobuilder.core.build.StuckQuestions.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The specification of the RECEIVER question: a method called on a receiver whose type is a class of
 * the running JDK, not of the project.
 *
 * <p>The signatures are not written here by hand. Each is what the grind detector's own
 * {@code GrindDetector.signaturesOf} makes of a compiler block found in an archived run (the run is
 * named per case). Where that run's source tree is packed in {@code stuck/trees.zip} it is the tree
 * asked; otherwise a small unrelated tree stands in, because the project then declares no such method.
 *
 * <p>What the answer must say, read from data rather than judged: the JDK class's public methods, own
 * and inherited, each as {@code name(ParameterSimpleType, ...)} -- the entries listed per case were read
 * from the class by reflection -- none of them the method that was called; and, as {@code meant}, the
 * first declaration of the called method in the project's sources as {@code Type.name(ParameterTypes)},
 * as the router's own "where is this name declared?" answer finds it -- null when the sources declare
 * no such method.
 */
class ReceiverQuestionsSpecTest {

    private static Map<String, String> tree(String project) throws IOException {
        if (project.equals("none")) {
            return Map.of("src/main/java/org/ink/Quill.java", "package org.ink;\npublic record Quill(String nib) {}\n");
        }
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(ReceiverQuestionsSpecTest.class.getResourceAsStream("/stuck/trees.zip"))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.isDirectory() || !e.getName().startsWith(project + "/")) continue;
                files.put(e.getName().substring(project.length() + 1), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "2026-08-24_00-07-06 | the build fails: Object has no getBookId()          | none          | Object | getBookId  | equals(Object);hashCode();toString();getClass() | -",
            "2026-09-01_00-17-05 | the build fails: Object has no getEmail()           | none          | Object | getEmail   | equals(Object);hashCode();toString();getClass() | -",
            "2026-08-22_02-54-35 | the build fails: Object has no isReturned()         | none          | Object | isReturned | equals(Object);hashCode();toString();getClass() | -",
            "2026-08-22_02-54-35 | the build fails: Object has no setReturned(boolean) | none          | Object | setReturned | equals(Object);hashCode();toString();getClass() | -",
            "2026-09-25_07-31-46 | the build fails: Object has no getId()              | structdiff    | Object | getId      | equals(Object);hashCode();toString();getClass() | StructuralDiffNode.getId()",
            "2026-09-25_07-31-46 | the build fails: Object has no getType()            | structdiff    | Object | getType    | equals(Object);hashCode();toString();getClass() | StructuralDiffNode.getType()",
            "2026-09-25_07-31-46 | the build fails: Object has no isModified()         | structdiff    | Object | isModified | equals(Object);hashCode();toString();getClass() | StructuralDiffNode.isModified()",
            "2026-08-28_05-27-49 | the build fails: GraphicsConfiguration has no createGraphics() | none | GraphicsConfiguration | createGraphics | createCompatibleImage(int, int);getDevice();getBounds();getColorModel() | -",
            "2026-08-28_05-27-49 | the build fails: BufferStrategy has no next()      | none          | BufferStrategy | next      | getDrawGraphics();show();contentsLost();dispose() | -",
            "2026-09-25_19-57-35 | the build fails: ParticipationWindows.java: cannot find symbol method indexOf(ParticipationWindow) in record component active of type Map<String,ParticipationWindow> | participation | Map | indexOf | get(Object);containsKey(Object);keySet();entrySet();values() | -"})
    void aMethodCalledOnAJdkTypeIsAnsweredWithWhatThatTypeDeclares(String run, String signature, String project, String type,
                                                                    String member, String contains, String meant) throws IOException {
        Optional<Answer> answer = StuckQuestions.answer(signature, tree(project));

        assertThat(answer).as("archived in " + run).isPresent();
        Answer a = answer.get();
        assertThat(a.kind()).isEqualTo(Kind.MEMBERS);
        assertThat(a.type()).isEqualTo(type);
        assertThat(a.member()).isEqualTo(member);
        assertThat(a.declared()).contains(contains.split(";"));
        assertThat(a.declared()).noneMatch(d -> d.startsWith(member + "("));
        assertThat(a.declared()).doesNotHaveDuplicates();
        if (meant.equals("-")) assertThat(a.meant()).isNull();
        else assertThat(a.meant()).isEqualTo(meant);
    }

    @Test
    void theProjectsOwnDeclarationOfTheCalledMethodIsNamed() {
        Map<String, String> sources = Map.of(
                "src/main/java/org/ink/Well.java", "package org.ink;\npublic class Well {\n  public int getInk(String colour) { return 1; }\n}\n",
                "src/main/java/org/ink/Quill.java", "package org.ink;\npublic record Quill(String nib) {}\n");

        Answer a = StuckQuestions.answer("the build fails: Object has no getInk(java.lang.String)", sources).orElseThrow();

        assertThat(a.type()).isEqualTo("Object");
        assertThat(a.meant()).isEqualTo("Well.getInk(String)");
    }

    @Test
    void aTypeNeitherTheSourcesNorTheJdkDeclareStillHasNoAnswer() {
        Map<String, String> sources = Map.of("src/main/java/org/ink/Quill.java", "package org.ink;\npublic record Quill(String nib) {}\n");
        assertThat(StuckQuestions.answer("the build fails: Inkwell has no fill()", sources)).isEmpty();
    }

    @Test
    void aTypeTheSourcesDeclareIsStillAnsweredFromTheSources() {
        Map<String, String> sources = Map.of("src/main/java/org/ink/Object.java",
                "package org.ink;\npublic class Object {\n  public int weight() { return 1; }\n}\n");
        Answer a = StuckQuestions.answer("the build fails: Object has no getWeight()", sources).orElseThrow();
        assertThat(a.declared()).isEqualTo(List.of("weight()"));
        assertThat(a.meant()).isEqualTo("weight()");
    }
}
