package com.walpro.autobuilder.core.build;

/**
 * T4-AU: the shaped questions callers ask about a file, answered by {@link FileRoleClassifier}
 * from the build-tool registry ({@link BuildToolProbeProperties}) instead of per-caller filename
 * lists. A file can hold several roles at once (roles are a set): a build descriptor is also a
 * resource in some layouts, a test file is also a source file.
 */
public enum FileRole {
    /** A file that defines a build tool for the project (registry {@code tool-detection} keys). */
    BUILD_DESCRIPTOR,
    /** A compilable source file (registry {@code source-extensions}). */
    SOURCE,
    /** A source file under a registered test directory marker. */
    TEST,
    /** A path under a build tool's generated-output directory. */
    GENERATED_OUTPUT,
    /** A file under a registered resource directory marker. */
    RESOURCE,
    /** No registry rule matched. */
    UNKNOWN
}
