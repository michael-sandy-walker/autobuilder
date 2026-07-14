package com.walpro.autobuilder.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * T4-CA: at an in-process halt, copy the newest snapshot that still passes the run's own
 * verification bar to {@code meta/preserved-artifact/}, so the run's terminal state includes the
 * best state it ever had on disk — not just the last-touched tree.
 *
 * <p>Why: all three {@code artifact-build-failed} corpora of the 2026-07-13 replay ended with a
 * tree WORSE than states the run had already snapshotted (the entry point present in the seed was
 * gone at the terminal — one run deleted it by merged mutation, one restructured every package and
 * never re-created it). The per-cycle snapshots already exist; nothing selected among them.
 *
 * <p>The bar is run-relative ({@code feedback_relative_thresholds}): an entry point is required
 * only if any snapshot of this run ever had one — a target that never had an entry point (library
 * shape) is never expected to grow one. {@code buildResult} disqualifies only on a recorded
 * failure; {@code null} (no build ran that cycle) is unknown, not failing.
 *
 * <p>Never throws; abstains without snapshots. Does NOT restore over {@code output/} — a resumed
 * run must not silently continue against an older tree than its goal state expects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreservedArtifactService {

    static final String SNAPSHOTS_DIR = "snapshots";
    static final String PRESERVED_DIR = "meta/preserved-artifact";
    static final String PROVENANCE_FILE = "meta/preserved-artifact-provenance.json";

    private final FileService fileService;
    private final ObjectMapper objectMapper;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /**
     * Provenance written next to the preserved copy so a human or the harness can see what was
     * selected and on what observation.
     */
    public record Provenance(String fromCycle, boolean entryPointPresent, boolean entryPointRequired,
                             String buildResult, int snapshotsConsidered, String haltCycle,
                             Instant preservedAt) {}

    private record Candidate(Path dir, String name, MutationSnapshotService.SnapshotState state) {}

    /** Selects and copies the best snapshot. Safe to call on every pause transition. */
    public void preserve(CycleContextData context) {
        try {
            List<Candidate> candidates = readCandidates();
            if (candidates.isEmpty()) {
                log.info("T4-CA: no stamped snapshots to preserve from — abstaining.");
                return;
            }
            boolean entryPointRequired = candidates.stream()
                    .anyMatch(c -> c.state().entryPointPresent());
            Candidate best = candidates.stream()
                    .filter(c -> !entryPointRequired || c.state().entryPointPresent())
                    .filter(c -> !isRecordedFailure(c.state().buildResult()))
                    .max(Comparator.comparing((Candidate c) -> c.state().recordedAt(),
                                    Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(Candidate::name))
                    .orElse(null);
            if (best == null) {
                log.info("T4-CA: no snapshot passes the run's bar (entryPointRequired={}, considered={}) — abstaining.",
                        entryPointRequired, candidates.size());
                return;
            }
            replacePreservedCopy(best.dir());
            Provenance provenance = new Provenance(best.state().cycleId(), best.state().entryPointPresent(),
                    entryPointRequired, best.state().buildResult(), candidates.size(),
                    context != null ? context.cycleName() : null, Instant.now());
            fileService.writeJson(PROVENANCE_FILE, provenance);
            log.info("T4-CA: preserved snapshot {} to {} (entryPointPresent={}, buildResult={}, considered={}).",
                    best.state().cycleId(), PRESERVED_DIR, best.state().entryPointPresent(),
                    best.state().buildResult(), candidates.size());
            if (behaviourLogger != null) {
                behaviourLogger.artifactPreserved(context != null ? context.cycleName() : null,
                        best.state().cycleId(), best.state().entryPointPresent(), entryPointRequired,
                        best.state().buildResult(), candidates.size());
            }
        } catch (Exception e) {
            log.warn("T4-CA: artifact preservation failed (run state untouched): {}", e.getMessage());
        }
    }

    private List<Candidate> readCandidates() throws IOException {
        if (!fileService.exists(SNAPSHOTS_DIR)) return List.of();
        List<Candidate> out = new ArrayList<>();
        for (Path dir : fileService.listSubfolders(SNAPSHOTS_DIR)) {
            Path stateFile = dir.resolve(MutationSnapshotService.SNAPSHOT_STATE_FILE);
            if (!Files.isRegularFile(stateFile)) continue; // pre-T4-CA snapshot — no recorded facts
            try {
                MutationSnapshotService.SnapshotState state = objectMapper.readValue(
                        Files.readString(stateFile), MutationSnapshotService.SnapshotState.class);
                out.add(new Candidate(dir, dir.getFileName().toString(), state));
            } catch (IOException | RuntimeException e) {
                log.debug("T4-CA: unreadable snapshot state at {}: {}", stateFile, e.getMessage());
            }
        }
        return out;
    }

    private static boolean isRecordedFailure(String buildResult) {
        return buildResult != null && !"COMPILE_OK".equals(buildResult) && !"SKIPPED".equals(buildResult);
    }

    /** Clean replace — a stale file from an older preservation could break the preserved tree. */
    private void replacePreservedCopy(Path snapshotDir) throws IOException {
        Path preserved = fileService.resolveSecurePath(PRESERVED_DIR);
        if (Files.exists(preserved)) {
            try (var walk = Files.walk(preserved)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        fileService.copyFolder(SNAPSHOTS_DIR + "/" + snapshotDir.getFileName(), PRESERVED_DIR);
    }
}
