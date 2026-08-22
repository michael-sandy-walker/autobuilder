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
    void keepsEverythingWhileTheCorpusSpansFewerActiveDaysThanTheWindow() throws IOException {
        // Five entries on five distinct days. A 7-ACTIVE-day window is not full, so nothing ages
        // out -- even though the oldest is 20 CALENDAR days back. This is the whole change: idle
        // stretches no longer consume the window.
        Path snapshots = Files.createDirectory(basePath.resolve("snapshots"));
        List<Path> created = createAged(snapshots, "cycle-", List.of(20, 15, 10, 5, 1));

        config.setSnapshots(new DirectoryLimits(7, 0, 0));

        newPolicy().sweep();

        for (int i = 0; i < created.size(); i++) {
            assertThat(Files.exists(created.get(i)))
                    .as("entry %d survives: only 5 active days exist against a window of 7", i)
                    .isTrue();
        }
    }

    @Test
    void deletesEntriesBeyondTheNewestNActiveDays() throws IOException {
        // Eight distinct active days, window of 3 -> the newest three days survive, the rest go.
        Path snapshots = Files.createDirectory(basePath.resolve("snapshots"));
        List<Path> created = createAged(snapshots, "cycle-", List.of(30, 20, 12, 9, 3, 2, 1));

        config.setMinKeep(0);
        config.setSnapshots(new DirectoryLimits(3, 0, 0));

        newPolicy().sweep();

        assertThat(Files.exists(created.get(0))).as("30 days back, outside the 3 active days").isFalse();
        assertThat(Files.exists(created.get(1))).as("20 days back, outside").isFalse();
        assertThat(Files.exists(created.get(2))).as("12 days back, outside").isFalse();
        assertThat(Files.exists(created.get(3))).as("9 days back, outside").isFalse();
        assertThat(Files.exists(created.get(4))).as("3rd-newest active day survives").isTrue();
        assertThat(Files.exists(created.get(5))).as("2nd-newest active day survives").isTrue();
        assertThat(Files.exists(created.get(6))).as("newest active day survives").isTrue();
    }

    @Test
    void anIdleGapDoesNotAgeTheCorpusOut() throws IOException {
        // The T6-M case: work, then a long silence, then work again. Under calendar retention the
        // pre-gap days fall out of a 10-day window purely because nothing ran; under active-day
        // retention they are still 4 of the last 5 working days and must survive.
        Path logs = Files.createDirectory(basePath.resolve("logs"));
        List<Path> created = createAged(logs, "run-", List.of(40, 39, 38, 37, 1));

        config.setMinKeep(0);
        config.setLogs(new DirectoryLimits(10, 0, 0));

        newPolicy().sweep();

        for (int i = 0; i < created.size(); i++) {
            assertThat(Files.exists(created.get(i)))
                    .as("entry %d survives an idle gap: 5 active days against a window of 10", i)
                    .isTrue();
        }
    }

    @Test
    void severalEntriesOnOneDayCountAsASingleActiveDay() throws IOException {
        // Otherwise a busy day would consume the window on its own and a burst of runs would
        // delete the very history it belongs to.
        Path logs = Files.createDirectory(basePath.resolve("logs"));
        List<Path> created = createAged(logs, "run-", List.of(9, 9, 9, 9, 5, 1));

        config.setMinKeep(0);
        config.setLogs(new DirectoryLimits(3, 0, 0));

        newPolicy().sweep();

        for (int i = 0; i < created.size(); i++) {
            assertThat(Files.exists(created.get(i)))
                    .as("entry %d survives: the four same-day entries are ONE active day", i)
                    .isTrue();
        }
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
    void preservesMinKeepEvenWhenEveryEntryIsOutsideTheActiveWindow() throws IOException {
        // min-keep still wins over the age rule; only the unit of "age" changed.
        Path snapshots = Files.createDirectory(basePath.resolve("snapshots"));
        List<Path> created = createAged(snapshots, "cycle-", List.of(50, 40, 30, 20, 10));

        config.setMinKeep(2);
        config.setSnapshots(new DirectoryLimits(1, 0, 0));

        newPolicy().sweep();

        assertThat(Files.exists(created.get(0))).as("oldest deleted").isFalse();
        assertThat(Files.exists(created.get(1))).as("second oldest deleted").isFalse();
        assertThat(Files.exists(created.get(2))).as("third oldest deleted").isFalse();
        assertThat(Files.exists(created.get(3))).as("held by min-keep").isTrue();
        assertThat(Files.exists(created.get(4))).as("held by min-keep").isTrue();
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
