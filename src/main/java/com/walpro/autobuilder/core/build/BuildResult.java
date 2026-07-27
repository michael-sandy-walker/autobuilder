package com.walpro.autobuilder.core.build;

/**
 * T4-AN-1: outcome of a post-merge build invocation.
 *
 * <ul>
 *   <li>{@link #COMPILE_OK} — build command exited 0.</li>
 *   <li>{@link #COMPILE_ERROR} — build command exited non-zero.</li>
 *   <li>{@link #TIMEOUT} — build command exceeded the configured timeout.</li>
 *   <li>{@link #SKIPPED} — build executor disabled, working directory missing, or no merged
 *       changes to validate. Heuristic and prompt block must treat this as no-observation.</li>
 *   <li>{@link #FRAGMENTATION_REJECTED} — T4-BH: probe rejected the write set before any file
 *       landed on disk because it would introduce a same-simple-name-different-package
 *       collision against the current {@code output/} tree. No build was run, no rollback
 *       point exists, and the {@code stderrTail} carries a human-readable description of the
 *       conflict.</li>
 * </ul>
 */
public enum BuildResult {
    COMPILE_OK,
    COMPILE_ERROR,
    TIMEOUT,
    SKIPPED,
    FRAGMENTATION_REJECTED,

    /**
     * T2-EQ: the sources compiled but the build tool could not produce a runnable artifact.
     * Distinct from COMPILE_ERROR on purpose — the code is valid, something the PACKAGING step
     * needs is absent, and the two demand different repairs. Field evidence 2026-07-27: a tree
     * that compiled cleanly and would have scored 21/23 reported artifact-build-failed because
     * no entry point existed, and nothing on the per-cycle path ever asked the tool to package.
     */
    PACKAGE_ERROR
}
