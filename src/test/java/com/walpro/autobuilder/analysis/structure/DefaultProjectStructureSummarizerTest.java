package com.walpro.autobuilder.analysis.structure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultProjectStructureSummarizerTest {

    private Path tempDir;
    private DefaultProjectStructureSummarizer summarizer;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("test-output");
        FileService fileService = new FileService(tempDir.toString(), new ObjectMapper());
        summarizer = new DefaultProjectStructureSummarizer(fileService);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void detectBasePackage_returnsNull_whenNoJavaFiles() {
        assertThat(summarizer.detectBasePackage(tempDir)).isNull();
    }

    @Test
    void detectBasePackage_returnsSinglePackage_whenOneJavaFile() throws IOException {
        Files.writeString(tempDir.resolve("Foo.java"),
                "package com.example.library;\npublic class Foo {}");

        assertThat(summarizer.detectBasePackage(tempDir)).isEqualTo("com.example.library");
    }

    @Test
    void detectBasePackage_returnsCommonPrefix_whenFilesHaveDifferentSubpackages() throws IOException {
        Files.writeString(tempDir.resolve("Foo.java"),
                "package com.example.library;\npublic class Foo {}");
        Path serviceDir = Files.createDirectories(tempDir.resolve("service"));
        Files.writeString(serviceDir.resolve("FooService.java"),
                "package com.example.library.service;\npublic class FooService {}");

        assertThat(summarizer.detectBasePackage(tempDir)).isEqualTo("com.example.library");
    }

    @Test
    void detectBasePackage_returnsNull_whenPackagesShareNoCommonSegment() throws IOException {
        Files.writeString(tempDir.resolve("Foo.java"),
                "package com.example.library;\npublic class Foo {}");
        Files.writeString(tempDir.resolve("Bar.java"),
                "package org.different;\npublic class Bar {}");

        assertThat(summarizer.detectBasePackage(tempDir)).isNull();
    }

    @Test
    void detectBasePackage_returnsNull_whenJavaFilesHaveNoPackageDeclaration() throws IOException {
        Files.writeString(tempDir.resolve("Unpackaged.java"),
                "public class Unpackaged {}");

        assertThat(summarizer.detectBasePackage(tempDir)).isNull();
    }

    @Test
    void detectBasePackage_ignoresNonJavaFiles() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "some readme");
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        assertThat(summarizer.detectBasePackage(tempDir)).isNull();
    }

    @Test
    void detectBasePackage_returnsCommonPrefix_acrossMultipleDepthLevels() throws IOException {
        Path controllerDir = Files.createDirectories(tempDir.resolve("controller"));
        Path modelDir = Files.createDirectories(tempDir.resolve("model"));
        Files.writeString(controllerDir.resolve("TaskController.java"),
                "package com.example.library.controller;\npublic class TaskController {}");
        Files.writeString(modelDir.resolve("Task.java"),
                "package com.example.library.model;\npublic class Task {}");
        Files.writeString(tempDir.resolve("LibraryApp.java"),
                "package com.example.library;\npublic class LibraryApp {}");

        assertThat(summarizer.detectBasePackage(tempDir)).isEqualTo("com.example.library");
    }
}
