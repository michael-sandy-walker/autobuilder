package com.walpro.autobuilder.core.stuck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-DZ: a read-only copy of the tree in which the terminal agent runs its commands -- the boundary,
 * built in rather than promised.
 *
 * <p>The copy holds the live tree, the failed change under {@code candidate/} and the compiler's own
 * block in {@code COMPILER_OUTPUT.txt}; every file and directory is made read-only. Commands run with
 * the copy as working directory, WITHOUT a shell, from an allow-list, within a time budget, and no
 * argument may name a path outside the copy. The live tree is never touched: the copy is written from
 * contents in memory.
 *
 * <p><b>Compile, never execute</b> (the user, 2026-09-26: compiling is acceptable; executing code that
 * could do what is otherwise not permitted is not). {@code javac} may run -- into a scratch directory
 * outside the copy, with annotation processing off -- but nothing that runs project or compiled code is
 * allowed: no {@code java}, build tool or test runner, and no option that loads code into the compiler
 * ({@code -J}, {@code -processor*}, {@code -Xplugin}, {@code @argfiles}).
 */
public final class ReadOnlyCopy implements AutoCloseable {

    public static final Set<String> ALLOWED = Set.of("grep", "sed", "cat", "head", "tail", "find", "ls", "wc", "javap", "jar", "javac");
    public static final int TIME_BUDGET_SECONDS = 10;
    public static final int OUTPUT_LINES = 80;

    private static final Pattern SED_PRINT = Pattern.compile("[0-9]+(,[0-9$]+)?p");
    private static final Set<String> FIND_WRITES = Set.of("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint",
            "-fprint0", "-fprintf", "-fls");

    /** One command as run: its output (capped), or why it was refused. */
    public record Result(List<String> argv, String refused, String output, boolean timedOut) {
    }

    private final Path work;
    private final Path root;
    private final Path scratch;

    private ReadOnlyCopy(Path work) {
        this.work = work;
        this.root = work.resolve("copy");
        this.scratch = work.resolve("scratch");
    }

    /** A fresh read-only copy of {@code tree} (+ the change under candidate/, + the compiler's block). */
    public static ReadOnlyCopy of(Map<String, String> tree, Map<String, String> candidate, String compilerOutput) throws IOException {
        ReadOnlyCopy c = new ReadOnlyCopy(Files.createTempDirectory("t4dz-"));
        Files.createDirectories(c.root);
        Files.createDirectories(c.scratch);
        write(c.root, tree);
        if (candidate != null && !candidate.isEmpty()) write(c.root.resolve("candidate"), candidate);
        Files.writeString(c.root.resolve("COMPILER_OUTPUT.txt"), compilerOutput == null ? "" : compilerOutput);
        try (Stream<Path> w = Files.walk(c.root)) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList()) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "r-x------" : "r--------"));
            }
        }
        return c;
    }

    private static void write(Path base, Map<String, String> files) throws IOException {
        if (files == null) return;
        for (Map.Entry<String, String> f : files.entrySet()) {
            if (f.getKey() == null || f.getValue() == null) continue;
            Path p = base.resolve(f.getKey()).normalize();
            if (!p.startsWith(base)) continue;
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue());
        }
    }

    public Path root() {
        return root;
    }

    /** Why {@code argv} may not run here; null when it may. */
    public static String refuseReason(List<String> argv) {
        if (argv == null || argv.isEmpty() || argv.stream().anyMatch(a -> a == null)) return "not an argument list";
        String program = argv.get(0);
        if (!ALLOWED.contains(program)) return "not allowed: " + program;
        for (String a : argv.subList(1, argv.size())) {
            if (a.startsWith("/") || List.of(a.split("/")).contains("..") || a.startsWith("~")) return "path leaves the copy: " + a;
            if (a.startsWith("-J") || a.startsWith("@")) return "loads code or arguments into the tool: " + a;
        }
        switch (program) {
            case "sed" -> {
                if (!(argv.size() >= 4 && argv.get(1).equals("-n") && SED_PRINT.matcher(argv.get(2)).matches())) {
                    return "sed may only print lines: sed -n <from>,<to>p <file>";
                }
            }
            case "find" -> {
                if (argv.stream().anyMatch(FIND_WRITES::contains)) return "find may only list";
            }
            case "jar" -> {
                if (argv.size() < 2 || !argv.get(1).matches("-?t\\w*")) return "jar may only list (tf)";
            }
            case "javac" -> {
                for (String a : argv) {
                    if (a.equals("-d") || a.equals("-s") || a.equals("-h") || a.startsWith("-processor") || a.startsWith("--processor")
                            || a.startsWith("-Xplugin") || a.startsWith("--plugin") || a.startsWith("-proc:")) {
                        return "javac option not allowed here: " + a;
                    }
                }
            }
            default -> {
            }
        }
        return null;
    }

    /** Runs {@code argv} in the copy, or refuses it. */
    public Result run(List<String> argv) {
        String why = refuseReason(argv);
        if (why != null) return new Result(argv, why, null, false);
        List<String> cmd = new ArrayList<>(argv);
        if (cmd.get(0).equals("javac")) cmd.addAll(1, List.of("-d", scratch.toString(), "-proc:none"));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true);
        pb.environment().keySet().retainAll(Set.of("PATH", "JAVA_HOME", "LANG"));
        pb.environment().put("HOME", scratch.toString());
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            byte[] out;
            boolean done = p.waitFor(TIME_BUDGET_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new Result(argv, null, "(timed out after " + TIME_BUDGET_SECONDS + " s)", true);
            }
            out = p.getInputStream().readAllBytes();
            List<String> lines = new String(out, StandardCharsets.UTF_8).lines().limit(OUTPUT_LINES).toList();
            return new Result(argv, null, String.join("\n", lines), false);
        } catch (IOException e) {
            return new Result(argv, null, "(could not run: " + e.getMessage() + ")", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(argv, null, "(interrupted)", false);
        }
    }

    /** A digest of every file in the copy, to prove nothing was written. */
    public String contentHash() throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (Stream<Path> w = Files.walk(root)) {
            for (Path p : w.filter(Files::isRegularFile).sorted().toList()) {
                md.update(root.relativize(p).toString().getBytes(StandardCharsets.UTF_8));
                md.update(Files.readAllBytes(p));
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    @Override
    public void close() {
        try (Stream<Path> w = Files.walk(work)) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    if (Files.isDirectory(p)) Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
                    else Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
                } catch (IOException ignored) {
                    // best effort
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        try (Stream<Path> w = Files.walk(work)) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // a leftover temp dir is harmless
        }
    }
}
