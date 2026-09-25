package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-CQ: the operator's list of paths the loop may not write, read as data beside the specification. */
class ProtectedPathsTest {

    @TempDir
    Path base;

    private ProtectedPaths paths;

    @BeforeEach
    void setUp() {
        paths = new ProtectedPaths(new FileService(base.toString(), new ObjectMapper()));
    }

    @Test
    void withoutTheFileNothingIsProtected() {
        assertThat(paths.isProtected("src/main/java/zeta/Quill.java")).isFalse();
        assertThat(paths.refuse("a writer", "pom.xml")).isFalse();
    }

    @Test
    void aGlobProtectsEverythingBeneathItAndNothingBesideIt() throws Exception {
        declare("# the operator's own code\nsrc/main/**\n\n");

        assertThat(paths.isProtected("src/main/java/zeta/Quill.java")).isTrue();
        assertThat(paths.isProtected("output/src/main/java/zeta/Quill.java"))
                .as("a path given with the output prefix is the same path").isTrue();
        assertThat(paths.refuse("a writer", "src/main/resources/app.properties")).isTrue();
        assertThat(paths.isProtected("src/test/java/zeta/QuillTest.java")).isFalse();
        assertThat(paths.isProtected("pom.xml")).isFalse();
    }

    @Test
    void anEditedListTakesEffectWithoutARestart() throws Exception {
        declare("src/main/**\n");
        assertThat(paths.isProtected("pom.xml")).isFalse();

        declare("src/main/**\npom.xml\n");
        assertThat(paths.isProtected("pom.xml")).isTrue();
    }

    private static final String QUILL = "package zeta;\npublic class Quill {\n"
            + "    private static final Quill BLANK = new Quill(0);\n"
            + "    private final int ink;\n"
            + "    public Quill(int ink) { this.ink = ink; }\n"
            + "    public Quill refill(int more) { return new Quill(ink + more); }\n"
            + "}\n";

    /** members: the file may be written, but what it declared at adoption stays -- and the model is told. */
    @Test
    void aMembersLineKeepsTheAdoptedDeclarations_addsTheNewOnes_andSaysWhatWasPutBack() throws Exception {
        declare("members: src/main/**\n");
        adopt("src/main/java/zeta/Quill.java", QUILL);

        assertThat(paths.isProtected("src/main/java/zeta/Quill.java")).as("writable, unlike a path glob").isFalse();
        assertThat(paths.preservesMembers("src/main/java/zeta/Quill.java")).isTrue();
        paths.recordAdoptionBaseline();

        String rewrite = "package zeta;\npublic class Quill {\n    private final int ink;\n"
                + "    public Quill(int ink) { this.ink = ink; }\n"
                + "    public Quill refill(int more) { return new Quill(more); }\n"
                + "    public int ink() { return ink; }\n}\n";
        String written = paths.preserve("evolution", "src/main/java/zeta/Quill.java", rewrite);

        assertThat(written).contains("return new Quill(ink + more);", "private static final Quill BLANK = new Quill(0);",
                "public int ink() { return ink; }").doesNotContain("return new Quill(more);");
        String block = paths.promptBlock();
        assertThat(block).contains("every declaration that already exists stays exactly as it is", "src/main/**",
                "src/main/java/zeta/Quill.java: Quill.field BLANK (removed), Quill.refill(int) (changed)");
        assertThat(paths.promptBlock()).as("reported once").doesNotContain("were put back");
    }

    @Test
    void theBaselineIsTheAdoptionsAndIsNeverReplaced_andANewFileIsLeftAlone() throws Exception {
        declare("members: src/main/**\n");
        adopt("src/main/java/zeta/Quill.java", QUILL);
        paths.recordAdoptionBaseline();
        adopt("src/main/java/zeta/Quill.java", QUILL.replace("ink + more", "more"));
        paths.recordAdoptionBaseline();

        assertThat(Files.readString(base.resolve(ProtectedPaths.BASELINE_DIR + "/src/main/java/zeta/Quill.java")))
                .contains("ink + more");
        String fresh = "package zeta;\npublic class Nib { }\n";
        assertThat(paths.preserve("evolution", "src/main/java/zeta/Nib.java", fresh)).isSameAs(fresh);
        assertThat(paths.refuseDelete("evolution", "src/main/java/zeta/Quill.java")).isTrue();
        assertThat(paths.refuseDelete("evolution", "src/main/java/zeta/Nib.java")).isFalse();
    }

    @Test
    void withoutAMembersLineNothingIsRecordedOrRestored() throws Exception {
        declare("src/test/**\n");
        adopt("src/main/java/zeta/Quill.java", QUILL);
        paths.recordAdoptionBaseline();

        assertThat(Files.exists(base.resolve(ProtectedPaths.BASELINE_DIR))).isFalse();
        assertThat(paths.preserve("evolution", "src/main/java/zeta/Quill.java", "anything")).isEqualTo("anything");
    }

    private void adopt(String rel, String text) throws Exception {
        Path f = base.resolve("output").resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private void declare(String text) throws Exception {
        Path f = base.resolve(ProtectedPaths.PATH);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }
}
