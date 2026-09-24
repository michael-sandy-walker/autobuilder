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

    private void declare(String text) throws Exception {
        Path f = base.resolve(ProtectedPaths.PATH);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }
}
