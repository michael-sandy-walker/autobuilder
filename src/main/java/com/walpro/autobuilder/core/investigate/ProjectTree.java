package com.walpro.autobuilder.core.investigate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-EE slice D: the project as it is NOW, read-only, for the investigator's tree probes. A log holds copies of
 * files as they were when logged; what a file contains now is read here.
 *
 * <p>Generic over text files: build output and version-control directories are left out, and a file that is
 * large or not text is never read.
 */
final class ProjectTree {

    static final int MAX_FILES_LISTED = 100;
    static final long MAX_FILE_BYTES = 512 * 1024;
    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "target", "node_modules", ".idea", ".gradle");

    private final Path root;
    private List<Path> files;

    ProjectTree(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** The tree's text files, relative to its root, in a stable order; walked once. */
    List<Path> files() {
        if (files != null) return files;
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> root.relativize(p).getNameCount() > 0)
                    .filter(p -> !skipped(root.relativize(p)))
                    .filter(ProjectTree::readable)
                    .map(root::relativize)
                    .sorted()
                    .forEach(out::add);
        } catch (IOException | RuntimeException e) {
            // an unreadable tree answers as an empty one; the probe result says so
        }
        files = out;
        return files;
    }

    /** The lines of {@code relative} as it is now, or null when it is not a readable file inside the tree. */
    List<String> lines(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root) || !Files.isRegularFile(p) || !readable(p)) return null;
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            return null;
        }
    }

    /** Paths whose relative form matches {@code p}. */
    List<String> matchingPaths(Pattern p) {
        return files().stream().map(f -> f.toString().replace('\\', '/')).filter(s -> p.matcher(s).find()).toList();
    }

    private static boolean skipped(Path relative) {
        for (Path part : relative) {
            if (SKIPPED_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    private static boolean readable(Path p) {
        try {
            if (Files.size(p) > MAX_FILE_BYTES) return false;
            byte[] head;
            try (var in = Files.newInputStream(p)) {
                head = in.readNBytes(1024);
            }
            for (byte b : head) if (b == 0) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
