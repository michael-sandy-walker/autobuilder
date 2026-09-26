package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.StuckQuestions.Answer;
import com.walpro.autobuilder.core.build.StuckQuestions.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The specification of {@link StuckQuestions#answer}.
 *
 * <p>The signatures are not written here. {@code stuck/signatures.tsv} is every distinct failure
 * signature the grind detector recorded in the archived runs of 2026-09-24/25 on three adopted
 * projects, beside the source tree that project had (its {@code src/main} at the revision it was
 * adopted from, packed per project in {@code stuck/trees.zip} so no tool or prompt mistakes them for
 * this project's own sources). The expected answers are what those sources declare;
 * where the signature names a missing accessor, the declared member it means is the one the archive's
 * own later fixes recorded (ParsedElement.getType() -> type(), getId() -> id(), getChildren() ->
 * children(); StructuralDiffNode.getChildren() -> children()).
 */
class StuckQuestionsSpecTest {

    private static final Map<String, Map<String, String>> TREES = new LinkedHashMap<>();

    private static Map<String, String> tree(String project) {
        return TREES.computeIfAbsent(project, p -> {
            Map<String, String> files = new LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(StuckQuestionsSpecTest.class.getResourceAsStream("/stuck/trees.zip"))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    if (e.isDirectory() || !e.getName().startsWith(p + "/")) continue;
                    files.put(e.getName().substring(p.length() + 1), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return files;
        });
    }

    private static Stream<Arguments> archived() throws IOException {
        try (InputStream in = StuckQuestionsSpecTest.class.getResourceAsStream("/stuck/signatures.tsv")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines().filter(l -> !l.isBlank()).map(l -> l.split("\t", -1)).map(f ->
                    Arguments.of(f[2] + " " + f[3], f[0], f[1], f[2], f[3], f[4], f[5], f[6])).toList().stream();
        }
    }

    private static String orNull(String field) {
        return "-".equals(field) ? null : field;
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("archived")
    void everyArchivedSignatureIsAnsweredFromItsOwnTree(String label, String project, String signature, String kind,
                                                        String type, String member, String meant, String declared) {
        Optional<Answer> answer = StuckQuestions.answer(signature, tree(project));
        if (kind.equals("NONE")) {
            assertThat(answer).as(signature).isEmpty();
            return;
        }
        assertThat(answer).as(signature).isPresent();
        Answer a = answer.get();
        assertThat(a.kind()).isEqualTo(Kind.valueOf(kind));
        assertThat(a.type()).isEqualTo(type);
        assertThat(a.member()).isEqualTo(orNull(member));
        assertThat(a.meant()).isEqualTo(orNull(meant));
        assertThat(a.declared()).containsExactlyElementsOf(Arrays.asList(declared.split(" \\| ")));
    }

    // The same questions over an invented vocabulary, so a rule that learned these names fails.
    private static final Map<String, String> COILS = Map.of(
            "src/main/java/org/warp/Coil.java", """
                    package org.warp;
                    public record Coil(String emitter, int turns) {
                        public Coil(String emitter) { this(emitter, 1); }
                        public boolean isAligned() { return turns > 0; }
                    }
                    """,
            "src/main/java/org/warp/Harness.java", """
                    package org.warp;
                    import java.util.List;
                    public class Harness {
                        public static int align(Coil coil) { return 1; }
                        public static int align(List<Coil> coils, boolean strict) { return coils.size(); }
                        private static int secret() { return 0; }
                    }
                    """);

    @Test
    void aMissingAccessorIsAnsweredWithTheMemberItMeans() {
        Answer a = StuckQuestions.answer("the build fails: Coil has no getTurns()", COILS).orElseThrow();
        assertThat(a.kind()).isEqualTo(Kind.MEMBERS);
        assertThat(a.type()).isEqualTo("Coil");
        assertThat(a.member()).isEqualTo("getTurns");
        assertThat(a.meant()).isEqualTo("turns()");
        assertThat(a.declared()).containsExactly("emitter()", "turns()", "isAligned()");
    }

    @Test
    void aMissingMemberNothingDeclaredMeansHasNoMeantMember() {
        Answer a = StuckQuestions.answer("the build fails: Coil has no rewind(int)", COILS).orElseThrow();
        assertThat(a.member()).isEqualTo("rewind");
        assertThat(a.meant()).isNull();
        assertThat(a.declared()).containsExactly("emitter()", "turns()", "isAligned()");
    }

    @Test
    void aRecordConstructorMismatchIsAnsweredWithItsConstructors() {
        Answer a = StuckQuestions.answer("the build fails: CoilTest.java: constructor Coil in record org.warp.Coil "
                + "cannot be applied to given types;", COILS).orElseThrow();
        assertThat(a.kind()).isEqualTo(Kind.CONSTRUCTOR);
        assertThat(a.type()).isEqualTo("Coil");
        assertThat(a.member()).isNull();
        assertThat(a.declared()).containsExactly("Coil(String emitter, int turns)", "Coil(String emitter)");
    }

    @Test
    void aMethodArgumentMismatchIsAnsweredWithItsOverloads() {
        Answer a = StuckQuestions.answer("the build fails: HarnessTest.java: method align in class org.warp.Harness "
                + "cannot be applied to given types;", COILS).orElseThrow();
        assertThat(a.kind()).isEqualTo(Kind.OVERLOADS);
        assertThat(a.type()).isEqualTo("Harness");
        assertThat(a.member()).isEqualTo("align");
        assertThat(a.declared()).containsExactly("align(Coil coil)", "align(List<Coil> coils, boolean strict)");
    }

    @Test
    void aTypeWhoseSourceIsNotInTheProjectHasNoAnswer() {
        assertThat(StuckQuestions.answer("the build fails: String has no getLength()", COILS)).isEmpty();
        assertThat(StuckQuestions.answer("the build fails: Plasma has no getHeat()", COILS)).isEmpty();
    }

    @Test
    void aSignatureNamingNothingToLookUpHasNoAnswer() {
        assertThat(StuckQuestions.answer("the build fails: CoilTest.java: cannot find symbol", COILS)).isEmpty();
        assertThat(StuckQuestions.answer("write to src/test/java/org/warp/CoilTest.java refused: protected", COILS)).isEmpty();
        assertThat(StuckQuestions.answer(null, COILS)).isEmpty();
        assertThat(StuckQuestions.answer("the build fails: Coil has no getTurns()", Map.of())).isEmpty();
        assertThat(StuckQuestions.answer("the build fails: Coil has no getTurns()", null)).isEmpty();
    }

    @Test
    void theTreeIsReadNotAssumed() {
        // Same signature, a tree where the record declares a getter-style accessor: the answer follows the tree.
        Map<String, String> other = Map.of("src/main/java/org/warp/Coil.java",
                "package org.warp;\npublic class Coil { public int getTurns() { return 1; } public int turnCount() { return 1; } }\n");
        Answer a = StuckQuestions.answer("the build fails: Coil has no turns()", other).orElseThrow();
        assertThat(a.meant()).isEqualTo("getTurns()");
        assertThat(a.declared()).containsExactly("getTurns()", "turnCount()");
    }

    @Test
    void theArchivedTreesAreThere() {
        assertThat(List.of("structdiff", "participation")).allSatisfy(p -> assertThat(tree(p)).isNotEmpty());
    }
}
