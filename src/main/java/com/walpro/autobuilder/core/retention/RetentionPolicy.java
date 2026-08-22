package com.walpro.autobuilder.core.retention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.retention.RetentionConfigProperties.DirectoryLimits;
import com.walpro.autobuilder.core.service.AutobuilderService;

/**
 * T5-Q: prunes {@code firestarter/logs|snapshots|mutations|responses} under
 * {@code file.base-path}. Each directory has age + size/count limits and a
 * minimum-keep floor that always preserves the most recent N entries.
 *
 * <p>Runs on a fixed schedule. Skips while a cycle is actively executing so it
 * never races a snapshot/mutation write.</p>
 */
@Component
public class RetentionPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicy.class);

    private final RetentionConfigProperties config;
    private final AutobuilderService autobuilderService;
    private final Path basePath;

    public RetentionPolicy(
            RetentionConfigProperties config,
            @Lazy AutobuilderService autobuilderService,
            @Value("${file.base-path:}") String basePath) {
        this.config = config;
        this.autobuilderService = autobuilderService;
        this.basePath = (basePath == null || basePath.isBlank()) ? null : Paths.get(basePath);
    }

    @Scheduled(
            initialDelayString = "${autobuilder.retention.schedule-initial-delay-ms:3600000}",
            fixedDelayString = "${autobuilder.retention.schedule-delay-ms:86400000}")
    public void runScheduled() {
        if (!config.isEnabled()) {
            return;
        }
        if (autobuilderService != null && autobuilderService.isCycleInProgress()) {
            log.debug("Retention skipped — cycle in progress.");
            return;
        }
        sweep();
    }

    public void sweep() {
        if (basePath == null) {
            log.debug("Retention skipped — file.base-path not set.");
            return;
        }
        sweepDirectory("logs", config.getLogs());
        sweepDirectory("snapshots", config.getSnapshots());
        sweepDirectory("mutations", config.getMutations());
        sweepDirectory("responses", config.getResponses());
    }

    void sweepDirectory(String name, DirectoryLimits limits) {
        if (limits == null) {
            return;
        }
        Path dir = basePath.resolve(name);
        if (!Files.isDirectory(dir)) {
            return;
        }

        List<Entry> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream.map(Entry::of)
                    .filter(e -> e != null)
                    .sorted(Comparator.comparing(Entry::lastModified).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("Retention failed listing {}: {}", dir, e.getMessage());
            return;
        }

        int minKeep = Math.max(0, config.getMinKeep());
        if (entries.size() <= minKeep) {
            return;
        }

        List<Entry> candidates = new ArrayList<>(entries.subList(minKeep, entries.size()));
        List<Entry> deleted = new ArrayList<>();

        if (limits.getMaxActiveDays() > 0) {
            // Count ACTIVE days, not calendar days: the window is the last N days on which this
            // directory actually received something, so a gap where nothing ran does not age the
            // corpus out. A calendar window silently shortens the record across idle stretches --
            // measured 2026-08-22, when only 4 of 28 failing runs still had a log because the
            // failures predated a 10-CALENDAR-day window that idle days had eaten into.
            // This can only ever retain MORE than the calendar form, never less.
            LocalDate cutoffDay = activeDayCutoff(entries, limits.getMaxActiveDays());
            if (cutoffDay != null) {
                for (Entry e : new ArrayList<>(candidates)) {
                    if (dayOf(e).isBefore(cutoffDay)) {
                        if (delete(e)) {
                            deleted.add(e);
                            candidates.remove(e);
                        }
                    }
                }
            }
        }

        if (limits.getMaxCycles() > 0) {
            int over = candidates.size() + minKeep - limits.getMaxCycles();
            while (over > 0 && !candidates.isEmpty()) {
                Entry oldest = candidates.remove(candidates.size() - 1);
                if (delete(oldest)) {
                    deleted.add(oldest);
                }
                over--;
            }
        }

        if (limits.getMaxSizeMb() > 0) {
            long limitBytes = (long) limits.getMaxSizeMb() * 1024L * 1024L;
            long total = entries.stream()
                    .filter(e -> !deleted.contains(e))
                    .mapToLong(Entry::sizeBytes)
                    .sum();
            int idx = candidates.size() - 1;
            while (total > limitBytes && idx >= 0) {
                Entry e = candidates.get(idx);
                if (delete(e)) {
                    total -= e.sizeBytes();
                    deleted.add(e);
                }
                idx--;
            }
        }

        if (!deleted.isEmpty()) {
            log.info("Retention {}: removed {} entries (kept {}).",
                    name, deleted.size(), entries.size() - deleted.size());
        }
    }

    /** The local day an entry belongs to. Retention groups by day, so the unit must be a day. */
    static LocalDate dayOf(Entry e) {
        return LocalDate.ofInstant(e.lastModified(), ZoneId.systemDefault());
    }

    /**
     * Oldest day still inside a window of {@code activeDays} ACTIVE days, or null when the
     * corpus does not yet span that many active days (in which case nothing ages out).
     *
     * <p>Computed over ALL entries rather than the deletion candidates, so {@code min-keep}
     * cannot distort the day count and make the window appear shorter than it is.
     */
    static LocalDate activeDayCutoff(List<Entry> entries, int activeDays) {
        if (activeDays <= 0 || entries.isEmpty()) {
            return null;
        }
        List<LocalDate> days = entries.stream()
                .map(RetentionPolicy::dayOf)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
        return days.size() < activeDays ? null : days.get(activeDays - 1);
    }

    private boolean delete(Entry entry) {
        try {
            deleteRecursively(entry.path());
            return true;
        } catch (IOException e) {
            log.warn("Retention failed deleting {}: {}", entry.path(), e.getMessage());
            return false;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> children = Files.list(path)) {
                List<Path> kids = children.toList();
                for (Path kid : kids) {
                    deleteRecursively(kid);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    record Entry(Path path, Instant lastModified, long sizeBytes) {
        static Entry of(Path p) {
            try {
                Instant mtime = Files.getLastModifiedTime(p).toInstant();
                long size = sizeOf(p);
                return new Entry(p, mtime, size);
            } catch (IOException e) {
                return null;
            }
        }

        private static long sizeOf(Path p) throws IOException {
            if (!Files.isDirectory(p)) {
                return Files.size(p);
            }
            try (Stream<Path> stream = Files.walk(p)) {
                return stream.filter(Files::isRegularFile).mapToLong(f -> {
                    try {
                        return Files.size(f);
                    } catch (IOException e) {
                        return 0L;
                    }
                }).sum();
            }
        }
    }
}
