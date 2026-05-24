package com.walpro.autobuilder.core.mutation;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * T4-BE: re-stages a {@link DeferredMutation} whose blockers have cleared. The flow is:
 *
 * <ol>
 *   <li><b>Drift check</b> — every {@code TOUCHES} file in {@code output/} must still match the
 *       {@code contentHash} captured at deferral time. Drift on any non-blocker file aborts
 *       (state → {@code DROPPED_DRIFT}) so we don't merge over the planner's later work.</li>
 *   <li><b>Load</b> the mutation's files from {@code mutations/<originalCycleId>/}.</li>
 *   <li><b>Re-probe</b> via {@link CompileProbeService#probe(CycleContextData, boolean)} with
 *       {@code allowInjection=false} (the injector would mask the test of whether the deferred
 *       mutation is actually viable now). On {@code COMPILE_OK} files are staged on disk and
 *       a rollback point is captured. On {@code COMPILE_ERROR} state → {@code DROPPED_REPROBE_FAILED}.</li>
 *   <li><b>Stage</b> a modified context: {@code evolutionOutcome} carrying the loaded
 *       {@link FileMutation}s, {@code evolutionRanThisCycle=true}, and a fresh {@code ROLE_EVALUATION}
 *       signal so the signal-driven engine routes through the standard eval → merge path.</li>
 * </ol>
 *
 * <p>The standard pipeline takes over from {@link com.walpro.autobuilder.agent.evaluation.EvaluationAgent}
 * — it sees a fresh evolution fingerprint, calls the LLM evaluator, negotiates with heuristics,
 * and (on ACCEPTED) routes into {@link com.walpro.autobuilder.agent.merge.MergeAgent}. The
 * deferred record is marked {@link DeferredMutationState#RESTAGED} the moment the probe clears
 * — the downstream LLM evaluation may still reject for non-compile reasons; that's a separate
 * concern from "the blockers are gone".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeferredMutationRestager {

    private final DeferredMutationLedger ledger;
    private final CompileProbeService compileProbeService;
    private final FileService fileService;

    @Value("${file.base-path:}")
    private String fileBasePath;

    public RestageResult attemptRestage(DeferredMutation candidate, CycleContextData context) {
        if (candidate == null) return RestageResult.skipped("null candidate");
        if (context == null) return RestageResult.skipped("null context");

        // ---- 1. drift check ----
        String outputPath = context.outputPath();
        for (Map.Entry<String, String> touched : candidate.touchedFiles().entrySet()) {
            String currentHash = readAndHashIfPresent(outputPath + "/" + touched.getKey());
            if (currentHash != null && !currentHash.equals(touched.getValue())) {
                log.info("T4-BE restage: drift detected on {} for deferred {} — dropping",
                        touched.getKey(), candidate.id());
                ledger.markDroppedDrift(candidate.id());
                return RestageResult.dropped("drift", touched.getKey());
            }
        }

        // ---- 2. load mutation files from disk ----
        List<FileMutation> files;
        try {
            files = loadMutationFiles(candidate);
        } catch (IOException e) {
            log.warn("T4-BE restage: load failed for {}: {}", candidate.id(), e.getMessage());
            ledger.markDroppedDrift(candidate.id());
            return RestageResult.dropped("files_unreadable", e.getMessage());
        }
        if (files.isEmpty()) {
            log.warn("T4-BE restage: no files loaded from {} — dropping {}", candidate.mutationDir(), candidate.id());
            ledger.markDroppedDrift(candidate.id());
            return RestageResult.dropped("files_missing", candidate.mutationDir());
        }

        // ---- 3. stage context for re-probe ----
        EvolutionOutcome restagedEvo = new EvolutionOutcome(
                files,
                List.of(),
                "deferred-restage of " + candidate.id(),
                null,
                false,
                false);

        CycleContextData restageCtx = context
                .withEvolutionOutcome(restagedEvo)
                .withEvolutionRanThisCycle(true);

        // ---- 4. re-probe (no injector — we want a clean test) ----
        CompileProbeService.ProbeResult probe = compileProbeService.probe(restageCtx, false);
        if (probe.outcome().result() == BuildResult.FRAGMENTATION_REJECTED) {
            // T4-BH: a later merge landed a flat version of one of the deferred types — restage
            // would create a same-simple-name-different-package collision. Different remediation
            // story from a compile failure (the deferred mutation is structurally subsumed, not
            // missing context), so this gets its own terminal state.
            log.info("T4-BE restage: package fragmentation detected for deferred {} ({}) — dropping",
                    candidate.id(), probe.outcome().stderrTail());
            ledger.markDroppedPackageFragmentation(candidate.id());
            return RestageResult.dropped("package_fragmentation", probe.outcome().stderrTail());
        }
        if (probe.outcome().result() == BuildResult.COMPILE_ERROR) {
            log.info("T4-BE restage: re-probe failed for deferred {} (stderr lines={}) — dropping",
                    candidate.id(), probe.outcome().stderrTail() != null
                            ? probe.outcome().stderrTail().split("\\R").length : 0);
            ledger.markDroppedReProbeFailed(candidate.id());
            return RestageResult.dropped("reprobe_failed", null);
        }

        // ---- 5. probe staged the files on disk — finalise restage context ----
        if (probe.filesOnDisk()) {
            restageCtx = restageCtx.withLastRollbackPoint(probe.rollbackPoint());
        }
        if (probe.outcome() != null && probe.outcome().result() != BuildResult.SKIPPED) {
            restageCtx = restageCtx.withLastProbeOutcome(probe.outcome());
        }
        // Boost ROLE_EVALUATION so the signal-driven engine routes through eval → merge.
        restageCtx = restageCtx.withSignal(Signal.of(BasicSignals.ROLE_EVALUATION, 3.0, 0.05, "deferred-restage"));

        ledger.markRestaged(candidate.id());
        log.info("T4-BE restage: deferred {} re-probed COMPILE_OK — eval/merge path will run", candidate.id());
        return RestageResult.staged(restageCtx);
    }

    private List<FileMutation> loadMutationFiles(DeferredMutation candidate) throws IOException {
        List<FileMutation> out = new ArrayList<>();
        String dir = fileBasePath + "/" + candidate.mutationDir();
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            log.warn("T4-BE restage: mutation dir missing: {}", dir);
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String fileName = p.getFileName().toString();
                if (fileName.equals("meta.json")) continue;
                String rel = root.relativize(p).toString().replace('\\', '/');
                String content = Files.readString(p, StandardCharsets.UTF_8);
                out.add(new FileMutation(rel, content));
            }
        }
        return out;
    }

    private String readAndHashIfPresent(String relativePath) {
        try {
            if (!fileService.exists(relativePath)) return null;
            return sha256(fileService.readString(relativePath));
        } catch (Exception e) {
            log.warn("T4-BE drift hash failed for {}: {}", relativePath, e.getMessage());
            return null;
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Outcome of an attempted re-stage. */
    public sealed interface RestageResult {

        static RestageResult staged(CycleContextData stagedContext) { return new Staged(stagedContext); }
        static RestageResult dropped(String reason, String detail) { return new Dropped(reason, detail); }
        static RestageResult skipped(String reason) { return new Skipped(reason); }

        /** Probe cleared — context is loaded with the mutation's files; caller assigns and continues. */
        record Staged(CycleContextData context) implements RestageResult {}
        /** Candidate dropped (drift, reprobe failure, files missing). Caller falls through. */
        record Dropped(String reason, String detail) implements RestageResult {}
        /** No-op (invalid inputs). Caller falls through. */
        record Skipped(String reason) implements RestageResult {}
    }
}
