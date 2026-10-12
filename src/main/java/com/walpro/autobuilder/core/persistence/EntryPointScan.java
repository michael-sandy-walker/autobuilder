package com.walpro.autobuilder.core.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
 * decided here — this class only observes.
 *
 * <p><b>T4-CV note on that requirement.</b> The original consumer gated on the run's own history
 * ("a target that never had one is never expected to have one"), which is false exactly in the
 * runs that need it most: six recorded runs died at {@code Unable to find main class} having never
 * written one, so the retrospective test read {@code false} throughout. The forward-looking
 * derivation now lives with the spec that motivates it —
 * {@code EndpointSurfaceQuery.specRequiresRunnableArtifact} — and a spec naming endpoints demands
 * a runnable artifact regardless of what the run has managed to produce so far.
 */
public final class EntryPointScan {

    private static final Pattern MAIN_METHOD =
            Pattern.compile("static\\s+void\\s+main\\s*\\(");

    /**
     * T4-CV: the work item raised when the spec demands a runnable artifact and the tree has no
     * entry point. Owned here so the template has one home, exactly as the endpoint query owns its
     * own. Phrased as the missing capability rather than as a defect statement — a description
     * that embeds "no main exists" becomes unconfirmable the moment the work is done, which is how
     * run 11 walled itself.
     */
    public static final String MISSING_ENTRY_POINT_WORK_ITEM =
            "Provide a runnable application entry point — the goal specification names HTTP"
                    + " endpoints, but no class in the application sources declares a"
                    + " 'static void main' method, so the packaged artifact cannot start.";

    /** The done-when for {@link #MISSING_ENTRY_POINT_WORK_ITEM}: mechanically checkable, and false until built. */
    public static final String ENTRY_POINT_CRITERION =
            "A class in the application sources declares a 'static void main' method";

    private EntryPointScan() {
    }

    /** Whether {@code path} lies under a test source root: such a file is never packaged, whatever it declares. */
    public static boolean isTestSource(String path) {
        if (path == null) return false;
        String normalized = path.replace('\\', '/');
        return normalized.contains("/src/test/") || normalized.startsWith("src/test/");
    }

    /**
     * The type in {@code source} that DECLARES an entry point -- a static {@code void main} taking the argument
     * array -- read from the parsed source, so the words in a comment or a string literal declare nothing.
     *
     * <p>Edit-hunks, every run since 2026-09-21 (field65, 2026-10-12: four of four): a test of the response
     * parser carries a Java source as a string literal, {@code "public static void main(String[] args)"} among
     * it. The text match took that test for the project's one entry point, the pom was told to launch a test
     * class, packaging then named a class the jar cannot hold, and the goal could only close when the model
     * declared some other main class the ticket never asked for -- or not at all, at full score.
     *
     * @param mustBePublic     whether only a public method counts (what a launcher accepts)
     * @param whenUnparsable   asked of the text when the source does not parse; a match answers with an empty
     *                         name -- an entry point is declared, by a type this could not read
     * @return the declaring type's name, empty when the source declares no entry point
     */
    public static Optional<String> entryPointTypeOf(String source, boolean mustBePublic, Pattern whenUnparsable) {
        if (source == null) return Optional.empty();
        com.github.javaparser.ast.CompilationUnit unit;
        try {
            unit = new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                    .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17))
                    .parse(source).getResult().orElse(null);
        } catch (RuntimeException e) {
            unit = null;
        }
        if (unit == null || unit.getTypes().isEmpty()) {
            return whenUnparsable != null && whenUnparsable.matcher(source).find() ? Optional.of("") : Optional.empty();
        }
        for (com.github.javaparser.ast.body.MethodDeclaration m
                : unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)) {
            if (!m.getNameAsString().equals("main") || !m.isStatic() || !m.getType().isVoidType()) continue;
            if (mustBePublic && !m.isPublic()) continue;
            if (m.getParameters().size() != 1) continue;
            com.github.javaparser.ast.body.Parameter arg = m.getParameter(0);
            String type = arg.getTypeAsString().replace(" ", "");
            if (!(type.equals("String[]") || (type.equals("String") && arg.isVarArgs()))) continue;
            return Optional.of(m.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                    .map(t -> t.getNameAsString()).orElse(""));
        }
        return Optional.empty();
    }

    /**
     * True when any {@code .java} file under the tree declares a {@code static void main(} method.
     * Prefers {@code src/main/java} when present (test sources cannot carry the packaged entry
     * point); scans the whole tree otherwise. Unreadable files and I/O failures count as absent —
     * the scan records an observation, it never blocks.
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

    /**
     * T4-CV: the same scan over an in-memory file map, for consumers that hold the output as
     * {@code path → source} rather than a directory (the closure-edge coverage pass).
     *
     * <p>Test sources are excluded explicitly here: a directory scan can prefer {@code
     * src/main/java} by walking it, but a flat map cannot, and a {@code main} in a test source
     * would read as a packaged entry point that does not exist. Same grammar, same "absent on
     * doubt" posture as the directory form.
     */
    public static boolean hasJavaEntryPointInSources(java.util.Map<String, String> files) {
        if (files == null || files.isEmpty()) return false;
        for (java.util.Map.Entry<String, String> e : files.entrySet()) {
            String path = e.getKey();
            if (path == null || !path.endsWith(".java")) continue;
            if (isTestSource(path)) continue;
            if (entryPointTypeOf(e.getValue(), false, MAIN_METHOD).isPresent()) return true;
        }
        return false;
    }

    private static boolean declaresMain(Path file) {
        try {
            return entryPointTypeOf(Files.readString(file), false, MAIN_METHOD).isPresent();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
