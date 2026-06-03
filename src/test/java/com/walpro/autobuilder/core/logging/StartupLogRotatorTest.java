package com.walpro.autobuilder.core.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-AE: unit tests for {@link StartupLogRotator}. Drives the package-private {@code rotate}
 * helper directly with {@code @TempDir} fixtures so we don't need a real Spring application
 * context (the listener fires at {@code ApplicationStartingEvent}, before the context exists).
 */
class StartupLogRotatorTest {

    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})");
    private static final Pattern NDJSON_PATTERN = Pattern.compile(
            "\"timestamp\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2})");

    @Test
    void rotate_existingLogFile_renamedToTimestampedArchive(@TempDir Path tmp) throws IOException {
        Path live = tmp.resolve("autobuilder.log");
        Files.writeString(live,
                "2026-06-01 13:27:47.123 [main] INFO  c.w.a.SomeClass - first line\n"
                        + "2026-06-01 13:27:48.456 [main] INFO  c.w.a.SomeClass - second line\n");

        new StartupLogRotator().rotate(live, LOG_PATTERN);

        assertThat(live).doesNotExist();
        Path archived = tmp.resolve("autobuilder-2026-06-01_13-27-47.log");
        assertThat(archived).exists();
        assertThat(Files.readString(archived)).contains("first line");
    }

    @Test
    void rotate_existingNdjsonFile_extractsTimestampFromJsonField(@TempDir Path tmp) throws IOException {
        Path live = tmp.resolve("behaviour.ndjson");
        // Pretty-printed multi-line JSON (matches the real behaviour ndjson shape).
        Files.writeString(live,
                "{\n"
                        + "  \"timestamp\" : \"2026-06-01T13:27:51.821435092+02:00\",\n"
                        + "  \"cycleId\" : \"cycle-1780313271793\",\n"
                        + "  \"event\" : \"cycle_start\"\n"
                        + "}\n");

        new StartupLogRotator().rotate(live, NDJSON_PATTERN);

        assertThat(live).doesNotExist();
        Path archived = tmp.resolve("behaviour-2026-06-01_13-27-51.ndjson");
        assertThat(archived).exists();
    }

    @Test
    void rotate_missingFile_isNoOp(@TempDir Path tmp) {
        Path live = tmp.resolve("autobuilder.log");
        new StartupLogRotator().rotate(live, LOG_PATTERN);
        // No exception, no archive created.
        assertThat(live).doesNotExist();
        try (var stream = Files.list(tmp)) {
            assertThat(stream).isEmpty();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void rotate_emptyFile_isNoOp(@TempDir Path tmp) throws IOException {
        Path live = tmp.resolve("autobuilder.log");
        Files.createFile(live);
        assertThat(Files.size(live)).isZero();

        new StartupLogRotator().rotate(live, LOG_PATTERN);

        // Empty file is left alone — no point archiving 0 bytes.
        assertThat(live).exists();
        try (var stream = Files.list(tmp)) {
            assertThat(stream).hasSize(1);
        }
    }

    @Test
    void rotate_unparseableFirstLine_fallsBackToCurrentInstant(@TempDir Path tmp) throws IOException {
        Path live = tmp.resolve("autobuilder.log");
        Files.writeString(live, "garbage first line with no timestamp\n");

        new StartupLogRotator().rotate(live, LOG_PATTERN);

        assertThat(live).doesNotExist();
        // Some archived file exists with the autobuilder-<timestamp>.log shape.
        List<Path> archives;
        try (var stream = Files.list(tmp)) {
            archives = stream.toList();
        }
        assertThat(archives).hasSize(1);
        String name = archives.get(0).getFileName().toString();
        assertThat(name).matches("autobuilder-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.log");
    }

    @Test
    void rotate_destinationAlreadyExists_appendsNumericSuffix(@TempDir Path tmp) throws IOException {
        Path live = tmp.resolve("autobuilder.log");
        Files.writeString(live, "2026-06-01 13:27:47.123 [main] INFO  - same-second restart\n");
        // Pre-create the target archive so the rotator has to fall back.
        Path collision = tmp.resolve("autobuilder-2026-06-01_13-27-47.log");
        Files.writeString(collision, "previous archive content\n");

        new StartupLogRotator().rotate(live, LOG_PATTERN);

        assertThat(live).doesNotExist();
        assertThat(collision).exists().hasContent("previous archive content\n");
        Path withSuffix = tmp.resolve("autobuilder-2026-06-01_13-27-47-2.log");
        assertThat(withSuffix).exists();
        assertThat(Files.readString(withSuffix)).contains("same-second restart");
    }

    @Test
    void extractTimestampSuffix_logLine_pullsFirstTimestamp(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("autobuilder.log");
        Files.writeString(file, "2026-06-01 13:27:47.123 [main] INFO - x\n");

        var suffix = new StartupLogRotator().extractTimestampSuffix(file, LOG_PATTERN);

        assertThat(suffix).contains("2026-06-01_13-27-47");
    }

    @Test
    void extractTimestampSuffix_ndjson_findsTimestampOnLaterLine(@TempDir Path tmp) throws IOException {
        // Real behaviour ndjson opens with `{` then the timestamp on line 2 — make sure we
        // don't bail after only inspecting line 1.
        Path file = tmp.resolve("behaviour.ndjson");
        Files.writeString(file, "{\n  \"timestamp\" : \"2026-06-01T13:27:51.821\",\n  \"event\":\"x\"\n}\n",
                StandardCharsets.UTF_8);

        var suffix = new StartupLogRotator().extractTimestampSuffix(file, NDJSON_PATTERN);

        assertThat(suffix).contains("2026-06-01_13-27-51");
    }
}
