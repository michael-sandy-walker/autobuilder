package com.walpro.autobuilder.home;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * T4-ED slice 3: brings a codebase on this machine in as a project -- its tree copied into {@code output/}, and
 * optionally what may not be touched into {@code bootstrap/protected-paths.txt}. The project is then started in
 * ADOPT_PROJECT, which works on the code as it is and waits for a goal.
 *
 * <p>A copy, never the original in place: the loop writes to {@code output/}, and someone's working tree is not
 * the place for that. Build output, version control and IDE state are left behind -- they are rebuilt or are
 * not the code. Links are not followed, so nothing outside the chosen directory comes along.
 */
final class ProjectImport {

    /** Directories that are not the code: version control, build output, dependencies, IDE state. */
    static final Set<String> LEFT_OUT = Set.of(".git", "target", "build", "node_modules", ".idea", ".gradle");

    /** A tree past these is refused rather than copied: the loop reads every file, many times. */
    static final int MAX_FILES = 20_000;
    static final long MAX_BYTES = 500L * 1024 * 1024;

    /** What was copied, and what was left out. */
    record Copied(int files, long bytes, int leftOut) {}

    private ProjectImport() {}

    /**
     * Copies {@code source} into {@code project/output} and writes {@code protectedPaths}, when given, into
     * {@code project/bootstrap/protected-paths.txt}. The project directory must not exist yet.
     *
     * @throws IllegalArgumentException when the source is not a directory, holds no files, or is too large
     */
    static Copied into(Path source, Path project, String protectedPaths) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(source + " is not a directory");
        }
        Copied size = measure(source);
        if (size.files() == 0) throw new IllegalArgumentException(source + " holds no files to import");
        if (size.files() > MAX_FILES || size.bytes() > MAX_BYTES) {
            throw new IllegalArgumentException(source + " holds " + size.files() + " files (" + size.bytes() / (1024 * 1024)
                    + " MB) without build output; the limit is " + MAX_FILES + " files and " + MAX_BYTES / (1024 * 1024) + " MB");
        }
        Path output = project.resolve("output");
        Files.createDirectories(project.resolve("bootstrap"));
        walk(source, (file, relative) -> {
            Path target = output.resolve(relative.toString());
            Files.createDirectories(target.getParent());
            Files.copy(file, target, LinkOption.NOFOLLOW_LINKS);
        });
        if (protectedPaths != null && !protectedPaths.isBlank()) {
            Files.writeString(project.resolve("bootstrap/protected-paths.txt"),
                    protectedPaths.endsWith("\n") ? protectedPaths : protectedPaths + "\n", StandardCharsets.UTF_8);
        }
        return size;
    }

    private static Copied measure(Path source) throws IOException {
        int[] files = {0};
        long[] bytes = {0};
        int left = walk(source, (file, relative) -> {
            files[0]++;
            bytes[0] += Files.size(file);
        });
        return new Copied(files[0], bytes[0], left);
    }

    private interface FileAction {
        void accept(Path file, Path relative) throws IOException;
    }

    /** Visits the regular files under {@code source}, skipping {@link #LEFT_OUT} and links; returns how many were skipped. */
    private static int walk(Path source, FileAction action) throws IOException {
        int[] leftOut = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(source) && LEFT_OUT.contains(dir.getFileName().toString())) {
                    leftOut[0]++;
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) action.accept(file, source.relativize(file));
                else leftOut[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return leftOut[0];
    }
}
