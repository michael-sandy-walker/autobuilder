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
        return archive(cycleId, snapshotPath, null);
    }

    /**
     * Archive the tree and, when the checkpoint captured one, the carried cycle context beside it.
     *
     * <p>The context is what the scheduler reads on its next tick — the streaks, warnings, relayed
     * rejection reasons and build observations that {@code ContextLineageTracker} carries from one
     * cycle into the next. It lived only in {@code mutations/<cycleId>/meta.json}, which is
     * written mid-cycle (after evolution, before evaluation) and cleared at the run boundary, so
     * no point from an earlier run could ever bring it back. Kept here under a reserved name, like
     * the cache, and stripped again before the tree is planted into {@code output/}.
     *
     * @param contextSourcePath the checkpoint's own copy of the carried context, or null
     */
    public String archive(String cycleId, String snapshotPath, String contextSourcePath) {
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
            copyContextInto(destination, contextSourcePath);
            prune();
            return target;
        } catch (IOException | RuntimeException e) {
            log.debug("T4-DQ: could not archive the tree for {}: {}", cycleId, e.getMessage());
            return null;
        }
    }

    /** Name of the cache copy inside an archived cycle. */
    /**
     * Everything the archive keeps beside the tree starts with this. The restore strips every
     * top-level entry carrying it before the tree is planted into {@code output/}, so a file
     * added here later cannot become project source by omission from a list of names.
     */
    public static final String RESERVED_PREFIX = "__";

    public static final String CACHE_SUBDIR = "__cache";

    /** The carried cycle context, archived beside the tree; never part of the project. */
    public static final String CONTEXT_FILE = "__context.json";

    /**
     * Present only when a person SUPPLIED the context file rather than the checkpoint capturing
     * it — a point taken before contexts were archived, rebuilt from the run's logs. Its text is
     * relayed verbatim into the rewind's result so the restore never reads as a capture.
     */
    public static final String CONTEXT_PROVENANCE_FILE = "__context.provenance";

    private void copyContextInto(Path destination, String contextSourcePath) {
        if (contextSourcePath == null || contextSourcePath.isBlank()) return;
        try {
            if (!fileService.exists(contextSourcePath)) return;
            Files.copy(fileService.resolveSecurePath(contextSourcePath),
                    destination.resolve(CONTEXT_FILE),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            log.debug("T4-DQ: could not archive the carried context: {}", e.getMessage());
        }
    }

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
