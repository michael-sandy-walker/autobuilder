package com.walpro.autobuilder.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.walpro.autobuilder.agent.merge.MergeAgent;
import com.walpro.autobuilder.agent.merge.model.MergeInput;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.model.SubgoalMetadata;
import com.walpro.autobuilder.persistence.CycleContext;
import com.walpro.autobuilder.persistence.MutationSnapshotService;
import com.walpro.autobuilder.service.FileService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MergeCoordinatorTest {

    @TempDir
    Path tempDir;

    FileService fileService;
    MergeCoordinator mergeCoordinator;

    ObjectMapper objectMapper;

    @BeforeEach
    void setup() throws IOException {
        fileService = new FileService(tempDir.toString());
        fileService.ensureDefaultFolders();

        MergeAgent fakeMergeAgent = new MergeAgent() {
            @Override
            public MergeOutcome merge(MergeInput input) {
                throw new UnsupportedOperationException("Should not be called in this test.");
            }
        };

        // ✅ Use properly configured ObjectMapper for both read/write
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        MutationSnapshotService mutationSnapshotService = new MutationSnapshotService(fileService, objectMapper);
        mergeCoordinator = new MergeCoordinator(fakeMergeAgent, fileService, mutationSnapshotService);
    }

    @Test
    void promotesNewFileFromSingleMutationBranch() throws IOException {
        // Arrange
        String cycleId = "cycle-1";
        String filePath = "src/test/java/FooTest.java";
        String fileContent = "public class FooTest {}";

        Path mutationPath = tempDir.resolve("mutations").resolve(cycleId).resolve(filePath);
        Files.createDirectories(mutationPath.getParent());
        Files.writeString(mutationPath, fileContent);

        CycleContext cycleContext = CycleContext.of(
                cycleId,
                0.0,
                "test merge promotion",
                false,
                new SubgoalMetadata("SG-001", "Promote new file test")
        );

        Path mutationDir = tempDir.resolve("mutations").resolve(cycleId);
        Files.writeString(
                mutationDir.resolve("meta.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cycleContext)
        );

        // Act
        CycleContextData contextData = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(tempDir.resolve("output"))
                .build();
        mergeCoordinator.mergeCompletedBranch(contextData);

        // Assert
        Path promotedFile = tempDir.resolve("output").resolve(filePath);
        assertThat(Files.exists(promotedFile)).isTrue();
        assertThat(Files.readString(promotedFile)).isEqualTo(fileContent);

        Path updatedMeta = tempDir.resolve("mutations").resolve(cycleId).resolve("meta.json");
        String metaJson = Files.readString(updatedMeta);
        assertThat(metaJson).contains("\"merged\" : true");
    }

    @Test
    void skipsFileWithNoVariants() throws IOException {
        String cycleId = "noop";
        String filePath = "src/main/java/OrphanFile.java";
        String originalContent = "public class OrphanFile {}";

        // Setup output folder with a file
        Path outputFile = tempDir.resolve("output").resolve(filePath);
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, originalContent);

        // Setup a mutation folder with valid meta.json but NO variants for the target file
        Path mutationDir = tempDir.resolve("mutations").resolve(cycleId);
        Files.createDirectories(mutationDir);
        CycleContext context = CycleContext.of(
                cycleId,
                0.0,
                "No variants here",
                false,
                new SubgoalMetadata("SG-999", "no variant")
        );
        Files.writeString(
                mutationDir.resolve("meta.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context)
        );

        CycleContextData contextData = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(tempDir.resolve("output"))
                .build();

        mergeCoordinator.mergeCompletedBranch(contextData);

        // ✅ File should remain untouched
        assertThat(Files.readString(outputFile)).isEqualTo(originalContent);

        // ✅ Meta should remain unmarked
        Path updatedMeta = mutationDir.resolve("meta.json");
        String updatedMetaContent = Files.readString(updatedMeta);
        assertThat(updatedMetaContent).contains("\"merged\" : false");
    }

    @Test
    void mergesVariantsAndMarksAllCyclesAsMerged() throws IOException {
        String filePath = "service/HelloService.java";
        String variant1 = "class HelloService { void a() {} }";
        String variant2 = "class HelloService { void b() {} }";
        String merged = "class HelloService { void a() {} void b() {} }";

        // Create two mutation cycles
        String[] cycleIds = { "c1", "c2" };
        for (int i = 0; i < cycleIds.length; i++) {
            Path variantPath = tempDir.resolve("mutations").resolve(cycleIds[i]).resolve(filePath);
            Files.createDirectories(variantPath.getParent());
            Files.writeString(variantPath, i == 0 ? variant1 : variant2);

            CycleContext ctx = CycleContext.of(cycleIds[i], 0.0, "", false,
                    new SubgoalMetadata("SG-" + i, "desc"));
            Files.writeString(variantPath.getParent().getParent().resolve("meta.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(ctx));
        }

        // Override coordinator with real merge agent
        MergeAgent mergeAgent = input -> new MergeOutcome(true, merged, "synthetic");
        MutationSnapshotService snapshotService = new MutationSnapshotService(fileService, objectMapper);
        mergeCoordinator = new MergeCoordinator(mergeAgent, fileService, snapshotService);

        CycleContextData contextData = CycleContextData.builder()
                .cycleName("merge-test")
                .outputPath(tempDir.resolve("output"))
                .build();

        mergeCoordinator.mergeCompletedBranch(contextData);

        Path mergedFile = tempDir.resolve("output").resolve(filePath);
        assertThat(Files.readString(mergedFile)).isEqualTo(merged);

        for (String cycleId : cycleIds) {
            Path metaFile = tempDir.resolve("mutations").resolve(cycleId).resolve("meta.json");
            assertThat(Files.readString(metaFile)).contains("\"merged\" : true");
        }
    }

    @Test
    void doesNotMarkCycleWithInvalidMetadataAsMerged() throws IOException {
        String cycleId = "incomplete";
        String filePath = "src/main/java/Incomplete.java";
        String fileContent = "class Incomplete {}";

        Path mutationPath = tempDir.resolve("mutations").resolve(cycleId).resolve(filePath);
        Files.createDirectories(mutationPath.getParent());
        Files.writeString(mutationPath, fileContent);

        // ❗ Malformed: missing subgoalMetadata
        String brokenMeta = """
        {
          "name": "%s",
          "timestamp": "2025-07-06T00:00:00Z",
          "score": 0.0,
          "comment": "broken",
          "merged": false
        }
        """.formatted(cycleId);

        Path metaPath = mutationPath.getParent().getParent().resolve("meta.json");
        Files.writeString(metaPath, brokenMeta); // ✅ Actually write it!

        CycleContextData contextData = CycleContextData.builder()
                .cycleName(cycleId)
                .outputPath(tempDir.resolve("output"))
                .build();

        mergeCoordinator.mergeCompletedBranch(contextData);

        // ✅ It should still exist and not be marked as merged
        CycleContext result = objectMapper.readValue(metaPath.toFile(), CycleContext.class);
        assertThat(result.isMerged()).isFalse();    }

}
