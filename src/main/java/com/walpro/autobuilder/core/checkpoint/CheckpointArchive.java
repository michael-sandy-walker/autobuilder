package com.walpro.autobuilder.core.checkpoint;

import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * T4-DQ: the tree copies that let a rewind reach past the run that took them.
 *
 * <p><b>Source only, which is what makes keeping them affordable.</b> A per-cycle snapshot measured
 * 120 KB, of which 56 KB was {@code target/} — build output, which regenerates and which nobody
 * needs preserved. Dropping it leaves ~16 KB of actual source per cycle, so a checkpoint plus its
 * restorable tree costs roughly 36 KB. Across the 139 runs of history in the graph that is a couple
 * of hundred megabytes; one long run's {@code snapshots/} alone has reached 952 MB.
 *
 * <p><b>Not cleared by reset or resume</b>, unlike {@code snapshots/} and {@code checkpoints/}.
 * Those are run-scoped on purpose because they hold the unpruned build output; this holds the part
 * worth keeping, so it is bounded by count instead of by the run boundary.
 *
 * <p>What "source" means here is deliberately the same question the diff already answers, rather
 * than a second opinion invented for this: the excluded folders come from the diff configuration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckpointArchive {

    /** Relative to {@code file.base-path}. Survives reset and resume. */
    public static final String ARCHIVE_DIR = "checkpoint-archive";

    private final FileService fileService;
    private final com.walpro.autobuilder.config.DiffConfigProperties diffConfig;

    /** Off keeps the pre-existing behaviour exactly: checkpoints stay inside their own run. */
    @Setter
    @Value("${autobuilder.checkpoint.archive-enabled:true}")
    private boolean enabled = true;

    /**
     * How many archived trees to keep, across all runs. Generous because each is a few kilobytes:
     * 2000 is roughly fifty runs of history at forty cycles each.
     */
    @Setter
    @Value("${autobuilder.checkpoint.archive-retain:2000}")
    private int retain = 2000;

    /**
     * Copy the cycle's tree, minus build output, into the archive. Returns the archive path, or
     * null when nothing was archived — a null is a point that will still be listed and still
     * carries its holder state, just without a tree to put back.
     */
    public String archive(String cycleId, String snapshotPath) {
        if (!enabled || cycleId == null || snapshotPath == null || snapshotPath.isBlank()) return null;
        String target = ARCHIVE_DIR + "/" + cycleId;
        try {
            if (!fileService.exists(snapshotPath)) return null;
            Path source = fileService.resolveSecurePath(snapshotPath);
            Path destination = fileService.resolveSecurePath(target);
            if (Files.exists(destination)) deleteRecursive(destination);
            Files.createDirectories(destination);

            int copied = 0;
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String relative = source.relativize(file).toString().replace('\\', '/');
                    if (relative.startsWith(CACHE_SUBDIR + "/")) continue;
                    if (excluded(relative)) continue;
                    Path out = destination.resolve(relative);
                    Files.createDirectories(out.getParent());
                    Files.copy(file, out);
                    copied++;
                }
            }
            if (copied == 0) {
                deleteRecursive(destination);
                return null;
            }
            // FAITHFUL asks for the resolution cache back, and across a run boundary the
            // checkpoint's own copy is gone with checkpoints/. Keep one here or cross-run
            // FAITHFUL can only ever report that it could not honour the mode.
            copyCacheInto(destination);
            prune();
            return target;
        } catch (IOException | RuntimeException e) {
            log.debug("T4-DQ: could not archive the tree for {}: {}", cycleId, e.getMessage());
            return null;
        }
    }

    /** Where an archived cycle's cache copy lives, when it kept one. */
    public String cachePathOf(String cycleId) {
        return ARCHIVE_DIR + "/" + cycleId + "/" + CACHE_SUBDIR;
    }

    /** Name of the cache copy inside an archived cycle. */
    public static final String CACHE_SUBDIR = "__cache";

    private void copyCacheInto(Path destination) {
        try {
            if (!fileService.exists(CheckpointService.CACHE_DIR)) return;
            Path cache = fileService.resolveSecurePath(CheckpointService.CACHE_DIR);
            Path out = destination.resolve(CACHE_SUBDIR);
            Files.createDirectories(out);
            try (Stream<Path> walk = Files.walk(cache)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    Path target = out.resolve(cache.relativize(file).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("T4-DQ: could not archive the cache: {}", e.getMessage());
        }
    }

    /** Is a tree still archived for this cycle? */
    public boolean has(String archivePath) {
        try {
            return archivePath != null && !archivePath.isBlank() && fileService.exists(archivePath);
        } catch (IOException e) {
            return false;
        }
    }

    /** Archived cycle ids, oldest first. */
    public List<String> archivedCycles() {
        try {
            if (!fileService.exists(ARCHIVE_DIR)) return List.of();
            return fileService.listSubfolders(ARCHIVE_DIR).stream()
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The snapshot's own state stamp describes the snapshot, not the project, and build output is
     * excluded by the same configuration the diff uses so the two cannot drift apart.
     */
    private boolean excluded(String relativePath) {
        if (relativePath.equals(
                com.walpro.autobuilder.core.persistence.MutationSnapshotService.SNAPSHOT_STATE_FILE)) {
            return true;
        }
        List<String> folders = diffConfig.getExcludeFolders();
        if (folders == null) return false;
        for (String folder : folders) {
            if (folder == null || folder.isBlank()) continue;
            if (relativePath.equals(folder) || relativePath.startsWith(folder + "/")) return true;
        }
        return false;
    }

    private void prune() {
        if (retain <= 0) return;
        List<String> cycles = archivedCycles();
        int excess = cycles.size() - retain;
        for (int i = 0; i < excess; i++) {
            try {
                fileService.deleteRecursively(ARCHIVE_DIR + "/" + cycles.get(i));
            } catch (IOException e) {
                log.debug("T4-DQ: could not prune archived tree {}: {}", cycles.get(i), e.getMessage());
            }
        }
    }

    private void deleteRecursive(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to delete: " + p, e);
                }
            });
        }
    }
}
