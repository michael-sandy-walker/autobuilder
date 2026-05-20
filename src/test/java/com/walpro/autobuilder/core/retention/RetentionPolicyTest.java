package com.walpro.autobuilder.core.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.walpro.autobuilder.core.retention.RetentionConfigProperties.DirectoryLimits;
import com.walpro.autobuilder.core.service.AutobuilderService;

class RetentionPolicyTest {

    @TempDir
    Path basePath;

    private RetentionConfigProperties config;
    private AutobuilderService autobuilderService;

    @BeforeEach
    void setup() {
        config = new RetentionConfigProperties();
        config.setEnabled(true);
        config.setMinKeep(2);
        autobuilderService = mock(AutobuilderService.class);
        when(autobuilderService.isCycleInProgress()).thenReturn(false);
    }

    private RetentionPolicy newPolicy() {
        return new RetentionPolicy(config, autobuilderService, basePath.toString());
    }

    @Test
    void deletesEntriesOlderThanMaxAgeDaysButKeepsMinKeepRecent() throws IOException {
        Path snapshots = Files.createDirectory(basePath.resolve("snapshots"));
        List<Path> created = createAged(snapshots, "cycle-",
                List.of(20, 15, 10, 5, 1));

        config.setSnapshots(new DirectoryLimits(7, 0, 0));

        newPolicy().sweep();

        assertThat(Files.exists(created.get(0))).as("20-day-old should be deleted").isFalse();
        assertThat(Files.exists(created.get(1))).as("15-day-old should be deleted").isFalse();
        assertThat(Files.exists(created.get(2))).as("10-day-old should be deleted").isFalse();
        assertThat(Files.exists(created.get(3))).as("5-day-old within min-keep").isTrue();
        assertThat(Files.exists(created.get(4))).as("1-day-old within min-keep").isTrue();
    }

    @Test
    void enforcesMaxCyclesByDeletingOldest() throws IOException {
        Path mutations = Files.createDirectory(basePath.resolve("mutations"));
        List<Path> created = createAged(mutations, "cycle-",
                List.of(10, 9, 8, 7, 6, 5, 4, 3, 2, 1));

        config.setMinKeep(2);
        config.setMutations(new DirectoryLimits(0, 0, 5));

        newPolicy().sweep();

        long remaining = created.stream().filter(Files::exists).count();
        assertThat(remaining).isEqualTo(5);
        assertThat(Files.exists(created.get(created.size() - 1)))
                .as("newest must always survive")
                .isTrue();
    }

    @Test
    void preservesMinKeepEvenWhenAllAreOldEnoughToDelete() throws IOException {
        Path logs = Files.createDirectory(basePath.resolve("logs"));
        List<Path> created = createAged(logs, "log-", List.of(30, 25, 20));

        config.setMinKeep(2);
        config.setLogs(new DirectoryLimits(7, 0, 0));

        newPolicy().sweep();

        long remaining = created.stream().filter(Files::exists).count();
        assertThat(remaining).isEqualTo(2);
    }

    @Test
    void skipsWhenCycleInProgress() throws IOException {
        Path snapshots = Files.createDirectory(basePath.resolve("snapshots"));
        List<Path> created = createAged(snapshots, "cycle-", List.of(30, 25, 20));

        config.setMinKeep(0);
        config.setSnapshots(new DirectoryLimits(7, 0, 0));
        when(autobuilderService.isCycleInProgress()).thenReturn(true);

        newPolicy().runScheduled();

        long remaining = created.stream().filter(Files::exists).count();
        assertThat(remaining).as("nothing should be deleted while cycle in progress")
                .isEqualTo(3);
    }

    @Test
    void disabledFlagSkipsScheduledRun() throws IOException {
        Path snapshots = Files.createDirectory(basePath.resolve("snapshots"));
        List<Path> created = createAged(snapshots, "cycle-", List.of(30, 25));

        config.setEnabled(false);
        config.setMinKeep(0);
        config.setSnapshots(new DirectoryLimits(7, 0, 0));

        newPolicy().runScheduled();

        long remaining = created.stream().filter(Files::exists).count();
        assertThat(remaining).isEqualTo(2);
    }

    @Test
    void missingDirectoryIsSilentlyIgnored() {
        config.setSnapshots(new DirectoryLimits(7, 0, 0));
        newPolicy().sweep();
    }

    private List<Path> createAged(Path parent, String prefix, List<Integer> daysOld) throws IOException {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < daysOld.size(); i++) {
            Path dir = parent.resolve(prefix + i);
            Files.createDirectory(dir);
            Path file = dir.resolve("payload.txt");
            Files.writeString(file, "x".repeat(1024));
            Instant when = Instant.now().minus(daysOld.get(i), ChronoUnit.DAYS);
            Files.setLastModifiedTime(dir, FileTime.from(when));
            Files.setLastModifiedTime(file, FileTime.from(when));
            out.add(dir);
        }
        return out;
    }
}
