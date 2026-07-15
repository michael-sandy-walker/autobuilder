package com.walpro.autobuilder.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.JavacDirectProbe;
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

    /** Ground-truth compile verification of a candidate before trusting its stamp (see preserve()). */
    @Setter
    @Autowired(required = false)
    private JavacDirectProbe buildProbe;

    private static final long VERIFY_TIMEOUT_MS = 120_000L;

    /**
     * Provenance written next to the preserved copy so a human or the harness can see what was
     * selected and on what observation. {@code verifiedBuildResult} is the preserve-time compile
     * probe's own result on the preserved tree ({@code UNVERIFIED} when no probe is wired);
     * {@code candidatesProbed} counts how many candidates were compile-checked before one passed.
     */
    public record Provenance(String fromCycle, boolean entryPointPresent, boolean entryPointRequired,
                             String buildResult, String verifiedBuildResult, int candidatesProbed,
                             int snapshotsConsidered, String haltCycle,
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
            Comparator<Candidate> newestFirst = Comparator
                    .comparing((Candidate c) -> c.state().recordedAt(),
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Candidate::name)
                    .reversed();
            List<Candidate> eligible = candidates.stream()
                    .filter(c -> !entryPointRequired || c.state().entryPointPresent())
                    .filter(c -> !isRecordedFailure(c.state().buildResult()))
                    .sorted(newestFirst)
                    .toList();
            if (eligible.isEmpty()) {
                log.info("T4-CA: no snapshot passes the run's bar (entryPointRequired={}, considered={}) — abstaining.",
                        entryPointRequired, candidates.size());
                return;
            }

            // Stale-stamp guard (field defect, corpus 2026-07-15_03-03-29): the stamped buildResult
            // is the CARRIED build outcome, which goes stale across cycles whose writes never faced
            // a post-merge build (injections). Compile-verify before trusting: probe the newest
            // eligible candidate; if it fails, fall back to the merge-anchored candidates only —
            // a merged stamp's build result came from that cycle's own post-merge gate, so those
            // are the verified states; unmerged trees between them are products of the same
            // speculative churn that broke the newest one.
            List<Candidate> probeOrder = new ArrayList<>();
            probeOrder.add(eligible.get(0));
            eligible.stream()
                    .filter(c -> c.state().merged() && c != eligible.get(0))
                    .forEach(probeOrder::add);
            Candidate best = null;
            String verifiedResult = "UNVERIFIED";
            int probed = 0;
            for (Candidate c : probeOrder) {
                if (buildProbe == null) {
                    best = c;
                    break;
                }
                probed++;
                BuildOutcome verification = buildProbe.execute(
                        c.dir().toString(), "preserve-verify-" + c.name(), VERIFY_TIMEOUT_MS);
                BuildResult r = verification != null ? verification.result() : null;
                if (r == BuildResult.COMPILE_OK || r == BuildResult.SKIPPED) {
                    best = c;
                    verifiedResult = String.valueOf(r);
                    break;
                }
                log.info("T4-CA: snapshot {} stamped buildResult={} but fails preserve-time verification ({}) — walking to an older verified candidate.",
                        c.state().cycleId(), c.state().buildResult(), r);
            }
            if (best == null) {
                log.info("T4-CA: no candidate passed preserve-time build verification ({} probed of {} eligible) — abstaining.",
                        probed, eligible.size());
                return;
            }
            replacePreservedCopy(best.dir());
            Provenance provenance = new Provenance(best.state().cycleId(), best.state().entryPointPresent(),
                    entryPointRequired, best.state().buildResult(), verifiedResult, probed,
                    candidates.size(), context != null ? context.cycleName() : null, Instant.now());
            fileService.writeJson(PROVENANCE_FILE, provenance);
            log.info("T4-CA: preserved snapshot {} to {} (entryPointPresent={}, buildResult={}, verified={}, probed={}, considered={}).",
                    best.state().cycleId(), PRESERVED_DIR, best.state().entryPointPresent(),
                    best.state().buildResult(), verifiedResult, probed, candidates.size());
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
