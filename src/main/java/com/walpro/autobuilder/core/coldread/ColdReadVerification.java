package com.walpro.autobuilder.core.coldread;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs an imported project's OWN tests, and reports what they said.
 *
 * <p><b>Why this is the anchor worth having.</b> A cold read describes what a project declares, and
 * declarations are cheap. Someone else's passing test suite is the one thing in an imported tree
 * that states intended behaviour AND can be checked — the loop did not write it, cannot argue with
 * it, and a green suite is a regression baseline the moment work starts. The alternative anchor,
 * the loop's own opinion of what the project ought to do, is the false-completion engine the import
 * ticket warns about.
 *
 * <p><b>It never touches the project.</b> Building writes a target directory, downloads
 * dependencies and can rewrite lock files, and this is a READ of somebody's code. So the tree is
 * copied to a scratch directory, the build runs there, and the copy is deleted. Slower, and the
 * only version that is honest about what a read is.
 *
 * <p>Opt-in for the same reason: a build takes minutes and reaches the network, which is not
 * something to do because someone asked what a directory contains.
 */
@Slf4j
@Service
public class ColdReadVerification {

    /**
     * @param attempted whether a verification was asked for and could be started
     * @param buildTool what was used, or null when nothing recognisable was found
     * @param builds    whether the project compiles and packages at all
     * @param testsRun  how many of its own tests ran
     * @param testsFailed how many of those did not pass
     * @param durationMs how long the whole thing took, because this is the expensive part of a read
     * @param notes     what happened, in the words the reader needs
     */
    public record Result(boolean attempted, String buildTool, boolean builds, int testsRun,
                         int testsFailed, long durationMs, List<String> notes) {

        /** A green suite is what makes an imported project safe to start changing. */
        public boolean greenBaseline() {
            return builds && testsRun > 0 && testsFailed == 0;
        }

        public static Result notAttempted(String why) {
            return new Result(false, null, false, 0, 0, 0, List.of(why));
        }
    }

    private static final Pattern SUREFIRE_COUNTS = Pattern.compile(
            "tests=\"(\\d+)\"[^>]*?errors=\"(\\d+)\"[^>]*?skipped=\"(\\d+)\"[^>]*?failures=\"(\\d+)\"");
    private static final long BUILD_TIMEOUT_MINUTES = 10;

    /** Copies {@code root}, runs its own tests there, and deletes the copy. */
    public Result verify(Path root) {
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            return Result.notAttempted("no pom.xml, and this reading only knows how to run a Maven build");
        }
        long start = System.currentTimeMillis();
        Path scratch = null;
        List<String> notes = new ArrayList<>();
        try {
            scratch = Files.createTempDirectory("coldread-");
            Path copy = scratch.resolve("project");
            copyWithoutBuildOutput(root, copy);
            notes.add("built in a copy at " + copy + ", so the project itself was not written to");

            Process process = new ProcessBuilder("mvn", "-B", "-q", "test")
                    .directory(copy.toFile())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder tail = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (var reader = process.inputReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (tail.length() < 4000) tail.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // the exit status and the surefire reports are the record; this is only context
                }
            });
            drain.start();
            boolean finished = process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                notes.add("the build did not finish within " + BUILD_TIMEOUT_MINUTES + " minutes and was stopped");
                return new Result(true, "maven", false, 0, 0, System.currentTimeMillis() - start, notes);
            }
            drain.join(2000);
            int exit = process.exitValue();

            int[] counts = readSurefire(copy);
            boolean builds = exit == 0 || counts[0] > 0;   // failing tests still prove it compiled
            if (exit != 0 && counts[0] == 0) {
                notes.add("the build failed before any test ran: " + lastMeaningfulLine(tail.toString()));
            }
            if (counts[0] == 0 && exit == 0) {
                notes.add("it builds, but no tests were found — nothing here states the intended "
                        + "behaviour except the code");
            }
            return new Result(true, "maven", builds, counts[0], counts[1],
                    System.currentTimeMillis() - start, notes);
        } catch (IOException e) {
            notes.add("could not run the build: " + e.getMessage());
            return new Result(true, "maven", false, 0, 0, System.currentTimeMillis() - start, notes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("interrupted while waiting for the build");
            return new Result(true, "maven", false, 0, 0, System.currentTimeMillis() - start, notes);
        } finally {
            deleteQuietly(scratch);
        }
    }

    /** Failures and errors both mean a test did not pass; the distinction is not one a reader needs here. */
    private int[] readSurefire(Path copy) {
        int run = 0, bad = 0;
        Path reports = copy.resolve("target/surefire-reports");
        if (!Files.isDirectory(reports)) return new int[]{run, bad};
        try (Stream<Path> files = Files.list(reports)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".xml")).toList()) {
                Matcher m = SUREFIRE_COUNTS.matcher(Files.readString(f));
                if (m.find()) {
                    run += Integer.parseInt(m.group(1));
                    bad += Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(4));
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("cold read: could not read the test reports: {}", e.getMessage());
        }
        return new int[]{run, bad};
    }

    private static String lastMeaningfulLine(String output) {
        String[] lines = output.strip().split("\\R");
        for (int i = lines.length - 1; i >= 0 && i > lines.length - 25; i--) {
            String l = lines[i].strip();
            if (!l.isEmpty() && !l.startsWith("at ")) return l.length() > 200 ? l.substring(0, 199) + "…" : l;
        }
        return "(no output)";
    }

    /** Copies sources only: build output is what the copy exists to produce. */
    private void copyWithoutBuildOutput(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (name.equals("target") || name.equals("build") || name.equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a leftover scratch directory is untidy, never harmful
                }
            });
        } catch (IOException e) {
            log.debug("cold read: could not clear the scratch copy at {}: {}", dir, e.getMessage());
        }
    }
}
