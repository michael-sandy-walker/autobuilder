package com.walpro.autobuilder.core.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EC: where a project's modules are -- the build root, and every module its build file lists, nested ones included.
 *
 * <p>ledger-import-modules run 2026-10-21_15-48-14: a multi-module child (linetokens/ and cashbook/ under an aggregator
 * pom) scored 40/40 by minute 10 and never closed. The test gate read reports only from the build root's
 * {@code target/surefire-reports}, a reactor build writes them per module, so the gate never held a report of the
 * tree and every closure deferred on "no test run of this tree". Every reader of a test run asks this class instead
 * of assuming the root is the only module. A project with no {@code <modules>} has one module: its root.
 */
public final class ProjectLayout {

    private static final Pattern MODULE = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");

    private ProjectLayout() {
    }

    /** T4-EC: names the module that runs, in a build of several: a fact about the project, the operator's, kept in its tree. */
    public static final String RUN_MODULE_FILE = ".run-module";

    /** Where the runnable artifact is built: the module {@value #RUN_MODULE_FILE} names, else the build root. */
    public static Path runnableRoot(Path projectRoot) {
        try {
            Path marker = projectRoot.resolve(RUN_MODULE_FILE);
            if (Files.isRegularFile(marker)) {
                Path module = projectRoot.resolve(Files.readString(marker).strip()).normalize();
                if (Files.isDirectory(module)) return module;
            }
        } catch (Exception e) {
            // an unreadable marker names nothing: the root
        }
        return projectRoot;
    }

    /** The build root first, then every module its build file lists (depth first), each once; only those that exist. */
    public static List<Path> moduleRoots(Path projectRoot) {
        Set<Path> out = new LinkedHashSet<>();
        collect(projectRoot.normalize(), out, 0);
        return List.copyOf(out);
    }

    private static void collect(Path root, Set<Path> out, int depth) {
        if (!Files.isDirectory(root) || !out.add(root) || depth > 8) return;
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) return;
        try {
            String xml = Files.readString(pom).replaceAll("(?s)<!--.*?-->", "");
            Matcher m = MODULE.matcher(xml);
            while (m.find()) collect(root.resolve(m.group(1)).normalize(), out, depth + 1);
        } catch (Exception e) {
            // an unreadable build file lists no modules: the root alone
        }
    }

    /** {@code relative} under every module root, where it exists. */
    public static List<Path> underEveryModule(Path projectRoot, String relative) {
        List<Path> out = new ArrayList<>();
        for (Path m : moduleRoots(projectRoot)) {
            Path p = m.resolve(relative);
            if (Files.exists(p)) out.add(p);
        }
        return out;
    }

    /** The module {@code file} belongs to: its nearest enclosing module root, else the build root. */
    public static Path moduleOf(Path projectRoot, Path file) {
        Path best = projectRoot.normalize();
        Path f = file.normalize();
        for (Path m : moduleRoots(projectRoot)) {
            if (f.startsWith(m) && m.getNameCount() > best.getNameCount()) best = m;
        }
        return best;
    }
}
