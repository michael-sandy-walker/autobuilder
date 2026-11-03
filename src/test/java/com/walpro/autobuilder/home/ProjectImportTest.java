package com.walpro.autobuilder.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectImportTest {

    @TempDir
    Path tmp;

    @Test
    void theCodeIsCopiedAndBuildOutputVersionControlAndLinksAreLeftBehind() throws Exception {
        Path src = tmp.resolve("src-tree");
        Files.createDirectories(src.resolve("src/main/java/x"));
        Files.writeString(src.resolve("pom.xml"), "<project/>");
        Files.writeString(src.resolve("src/main/java/x/A.java"), "class A {}");
        Files.createDirectories(src.resolve(".git/objects"));
        Files.writeString(src.resolve(".git/HEAD"), "ref");
        Files.createDirectories(src.resolve("target/classes"));
        Files.writeString(src.resolve("target/classes/A.class"), "bin");
        Files.createDirectories(src.resolve("web/node_modules/m"));
        Files.writeString(src.resolve("web/node_modules/m/i.js"), "x");
        Path outside = Files.writeString(tmp.resolve("secret.txt"), "not part of the code");
        Files.createSymbolicLink(src.resolve("link.txt"), outside);

        ProjectImport.Copied copied = ProjectImport.into(src, tmp.resolve("proj"), "src/main/**");

        try (var all = Files.walk(tmp.resolve("proj"))) {
            assertThat(all.filter(Files::isRegularFile).map(p -> tmp.resolve("proj").relativize(p).toString()).sorted())
                    .containsExactly("bootstrap/protected-paths.txt", "output/pom.xml", "output/src/main/java/x/A.java");
        }
        assertThat(copied.files()).isEqualTo(2);
        assertThat(copied.leftOut()).isEqualTo(4);
        assertThat(Files.readString(tmp.resolve("proj/bootstrap/protected-paths.txt"))).isEqualTo("src/main/**\n");
        assertThat(Files.readString(src.resolve("src/main/java/x/A.java"))).isEqualTo("class A {}");
    }

    @Test
    void anEmptyOrMissingSourceIsRefusedAndNothingIsWritten() throws Exception {
        Files.createDirectories(tmp.resolve("empty/.git"));
        Files.writeString(tmp.resolve("empty/.git/HEAD"), "ref");

        assertThatThrownBy(() -> ProjectImport.into(tmp.resolve("empty"), tmp.resolve("p1"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no files");
        assertThatThrownBy(() -> ProjectImport.into(tmp.resolve("absent"), tmp.resolve("p2"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a directory");
        assertThat(tmp.resolve("p1")).doesNotExist();
        assertThat(tmp.resolve("p2")).doesNotExist();
    }
}
