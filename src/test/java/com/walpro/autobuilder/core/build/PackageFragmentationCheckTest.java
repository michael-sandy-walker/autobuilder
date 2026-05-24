package com.walpro.autobuilder.core.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BH: unit tests for the shared {@link PackageFragmentationCheck} helper. Exercise the
 * helper in isolation against a real {@link FileService} backed by {@link TempDir}, so the
 * "scan output, compare simple names across packages" logic is verified once, decoupled from
 * the merge-time and probe-time call sites that consume it.
 */
class PackageFragmentationCheckTest {

    @TempDir Path tmp;

    private FileService fileService;
    private PackageFragmentationCheck check;

    @BeforeEach
    void setUp() throws IOException {
        fileService = new FileService(tmp.toString(), new ObjectMapper());
        check = new PackageFragmentationCheck(fileService);
        Files.createDirectories(tmp.resolve("output"));
    }

    @Test
    void check_returnsConflict_whenProposedFileSplitsExistingTypeAcrossPackages() throws IOException {
        // BookService.java already lives at com.example.demo; the LLM proposes a second
        // BookService.java at com.example.demo.service. Helper must surface the collision.
        writeOutput("src/main/java/com/example/demo/BookService.java", "package com.example.demo; public class BookService {}");
        Map<String, String> proposed = Map.of(
                "src/main/java/com/example/demo/service/BookService.java",
                "package com.example.demo.service; public class BookService {}");

        Optional<PackageFragmentationCheck.Conflict> result = check.check(proposed, "output");

        assertThat(result).isPresent();
        PackageFragmentationCheck.Conflict c = result.get();
        assertThat(c.simpleName()).isEqualTo("BookService");
        assertThat(c.candidatePath()).isEqualTo("src/main/java/com/example/demo/service/BookService.java");
        assertThat(c.existingPath()).isEqualTo("src/main/java/com/example/demo/BookService.java");
    }

    @Test
    void check_returnsEmpty_whenProposedFileHasUniqueSimpleName() throws IOException {
        writeOutput("src/main/java/com/example/demo/Bar.java", "package com.example.demo; public class Bar {}");
        Map<String, String> proposed = Map.of(
                "src/main/java/com/example/demo/loan/Foo.java",
                "package com.example.demo.loan; public class Foo {}");

        assertThat(check.check(proposed, "output")).isEmpty();
    }

    @Test
    void check_returnsEmpty_whenProposedFileOverwritesExistingAtSamePath() throws IOException {
        // Modifying a file at its own path is not fragmentation — the comparison is "different
        // directory, same simple name", and the candidate is its own existing file.
        writeOutput("src/main/java/com/example/demo/Foo.java", "package com.example.demo; public class Foo {}");
        Map<String, String> proposed = Map.of(
                "src/main/java/com/example/demo/Foo.java",
                "package com.example.demo; public class Foo { /* edited */ }");

        assertThat(check.check(proposed, "output")).isEmpty();
    }

    @Test
    void check_ignoresNonJavaFiles() throws IOException {
        writeOutput("src/main/resources/application.yml", "key: value");
        writeOutput("src/main/java/com/example/demo/Foo.java", "package com.example.demo; public class Foo {}");
        Map<String, String> proposed = Map.of(
                "src/main/resources/static/Foo.properties", "key=value",
                "src/main/java/com/example/demo/loan/Bar.java", "package com.example.demo.loan; public class Bar {}");

        assertThat(check.check(proposed, "output")).isEmpty();
    }

    @Test
    void check_returnsEmpty_forNullOrEmptyProposedFiles() {
        assertThat(check.check(null, "output")).isEmpty();
        assertThat(check.check(Map.of(), "output")).isEmpty();
    }

    @Test
    void check_detectsCollisionAcrossDeeplyNestedPackages() throws IOException {
        writeOutput("src/main/java/com/example/demo/a/b/c/Item.java", "package com.example.demo.a.b.c; public class Item {}");
        Map<String, String> proposed = Map.of(
                "src/main/java/com/example/demo/x/y/Item.java",
                "package com.example.demo.x.y; public class Item {}");

        Optional<PackageFragmentationCheck.Conflict> result = check.check(proposed, "output");

        assertThat(result).isPresent();
        assertThat(result.get().simpleName()).isEqualTo("Item");
    }

    private void writeOutput(String relPath, String content) throws IOException {
        Path p = tmp.resolve("output").resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }
}
