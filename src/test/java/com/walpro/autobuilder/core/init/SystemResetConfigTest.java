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
    void resetSystemForce_wipesTheSymbolImportCacheWithTheRunState() throws IOException {
        // T4-BM step 6a: cache/ previously SURVIVED resets — bare-simple-name entries carried
        // one target project's domain-type NONE rows and stale version pins into the next
        // run's worldview (22 of 25 inspected entries). The cache is run state, not knowledge.
        Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(tmp.resolve("cache/symbol-imports.json"), "{\"Loan\":null}");
        Files.createDirectories(tmp.resolve("output"));
        Files.writeString(tmp.resolve("output/App.java"), "class App {}");

        resetConfig.resetSystemForce();

        assertThat(tmp.resolve("cache/symbol-imports.json")).doesNotExist();
        assertThat(tmp.resolve("output/App.java")).doesNotExist();
        assertThat(tmp.resolve("output")).as("base folders recreated").exists();
    }
}
