package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** field123: of three modules the first jar was a command-line tool, and the loop's gate found no artifact at all. */
class RunnableRootTest {

    @TempDir
    Path tree;

    @Test
    void theMarkerNamesTheModuleThatRuns_elseTheRoot() throws Exception {
        assertThat(ProjectLayout.runnableRoot(tree)).isEqualTo(tree);

        Files.createDirectories(tree.resolve("library"));
        Files.writeString(tree.resolve(ProjectLayout.RUN_MODULE_FILE), "library\n");
        assertThat(ProjectLayout.runnableRoot(tree)).isEqualTo(tree.resolve("library"));

        Files.writeString(tree.resolve(ProjectLayout.RUN_MODULE_FILE), "gone\n");
        assertThat(ProjectLayout.runnableRoot(tree)).isEqualTo(tree);
    }
}
