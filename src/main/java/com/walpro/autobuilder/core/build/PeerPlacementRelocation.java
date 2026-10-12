package com.walpro.autobuilder.core.build;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer.PeerPlacementAnomaly;

/**
 * Moves a type to the package its own structural peers established, and repairs every reference.
 *
 * <p>WHY THIS EXISTS. The fact was detected correctly and consumed by nothing. Across nine
 * archived runs the placement anomaly was stated for the whole life of the run — still being
 * reported minutes before the log ended — and in all nine the artifact finished with the type
 * still sitting apart from its peers. That was the pre-registered condition for building this:
 * a readout that never moves anything is not an actuator, however often it fires.
 *
 * <p>WHAT MAKES IT SAFE TO DO DETERMINISTICALLY. The finding is only made when the project has
 * already stated a norm — three or more nominating peers, a strict majority, silence on a tie —
 * so the destination is never chosen by this code; it is read off the project's own layout. The
 * same evidence condition is written down for the duplication axis in T4-BA step 3(b), which
 * cautions that taking the majority is risky for a type that genuinely should sit apart. That
 * caution is answered by the detector's gate, not re-litigated here: this moves only what the
 * report would already have named.
 *
 * <p>WHAT IT REFUSES. No move when the project declares no entry point, when the destination
 * would fall outside the subtree the entry point's package roots, when the destination already
 * holds a file of that name, or when the declaration cannot be located. The entry-point rule is
 * structural rather than ecosystem knowledge: whatever a project uses to discover its own types,
 * a move that leaves the subtree the application already occupies cannot be verified from source,
 * so it is not made ([[feedback-no-ecosystem-jargon-in-framework-code]]).
 */
public final class PeerPlacementRelocation {

    /**
     * One planned move. Carries coordinates only — every rewrite is produced against whatever the
     * sources hold at apply time, so a plan made from a slightly stale map cannot clobber fresher
     * text.
     */
    public record Relocation(String typeName, String fromPath, String toPath,
                             String fromPackage, String toPackage) {}

    /** The rewrites a move needs: new or replaced file contents, and the path it vacates. */
    public record Applied(Map<String, String> writes, String vacatedPath) {}

    private static final Pattern JAVA_FILE = Pattern.compile(".*\\.java$");
    private static final Pattern MAIN_METHOD = Pattern.compile(
            "static\\s+(?:final\\s+)?void\\s+main\\s*\\(\\s*(?:final\\s+)?String\\s*(?:\\[\\s*\\]|\\.\\.\\.)");

    private PeerPlacementRelocation() {}

    /**
     * Plans a move per finding, in the order the findings were reported. A finding that fails any
     * precondition is skipped rather than approximated.
     */
    public static List<Relocation> plan(Map<String, String> sources, List<PeerPlacementAnomaly> findings) {
        if (sources == null || sources.isEmpty() || findings == null || findings.isEmpty()) return List.of();
        Set<String> entryPackages = entryPointPackages(sources);
        if (entryPackages.isEmpty()) return List.of();

        Map<String, Relocation> byPath = new LinkedHashMap<>();
        for (PeerPlacementAnomaly f : findings) {
            String to = f.majorityPackage();
            if (to == null || to.isBlank()) continue;
            if (entryPackages.stream().noneMatch(root -> isUnder(to, root))) continue;
            // The destination is the peers' own home, so the project has already put code there.
            if (packageIsEmpty(sources, to)) continue;

            Optional<String> from = declaringFile(sources, f.typeName(), f.actualPackage());
            if (from.isEmpty() || byPath.containsKey(from.get())) continue;
            String toPath = repackagedPath(from.get(), f.actualPackage(), to);
            if (toPath == null || sources.containsKey(toPath)) continue;
            byPath.put(from.get(), new Relocation(f.typeName(), from.get(), toPath, f.actualPackage(), to));
        }
        return List.copyOf(byPath.values());
    }

    /**
     * Produces every rewrite the move needs, against the CURRENT sources: the moved file under its
     * new package, references to it rewritten wherever they are qualified, and an import wherever
     * a file relied on same-package visibility that the move takes away — in both directions,
     * since the moved file loses the package it is leaving as surely as that package loses it.
     *
     * <p>Empty when the source no longer declares the type where the plan said, so a file
     * rewritten since the plan was made is left alone.
     */
    public static Optional<Applied> apply(Map<String, String> sources, Relocation r) {
        if (sources == null || r == null) return Optional.empty();
        if (r.fromPackage().equals(r.toPackage())) return Optional.empty();
        String moved = sources.get(r.fromPath());
        if (moved == null || !declares(moved, r.typeName())) return Optional.empty();

        Map<String, Set<String>> typesByPackage = declaredTypesByPackage(sources);
        Map<String, String> writes = new LinkedHashMap<>();

        // The moved file: new package declaration, plus an import for every type it was reading
        // from its old package by simple name.
        String movedOut = PackageConsolidationService.rewritePackageDeclaration(moved, r.toPackage());
        for (String left : typesByPackage.getOrDefault(r.fromPackage(), Set.of())) {
            if (left.equals(r.typeName())) continue;
            if (!referencesSimpleName(moved, left)) continue;
            movedOut = withImport(movedOut, r.fromPackage() + "." + left);
        }
        movedOut = rewriteQualified(movedOut, r);
        writes.put(r.toPath(), movedOut);

        String newFqn = qualify(r.toPackage(), r.typeName());
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || path.equals(r.fromPath())) continue;
            if (!JAVA_FILE.matcher(path).matches()) continue;

            String out = rewriteQualified(content, r);
            String pkg = packageDeclaredIn(content);
            if (pkg.equals(r.fromPackage()) && referencesSimpleName(content, r.typeName())) {
                out = withImport(out, newFqn);
            } else if (pkg.equals(r.toPackage())) {
                // The import that qualified it across a package boundary is now same-package noise.
                out = withoutImport(out, newFqn);
            }
            if (!out.equals(content)) writes.put(path, out);
        }
        return Optional.of(new Applied(Map.copyOf(writes), r.fromPath()));
    }

    /**
     * Packages of every type declaring a {@code main} entry point. A project with none gets no
     * move at all — without a root there is nothing to judge the destination against.
     */
    static Set<String> entryPointPackages(Map<String, String> sources) {
        Set<String> packages = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (e.getKey() == null || !JAVA_FILE.matcher(e.getKey()).matches()) continue;
            if (e.getValue() == null || com.walpro.autobuilder.core.persistence.EntryPointScan
                    .entryPointTypeOf(e.getValue(), false, MAIN_METHOD).isEmpty()) continue;
            packages.add(packageDeclaredIn(e.getValue()));
        }
        return packages;
    }

    /** Whether {@code candidate} is {@code root} or nests inside it, on a dot boundary. */
    static boolean isUnder(String candidate, String root) {
        if (root == null || root.isEmpty()) return true;   // a root-package entry point roots everything
        return candidate.equals(root) || candidate.startsWith(root + ".");
    }

    private static boolean packageIsEmpty(Map<String, String> sources, String pkg) {
        return sources.entrySet().stream()
                .filter(e -> e.getKey() != null && JAVA_FILE.matcher(e.getKey()).matches())
                .filter(e -> e.getValue() != null)
                .noneMatch(e -> packageDeclaredIn(e.getValue()).equals(pkg));
    }

    /** The file declaring the type, confirmed to sit in the package the finding named. */
    private static Optional<String> declaringFile(Map<String, String> sources, String typeName, String pkg) {
        return sources.entrySet().stream()
                .filter(e -> e.getKey() != null && JAVA_FILE.matcher(e.getKey()).matches())
                .filter(e -> e.getValue() != null && declares(e.getValue(), typeName))
                .filter(e -> packageDeclaredIn(e.getValue()).equals(pkg == null ? "" : pkg))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** The same path with the old package's directories replaced by the new one's. */
    static String repackagedPath(String path, String fromPackage, String toPackage) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) return null;
        String dir = path.substring(0, slash);
        String file = path.substring(slash + 1);
        String fromDir = fromPackage == null ? "" : fromPackage.replace('.', '/');
        String toDir = toPackage.replace('.', '/');
        if (fromDir.isEmpty()) return dir + "/" + toDir + "/" + file;
        if (!dir.endsWith(fromDir)) return null;
        return dir.substring(0, dir.length() - fromDir.length()) + toDir + "/" + file;
    }

    /**
     * Rewrites qualified references to the moved type. The trailing boundary matters: without it
     * {@code a.b.Book} also matches the front of {@code a.b.BookRepository} and renames a type
     * nobody asked about.
     */
    private static String rewriteQualified(String content, Relocation r) {
        String oldFqn = qualify(r.fromPackage(), r.typeName());
        String newFqn = qualify(r.toPackage(), r.typeName());
        if (oldFqn.equals(newFqn)) return content;
        return content.replaceAll(Pattern.quote(oldFqn) + "(?![\\w$])", Matcher.quoteReplacement(newFqn));
    }

    private static String qualify(String pkg, String typeName) {
        return pkg == null || pkg.isEmpty() ? typeName : pkg + "." + typeName;
    }

    private static boolean declares(String source, String typeName) {
        return Pattern.compile("\\b(class|interface|record|enum)\\s+" + Pattern.quote(typeName) + "\\b")
                .matcher(source).find();
    }

    private static boolean referencesSimpleName(String source, String typeName) {
        return Pattern.compile("\\b" + Pattern.quote(typeName) + "\\b").matcher(source).find();
    }

    static String packageDeclaredIn(String source) {
        Matcher m = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(source);
        return m.find() ? m.group(1) : "";
    }

    private static Map<String, Set<String>> declaredTypesByPackage(Map<String, String> sources) {
        Map<String, Set<String>> byPackage = new LinkedHashMap<>();
        Pattern declaration = Pattern.compile("(?m)^[ \\t]*(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+"
                + "|non-sealed\\s+)*(?:class|interface|record|enum)\\s+(\\w+)");
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (e.getKey() == null || !JAVA_FILE.matcher(e.getKey()).matches() || e.getValue() == null) continue;
            Matcher m = declaration.matcher(e.getValue());
            String pkg = packageDeclaredIn(e.getValue());
            while (m.find()) byPackage.computeIfAbsent(pkg, k -> new LinkedHashSet<>()).add(m.group(1));
        }
        return byPackage;
    }

    /** Adds the import after the package declaration, unless the file already has it. */
    private static String withImport(String source, String fqn) {
        if (Pattern.compile("(?m)^\\s*import\\s+" + Pattern.quote(fqn) + "\\s*;").matcher(source).find()) {
            return source;
        }
        Matcher pkg = Pattern.compile("(?m)^package\\s+[\\w.]+\\s*;\\s*$").matcher(source);
        if (pkg.find()) {
            return source.substring(0, pkg.end()) + "\n\nimport " + fqn + ";" + source.substring(pkg.end());
        }
        return "import " + fqn + ";\n" + source;
    }

    private static String withoutImport(String source, String fqn) {
        return source.replaceAll("(?m)^[ \\t]*import\\s+" + Pattern.quote(fqn) + "\\s*;[ \\t]*\\r?\\n", "");
    }
}
