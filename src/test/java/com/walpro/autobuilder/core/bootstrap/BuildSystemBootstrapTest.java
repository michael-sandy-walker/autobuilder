package com.walpro.autobuilder.core.bootstrap;

import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-BT: BuildSystemBootstrap — copies bootstrap/build/ into output/ at startup.
 */
class BuildSystemBootstrapTest {

    @TempDir
    Path baseDir;

    private FileService fileService;
    private BuildSystemBootstrap bootstrap;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(baseDir.resolve("output"));
        Files.createDirectories(baseDir.resolve("bootstrap"));
        Files.createDirectories(baseDir.resolve("meta"));

        fileService = new FileService(baseDir.toString(), new com.fasterxml.jackson.databind.ObjectMapper());
        bootstrap = new BuildSystemBootstrap(fileService);
        setMode(bootstrap, "auto");
    }

    @Test
    void copiesAllFilesFromBootstrapBuildToOutput() throws IOException {
        // Given: bootstrap/build/ with pom.xml and a nested directory entry
        Path buildSeed = baseDir.resolve("bootstrap/build");
        Files.createDirectories(buildSeed);
        Files.writeString(buildSeed.resolve("pom.xml"), "<project/>");
        Path nested = buildSeed.resolve("src/main/resources");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("application.properties"), "server.port=8080");

        bootstrap.seedBuildSystemIfNecessary();

        assertThat(baseDir.resolve("output/pom.xml")).exists()
                .hasContent("<project/>");
        assertThat(baseDir.resolve("output/src/main/resources/application.properties")).exists()
                .hasContent("server.port=8080");
        assertThat(baseDir.resolve("meta/build-bootstrap.done")).exists();
    }

    @Test
    void isIdempotent_skipsWhenFlagAlreadyPresent() throws IOException {
        Path buildSeed = baseDir.resolve("bootstrap/build");
        Files.createDirectories(buildSeed);
        Files.writeString(buildSeed.resolve("pom.xml"), "<project/>");

        // First call seeds and writes flag
        bootstrap.seedBuildSystemIfNecessary();
        assertThat(baseDir.resolve("output/pom.xml")).exists();

        // Overwrite target to verify it is NOT overwritten on second call
        Files.writeString(baseDir.resolve("output/pom.xml"), "modified-by-evolution");
        bootstrap.seedBuildSystemIfNecessary();

        // Flag existed → skip; modified content must be preserved
        assertThat(baseDir.resolve("output/pom.xml")).hasContent("modified-by-evolution");
    }

    @Test
    void skipsWhenNoBuildSeedDirectory() throws IOException {
        // bootstrap/build/ does not exist — output/ stays empty
        bootstrap.seedBuildSystemIfNecessary();

        assertThat(Files.list(baseDir.resolve("output")).count()).isZero();
        assertThat(baseDir.resolve("meta/build-bootstrap.done")).doesNotExist();
    }

    @Test
    void skipsWhenModeIsNone() throws IOException {
        Path buildSeed = baseDir.resolve("bootstrap/build");
        Files.createDirectories(buildSeed);
        Files.writeString(buildSeed.resolve("pom.xml"), "<project/>");

        setMode(bootstrap, "none");
        bootstrap.seedBuildSystemIfNecessary();

        assertThat(baseDir.resolve("output/pom.xml")).doesNotExist();
    }

    // ---- helper ----

    private static void setMode(BuildSystemBootstrap b, String mode) {
        try {
            Field f = BuildSystemBootstrap.class.getDeclaredField("buildSystemMode");
            f.setAccessible(true);
            f.set(b, mode);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
