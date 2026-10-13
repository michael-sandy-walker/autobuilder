package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T6-T: every class a build reports missing that the project's classpath already provides is imported, in every
 * file that misses it, in ONE fix.
 *
 * <p><b>The gap, from the field.</b> Ladder-grind runs 2026-10-12_21-49-02, 2026-10-12_23-51-29 and
 * 2026-10-13_01-53-56: after the suite compiled, 45 candidates failed to compile and 35 reached a test run. A
 * candidate that used three library classes correctly and imported none of them ({@code Node},
 * {@code LambdaExpr}, {@code ObjectCreationExpr}) was repaired one diagnostic at a time: the second pass was the
 * last, and a name several jars declare ({@code Node}: 13 classes on that classpath) was not repaired at all --
 * the chain went on to create an empty class of that name in the project, which then hid the library's.
 *
 * <p><b>Which class, when several share the name.</b> The file says it: the one candidate that lives in a jar
 * the file already imports from, and among several in that jar the one whose package is nearest to those
 * imports. No candidate singled out that way is left alone -- the caller's other paths, or the model, decide.
 * A name the project itself declares is never imported from a jar.
 *
 * <p>Nothing is added to the build file: every class imported here is on the resolved classpath already.
 */
@Slf4j
@Component
@Order(25)
@RequiredArgsConstructor
public class ClasspathTypeImportsResolver implements CompileFixResolver {

    static final String RULE = "classpath-type-imports";
    private static final String OUTPUT_DIR = "output";

    /** One "cannot find symbol ... symbol: class X" diagnostic: the file, then the class. */
    private static final Pattern MISSING_CLASS = Pattern.compile(
            "(\\S+\\.java):\\[\\d+,\\d+\\][^\\n]*cannot find symbol\\s*\\n(?:\\[\\w+\\])?\\s*symbol:\\s+class\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern IMPORT_LINE = Pattern.compile(
            "(?m)^\\s*import\\s+(?!static\\b)([\\w$]+(?:\\.[\\w$]+)*)(\\.\\*)?\\s*;");
    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private final FileService fileService;
    private final PackageDiscoveryService discovery;

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken, String stderr,
                                         CycleContextData context) {
        if (stderr == null || stderr.isBlank()) return Optional.empty();
        Path outputRoot;
        try {
            outputRoot = fileService.resolveSecurePath(OUTPUT_DIR).toAbsolutePath().normalize();
        } catch (IOException e) {
            return Optional.empty();
        }
        if (!Files.isDirectory(outputRoot)) return Optional.empty();

        Map<Path, Set<String>> missing = new LinkedHashMap<>();
        Matcher m = MISSING_CLASS.matcher(stderr);
        while (m.find()) {
            Path file = Path.of(m.group(1)).toAbsolutePath().normalize();
            if (file.startsWith(outputRoot) && Files.isRegularFile(file)) {
                missing.computeIfAbsent(file, k -> new LinkedHashSet<>()).add(m.group(2));
            }
        }
        if (missing.isEmpty()) return Optional.empty();

        Set<String> declaredByProject = typesTheProjectDeclares(outputRoot);
        Map<String, Map<String, String>> onClasspath = new LinkedHashMap<>();
        List<FileMutation> mutations = new ArrayList<>();
        List<String> touched = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (Map.Entry<Path, Set<String>> e : missing.entrySet()) {
            String content;
            try {
                content = Files.readString(e.getKey());
            } catch (IOException ex) {
                continue;
            }
            String patched = content;
            for (String name : e.getValue()) {
                if (declaredByProject.contains(name)) continue;
                Map<String, String> candidates = onClasspath.computeIfAbsent(name,
                        n -> discovery.classpathTypes(n, outputRoot));
                String fqn = chosen(candidates, patched);
                if (fqn == null || imports(patched, fqn)) continue;
                String next = SymbolImportResolver.insertImport(patched, fqn);
                if (next == null) continue;
                patched = next;
                if (!added.contains(fqn)) added.add(fqn);
            }
            if (!patched.equals(content)) {
                String rel = outputRoot.relativize(e.getKey()).toString().replace('\\', '/');
                mutations.add(new FileMutation(rel, patched));
                touched.add(rel);
            }
        }
        if (mutations.isEmpty()) return Optional.empty();

        String description = "Added the import of " + String.join(", ", added)
                + " -- on the project's classpath, used by the change and not imported";
        String token = added.get(0).substring(added.get(0).lastIndexOf('.') + 1);
        log.info("CompileFixInjector: {} -- {} (touched {})", RULE, description, touched);
        return Optional.of(new FixProposal(mutations,
                new InjectorFixRecord(RULE, Kind.CANNOT_FIND_SYMBOL, token, touched, description)));
    }

    /**
     * The one class among {@code candidates} (fully qualified name to providing jar) that {@code source} means, or
     * null: the only one there is; else the only one in a jar the file's imports already come from; else, among
     * several in such a jar, the one whose package shares most with those imports -- when one does.
     */
    static String chosen(Map<String, String> candidates, String source) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.keySet().iterator().next();
        List<String> imported = new ArrayList<>();
        Matcher m = IMPORT_LINE.matcher(source);
        while (m.find()) imported.add(m.group(1));
        if (imported.isEmpty()) return null;

        Map<String, List<String>> importsByJar = new LinkedHashMap<>();
        for (String jar : new LinkedHashSet<>(candidates.values())) {
            List<String> fromJar = providedBy(jar, imported);
            if (!fromJar.isEmpty()) importsByJar.put(jar, fromJar);
        }
        List<String> testified = candidates.entrySet().stream()
                .filter(c -> importsByJar.containsKey(c.getValue())).map(Map.Entry::getKey).toList();
        if (testified.size() == 1) return testified.get(0);
        if (testified.isEmpty()) return null;

        String best = null;
        int bestShared = -1;
        boolean tie = false;
        for (String fqn : testified) {
            int shared = importsByJar.get(candidates.get(fqn)).stream()
                    .mapToInt(i -> sharedSegments(fqn, i)).max().orElse(0);
            if (shared > bestShared) {
                best = fqn;
                bestShared = shared;
                tie = false;
            } else if (shared == bestShared) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /** The names among {@code imported} -- a class, or a package for a wildcard -- that {@code jar} holds. */
    private static List<String> providedBy(String jar, List<String> imported) {
        List<String> held = new ArrayList<>();
        try (JarFile file = new JarFile(jar)) {
            for (String name : imported) {
                String path = name.replace('.', '/');
                // A wildcard names a package; a jar need not list its directories, so its classes are asked.
                if (file.getEntry(path + ".class") != null || file.getEntry(path + "/") != null
                        || file.stream().anyMatch(en -> en.getName().startsWith(path + "/"))) held.add(name);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return held;
    }

    private static int sharedSegments(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        int n = 0;
        while (n < x.length - 1 && n < y.length && x[n].equals(y[n])) n++;
        return n;
    }

    private static boolean imports(String source, String fqn) {
        Matcher m = IMPORT_LINE.matcher(source);
        String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
        while (m.find()) {
            if (m.group(2) == null ? m.group(1).equals(fqn) : m.group(1).equals(pkg)) return true;
        }
        return false;
    }

    /** The simple names of the types the project's own sources declare, by file name. */
    private static Set<String> typesTheProjectDeclares(Path outputRoot) {
        Set<String> names = new LinkedHashSet<>();
        Path src = outputRoot.resolve("src");
        if (!Files.isDirectory(src)) return names;
        try (java.util.stream.Stream<Path> walk = Files.walk(src)) {
            walk.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".java")).forEach(n -> names.add(n.substring(0, n.length() - 5)));
        } catch (IOException e) {
            log.debug("T6-T: could not list the project's sources under {}: {}", src, e.getMessage());
        }
        return names;
    }
}
