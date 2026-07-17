package com.walpro.autobuilder.core.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CJ slice 1: run/lineage identity semantics — mint-on-absent, inherit-on-present (the
 * resume path), renew-on-reset, and never-throws under an unwritable meta/.
 */
class RunIdentityServiceTest {

    @TempDir
    Path tmp;

    private FileService fileService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fileService = new FileService(tmp.toString(), objectMapper);
    }

    @Test
    void lineageId_mintsAndPersists_whenNoIdentityFileExists() throws IOException {
        RunIdentityService service = new RunIdentityService(fileService, objectMapper);

        String lineage = service.lineageId();

        assertThat(lineage).startsWith("lineage-");
        assertThat(tmp.resolve("meta/run-identity.json")).exists();
        RunIdentityService.Identity stored = objectMapper.readValue(
                Files.readString(tmp.resolve("meta/run-identity.json")), RunIdentityService.Identity.class);
        assertThat(stored.lineageId()).isEqualTo(lineage);
        assertThat(stored.mintedAt()).isNotBlank();
        // Stable within the JVM.
        assertThat(service.lineageId()).isEqualTo(lineage);
    }

    @Test
    void lineageId_inheritsFromTheIdentityFile_theResumePath() throws IOException {
        Files.createDirectories(tmp.resolve("meta"));
        Files.writeString(tmp.resolve("meta/run-identity.json"),
                "{\"lineageId\":\"lineage-parent\",\"mintedAt\":\"2026-07-17T00:00:00Z\"}");

        RunIdentityService service = new RunIdentityService(fileService, objectMapper);

        assertThat(service.lineageId()).isEqualTo("lineage-parent");
    }

    @Test
    void lineageId_mintsFresh_whenTheIdentityFileIsUnreadable() throws IOException {
        Files.createDirectories(tmp.resolve("meta"));
        Files.writeString(tmp.resolve("meta/run-identity.json"), "not json at all");

        RunIdentityService service = new RunIdentityService(fileService, objectMapper);

        assertThat(service.lineageId()).startsWith("lineage-");
    }

    @Test
    void runId_isMintedPerInstance_andDiffersFromLineage() {
        RunIdentityService first = new RunIdentityService(fileService, objectMapper);
        RunIdentityService second = new RunIdentityService(fileService, objectMapper);

        assertThat(first.runId()).startsWith("run-").isNotEqualTo(second.runId());
        assertThat(first.runId()).isEqualTo(first.runId());
    }

    @Test
    void renewLineage_seversAndPersists_aSecondBootInherits() throws IOException {
        RunIdentityService parent = new RunIdentityService(fileService, objectMapper);
        String before = parent.lineageId();

        String renewed = parent.renewLineage();

        assertThat(renewed).isNotEqualTo(before);
        assertThat(parent.lineageId()).isEqualTo(renewed);
        // A later boot (fresh instance over the same tree) inherits the renewed lineage.
        RunIdentityService child = new RunIdentityService(fileService, objectMapper);
        assertThat(child.lineageId()).isEqualTo(renewed);
        assertThat(child.runId()).isNotEqualTo(parent.runId());
    }

    @Test
    void lineageId_survivesInMemory_whenPersistenceFails() throws IOException {
        // A regular FILE named "meta" makes ensureFolderExists/writeFile fail underneath.
        Files.writeString(tmp.resolve("meta"), "not a directory");

        RunIdentityService service = new RunIdentityService(fileService, objectMapper);

        String lineage = service.lineageId();
        assertThat(lineage).startsWith("lineage-");
        assertThat(service.lineageId()).isEqualTo(lineage);
    }
}
