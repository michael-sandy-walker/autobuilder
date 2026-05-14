package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.MergeRollbackPoint;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T1-EC: tentative-merge probe. Writes the proposed mutation files to {@code output/} and runs
 * the configured build command. On COMPILE_OK the files stay on disk so the LLM evaluator (and
 * later, MergeAgent's commit phase) sees real on-disk state without a redundant write+compile
 * round-trip. On COMPILE_FAIL the files are rolled back to their pre-probe content so the
 * working tree is clean for the next attempt.
 *
 * <p>Cheap-before-expensive: a local {@code mvn compile} is far cheaper than an LLM evaluation
 * call. Running the compile first lets us reject mutations that don't compile without ever
 * paying for an LLM call — and avoids an entire wasted merge-and-rollback cycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompileProbeService {

    private final FileService fileService;
    private final MergeRollbackService mergeRollbackService;
    private final BuildExecutor buildExecutor;

    @Value("${file.base-path:}")
    private String fileBasePath;

    /** Outcome of one probe call. */
    public record ProbeResult(BuildOutcome outcome, MergeRollbackPoint rollbackPoint) {
        public boolean filesOnDisk() {
            return rollbackPoint != null;
        }
    }

    /**
     * Probes whether the proposed mutation in {@code context} compiles.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@link BuildResult#SKIPPED} (no rollback point) when the build executor is disabled
     *       or there are no files to probe — caller should proceed as if no probe ran.</li>
     *   <li>{@link BuildResult#COMPILE_OK} (rollback point set) — files are on disk and ready
     *       for evaluation/merge. Caller is responsible for either keeping them (on accept) or
     *       passing the rollback point back to {@link #rollback} (on reject).</li>
     *   <li>{@link BuildResult#COMPILE_ERROR} (no rollback point — already restored) — files
     *       were rolled back to their pre-probe content. Caller should treat as REJECTED.</li>
     * </ul>
     */
    public ProbeResult probe(CycleContextData context) {
        if (!buildExecutor.isEnabled()) {
            return new ProbeResult(BuildOutcome.skipped("build executor disabled"), null);
        }

        List<FileMutation> mutations = context.evolutionOutcome() != null
                ? context.evolutionOutcome().addedOrChangedFiles()
                : null;
        if (mutations == null || mutations.isEmpty()) {
            return new ProbeResult(BuildOutcome.skipped("no changed files in evolution outcome"), null);
        }

        String outputPath = context.outputPath();
        String cycleId = context.cycleName() + "-probe";

        Map<String, String> originalContents = new LinkedHashMap<>();
        Set<String> newlyAddedPaths = new LinkedHashSet<>();

        try {
            for (FileMutation mutation : mutations) {
                if (mutation == null || mutation.path() == null || mutation.content() == null) continue;
                String relPath = outputPath + "/" + mutation.path();
                boolean existed = fileService.exists(relPath);
                String originalContent = existed ? fileService.readString(relPath) : "";
                originalContents.put(mutation.path(), originalContent);
                if (!existed) {
                    newlyAddedPaths.add(mutation.path());
                }
                Path outputFile = fileService.resolveSecurePath(relPath);
                fileService.writeFileResolved(outputFile, mutation.content());
            }
        } catch (IOException e) {
            log.warn("T1-EC: probe write failed: {}", e.getMessage());
            // Best-effort restore of any files we managed to write before the IOException.
            MergeRollbackPoint partial = mergeRollbackService.snapshot(cycleId, originalContents, newlyAddedPaths);
            try { mergeRollbackService.rollback(partial, outputPath, fileService); } catch (Exception ignored) {}
            return new ProbeResult(BuildOutcome.skipped("probe write failed: " + e.getMessage()), null);
        }

        MergeRollbackPoint rollbackPoint = mergeRollbackService.snapshot(cycleId, originalContents, newlyAddedPaths);
        BuildOutcome outcome = buildExecutor.execute(fileBasePath, cycleId);
        log.info("T1-EC: probe result={} ({} files written)", outcome.result(), originalContents.size());

        if (outcome.result() == BuildResult.COMPILE_ERROR) {
            // Compile failed — restore so the working tree is clean for the next attempt.
            mergeRollbackService.rollback(rollbackPoint, outputPath, fileService);
            return new ProbeResult(outcome, null);
        }

        // COMPILE_OK (or TIMEOUT/SKIPPED): leave files on disk; caller decides what to do next.
        return new ProbeResult(outcome, rollbackPoint);
    }

    /** Restores files to the snapshot captured by {@link #probe}. Used on LLM rejection after a successful compile. */
    public void rollback(MergeRollbackPoint rollbackPoint, String outputPath) {
        if (rollbackPoint == null) return;
        mergeRollbackService.rollback(rollbackPoint, outputPath, fileService);
    }
}
