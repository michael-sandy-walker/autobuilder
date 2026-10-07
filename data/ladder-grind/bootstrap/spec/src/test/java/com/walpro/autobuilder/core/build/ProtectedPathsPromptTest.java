package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-CQ: a refused write is told to the model once, in the next prompt, beside the declared paths. */
class ProtectedPathsPromptTest {

    @TempDir
    Path base;

    @Test
    void theBlockNamesTheGlobsAndEachRefusalOnce() throws Exception {
        Path f = base.resolve(ProtectedPaths.PATH);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "# operator\nsrc/main/**\n");
        ProtectedPaths paths = new ProtectedPaths(new FileService(base.toString(), new ObjectMapper()));

        paths.refuse("the merge", "output/src/main/java/org/sample/Quill.java");
        paths.refuse("the compile probe", "src/main/java/org/sample/Quill.java");
        String first = paths.promptBlock();

        assertThat(first).contains("  - src/main/**").contains("NOT applied")
                .containsOnlyOnce("src/main/java/org/sample/Quill.java");
        assertThat(paths.promptBlock()).as("already told").contains("src/main/**").doesNotContain("NOT applied");
    }

    @Test
    void nothingProtectedMeansNoBlock() {
        ProtectedPaths paths = new ProtectedPaths(new FileService(base.toString(), new ObjectMapper()));
        assertThat(paths.promptBlock()).isNull();
    }
}
