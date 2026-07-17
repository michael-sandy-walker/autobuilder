package com.walpro.autobuilder.core.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SystemResetConfigTest {

    @TempDir
    Path tmp;

    private SystemResetConfig resetConfig;

    @BeforeEach
    void setUp() throws IOException {
        FileService fileService = new FileService(tmp.toString(), new ObjectMapper());
        Files.createDirectories(tmp.resolve("bootstrap"));
        Files.writeString(tmp.resolve("bootstrap/seed.txt"), "seed goal");
        resetConfig = new SystemResetConfig(
                mock(AutobuilderConfigProperties.class),
                fileService,
                mock(GoalPlannerService.class),
                mock(GoalGraphService.class),
                mock(CriteriaVerificationService.class));
    }

    @Test
    void resetSystemForce_keepsTheSymbolImportCache_wipesTheRunState() throws IOException {
        // T4-CD slice 3 (reverses the T4-BM step-6a wipe): resolutions are ecosystem facts, not
        // run facts. The step-6 dependency-fingerprint keying makes staleness self-correcting —
        // entries only serve under their recorded dependency context — and the 6a pollution
        // classes are each closed by later mechanisms (member-token gate, BOM version omission,
        // discovery-first ordering). Run state (output/, meta/, snapshots, mutations) still wipes.
        Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(tmp.resolve("cache/symbol-imports.json"), "{\"Loan\":null}");
        Files.createDirectories(tmp.resolve("output"));
        Files.writeString(tmp.resolve("output/App.java"), "class App {}");
        Files.createDirectories(tmp.resolve("meta"));
        Files.writeString(tmp.resolve("meta/run-testament.json"), "{}");

        resetConfig.resetSystemForce();

        assertThat(tmp.resolve("cache/symbol-imports.json")).as("ecosystem knowledge survives").exists();
        assertThat(tmp.resolve("output/App.java")).doesNotExist();
        assertThat(tmp.resolve("meta/run-testament.json")).as("a reset severs the lineage").doesNotExist();
        assertThat(tmp.resolve("output")).as("base folders recreated").exists();
    }
}
