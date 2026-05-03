package com.walpro.autobuilder.core.build;

/**
 * T4-AN-1: outcome of a post-merge build invocation.
 *
 * <ul>
 *   <li>{@link #COMPILE_OK} — build command exited 0.</li>
 *   <li>{@link #COMPILE_ERROR} — build command exited non-zero.</li>
 *   <li>{@link #TIMEOUT} — build command exceeded the configured timeout.</li>
 *   <li>{@link #SKIPPED} — build executor disabled, working directory missing, or no merged
 *       changes to validate. Heuristic and prompt block must treat this as no-evidence.</li>
 * </ul>
 */
public enum BuildResult {
    COMPILE_OK,
    COMPILE_ERROR,
    TIMEOUT,
    SKIPPED
}
