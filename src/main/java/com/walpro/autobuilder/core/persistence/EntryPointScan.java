package com.walpro.autobuilder.core.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-CA: cheap deterministic scan for a Java application entry point in a source tree.
 *
 * <p>All three {@code artifact-build-failed} corpora of the 2026-07-13 replay failed packaging
 * on the same shape — sources compile but no entry-point class exists (the completion-edge
 * package gate would have named it, but churn runs never reach that edge). This scan records the
 * fact per snapshot so a terminal-time consumer can select the newest state that still had one.
 *
 * <p>Language-level, not framework-level: it looks for the Java {@code static void main}
 * declaration grammar, nothing ecosystem-specific. Whether an entry point is REQUIRED is not
 * decided here — the consumer gates on the run's own history (a target that never had one is
 * never expected to have one).
 */
public final class EntryPointScan {

    private static final Pattern MAIN_METHOD =
            Pattern.compile("static\\s+void\\s+main\\s*\\(");

    private EntryPointScan() {
    }

    /**
     * True when any {@code .java} file under the tree declares a {@code static void main(} method.
     * Prefers {@code src/main/java} when present (test sources cannot carry the packaged entry
     * point); scans the whole tree otherwise. Unreadable files and I/O failures count as absent —
     * the scan records evidence, it never blocks.
     */
    public static boolean hasJavaEntryPoint(Path root) {
        if (root == null || !Files.isDirectory(root)) return false;
        Path mainSources = root.resolve("src/main/java");
        Path scanRoot = Files.isDirectory(mainSources) ? mainSources : root;
        try (Stream<Path> files = Files.walk(scanRoot)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .anyMatch(EntryPointScan::declaresMain);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean declaresMain(Path file) {
        try {
            return MAIN_METHOD.matcher(Files.readString(file)).find();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
