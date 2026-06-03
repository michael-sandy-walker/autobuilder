package com.walpro.autobuilder.core.logging;

import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.context.ApplicationListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T5-AE: rotate the previous run's live log files out of the way before logback opens fresh
 * {@code autobuilder.log} / {@code behaviour.ndjson} targets for the current JVM.
 *
 * <p>Registered via {@code META-INF/spring.factories} (NOT {@code @Component}) so it fires on
 * {@link ApplicationStartingEvent} — the earliest Spring Boot event, dispatched before the
 * application context exists and before logback initialises from {@code logback-spring.xml}.
 * Renaming a still-open file would conflict with logback's write handle; a {@code @Component}
 * bean wouldn't be instantiated in time.
 *
 * <p>For each of {@code autobuilder.log} and {@code behaviour.ndjson} under
 * {@code ${FILE_BASE_PATH}/logs/}: if the file exists and is non-empty, extract a start-time
 * suffix from the first line's timestamp and rename to {@code <stem>-<startTime>.<ext>}.
 * Missing or empty files are no-ops. Unparseable first lines fall back to the rotator's own
 * invocation instant so the file is never lost.
 */
public class StartupLogRotator implements ApplicationListener<ApplicationStartingEvent> {

    private static final String LOG_FILE = "autobuilder.log";
    private static final String NDJSON_FILE = "behaviour.ndjson";
    private static final String LOGS_SUBDIR = "logs";

    /** Matches the log line prefix: {@code 2026-06-01 13:27:47.123 [thread] ...} */
    private static final Pattern LOG_FIRST_TIMESTAMP = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})");

    /** Matches the behaviour ndjson first record's timestamp field — pretty-printed multi-line JSON:
     *  {@code "timestamp" : "2026-06-01T13:27:51.821..."} */
    private static final Pattern NDJSON_FIRST_TIMESTAMP = Pattern.compile(
            "\"timestamp\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2})");

    private static final DateTimeFormatter SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        Path logsDir = resolveLogsDir();
        if (logsDir == null) return;
        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            System.err.println("T5-AE: failed to create logs dir " + logsDir + ": " + e.getMessage());
            return;
        }
        rotate(logsDir.resolve(LOG_FILE), LOG_FIRST_TIMESTAMP);
        rotate(logsDir.resolve(NDJSON_FILE), NDJSON_FIRST_TIMESTAMP);
    }

    /** Visible for testing: resolves {@code ${FILE_BASE_PATH:/data/firestarter}/logs}. */
    Path resolveLogsDir() {
        String basePath = System.getenv("FILE_BASE_PATH");
        if (basePath == null || basePath.isBlank()) basePath = "/data/firestarter";
        return Path.of(basePath, LOGS_SUBDIR);
    }

    /** Visible for testing: rotates a single file if it's non-empty. */
    void rotate(Path liveFile, Pattern timestampPattern) {
        try {
            if (!Files.exists(liveFile)) return;
            if (Files.size(liveFile) == 0) return;
        } catch (IOException e) {
            System.err.println("T5-AE: failed to stat " + liveFile + ": " + e.getMessage());
            return;
        }

        String suffix = extractTimestampSuffix(liveFile, timestampPattern)
                .orElseGet(() -> LocalDateTime.now().format(SUFFIX_FORMAT));

        String name = liveFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0) ? name.substring(0, dot) : name;
        String ext = (dot > 0) ? name.substring(dot) : "";

        Path target = liveFile.resolveSibling(stem + "-" + suffix + ext);
        int seq = 2;
        while (Files.exists(target)) {
            target = liveFile.resolveSibling(stem + "-" + suffix + "-" + seq + ext);
            seq++;
        }

        try {
            Files.move(liveFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(liveFile, target);
            } catch (IOException e2) {
                System.err.println("T5-AE: failed to rotate " + liveFile + " → " + target + ": " + e2.getMessage());
            }
        }
    }

    /** Visible for testing: reads the first line and matches the pattern to extract a suffix. */
    java.util.Optional<String> extractTimestampSuffix(Path file, Pattern pattern) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // For the ndjson pretty-printed format, the timestamp field is on line 2, not line 1.
            // Read up to the first 20 lines looking for a match — cheap and bounded.
            for (int i = 0; i < 20; i++) {
                String line = reader.readLine();
                if (line == null) break;
                Matcher m = pattern.matcher(line);
                if (m.find()) {
                    return java.util.Optional.of(m.group(1) + "_" + m.group(2).replace(':', '-'));
                }
            }
        } catch (IOException ignored) {
            // Fall through to fallback.
        }
        return java.util.Optional.empty();
    }
}
