package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.ProjectClasspathService;
import com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.Resolution;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-BM: project-aware package discovery — resolves a missing symbol or package from the
 * project's OWN observations instead of a hardcoded registry row. The registry's frozen worldview
 * ("mockito is 5.7.0", "NotNull lives in jakarta") loses to what the project actually declares;
 * discovery has an error-correction path (the project changes → the answers change) where the
 * registry only has row-adds.
 *
 * <p>Sources, cheap → expensive, with provenance on every hit:
 * <ol>
 *   <li><b>classpath</b> — the project's resolved dependency classpath (shared with the compile
 *       probe via {@link ProjectClasspathService}). A type found in a classpath jar yields its
 *       exact FQN and the coordinates the PROJECT resolved (version drift impossible); the GAV
 *       is recovered from the local-repository path layout. Ambiguity (same simple name in
 *       several packages) abstains unless the project's own imports disambiguate.</li>
 *   <li><b>project-imports</b> — {@code import} statements across the project's sources. If the
 *       project already imports {@code a.b.C} somewhere, that IS its resolution of {@code C};
 *       coordinates come from the classpath jar providing it, or from the coordinate search
 *       when the artifact is not on the classpath yet.</li>
 *   <li><b>coordinate-search</b> — {@link CoordinateSearchClient}, consulted only with an exact
 *       FQN in hand (see its Javadoc for why bare-name search is out).</li>
 * </ol>
 *
 * <p>Every abstain leaves the caller free to fall back to the {@link WellKnownPackageRegistry}
 * table (short-term fallback rows) and then the LLM lookup — discovery narrows the registry's
 * job, never widens the failure surface.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackageDiscoveryService {

    private static final long CLASSPATH_TIMEOUT_MS = 30_000L;
    private static final Pattern SIMPLE_TYPE_NAME = Pattern.compile("[A-Z][A-Za-z0-9_$]*");
    private static final Pattern IMPORT_LINE = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?([\\w$]+(?:\\.[\\w$]+)*)(\\.\\*)?\\s*;");

    private final ProjectClasspathService classpathService;
    private final CoordinateSearchClient coordinateSearchClient;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /** A discovery result: the resolution plus which source produced it. */
    public record Discovery(Resolution resolution, String source) {}

    /**
     * Resolves a missing type's simple name against project observations. Empty = no observation or
     * an ambiguous observation (caller falls back to registry/LLM); never guesses.
     */
    public Optional<Discovery> lookupType(String simpleName, Path projectRoot) {
        if (simpleName == null || simpleName.isBlank() || simpleName.contains(".")) return Optional.empty();
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return Optional.empty();

        List<String> jars = classpathJars(projectRoot);
        Map<String, Set<String>> importIndex = importIndex(projectRoot);

        // Sources 0+1: the JDK platform and the resolved classpath. The platform is an observation the
        // jar scan cannot see (platform types live in the runtime image, not in jars) — without
        // it, a jar that republishes a platform simple name is the ONLY hit and wins wrongly
        // (field 2026-07-08/09: Optional -> org.apache.el.stream.Optional via tomcat-embed-el,
        // injected into six files + pom, churning for four runs).
        Map<String, String> onClasspath = scanJarsForType(jars, simpleName);
        Set<String> jdkExported = JdkPlatformTypeIndex.exportedTypes(simpleName);
        Set<String> jdkBase = JdkPlatformTypeIndex.javaBaseTypes(simpleName);
        Set<String> imported = importIndex.getOrDefault(simpleName, Set.of());

        // (a) The project's own imports pick among ALL candidates (classpath ∪ platform).
        Set<String> candidates = new LinkedHashSet<>(onClasspath.keySet());
        candidates.addAll(jdkExported);
        List<String> importMatches = candidates.stream().filter(imported::contains).toList();
        if (importMatches.size() == 1) {
            String fqn = importMatches.get(0);
            return onClasspath.containsKey(fqn)
                    ? Optional.of(discovered(simpleName, "classpath", resolutionFor(fqn, onClasspath.get(fqn))))
                    : Optional.of(discovered(simpleName, "jdk-platform", new Resolution(fqn, null, null, null)));
        }

        // (b) Unique classpath hit, unshadowed by a java.base name → the project's own resolved
        // version, exactly as before the platform source existed. A java.base collision blocks
        // this path (the Optional trap above); non-base platform modules do not — no field damage
        // observed there, and blocking would regress measured-good hits (e.g. Entity, Result).
        if (onClasspath.size() == 1 && jdkBase.isEmpty()) {
            Map.Entry<String, String> hit = onClasspath.entrySet().iterator().next();
            // (b2) Annotation-usage testimony: when every usage of the name in the project's
            // sources is @-form, the candidate must BE an annotation type (field 2026-07-16:
            // @Param in a repository resolved to the JSP tag POJO
            // org.springframework.web.servlet.tags.Param — the only classpath claimant — and
            // the re-probe failed with "cannot be converted to java.lang.annotation.Annotation").
            // Unreadable or unconfirmable class bytes count as unconfirmed — never guess.
            if (usageForm(projectRoot, simpleName) == UsageForm.ANNOTATION
                    && !confirmedAnnotationType(hit.getValue(), hit.getKey())) {
                log.debug("T4-BM: {} is used only as an annotation but the sole classpath candidate {} "
                                + "is not an annotation type — abstaining",
                        simpleName, hit.getKey());
                return Optional.empty();
            }
            return Optional.of(discovered(simpleName, "classpath",
                    resolutionFor(hit.getKey(), hit.getValue())));
        }

        // (c) java.base exports exactly this name, nothing on the classpath claims it and no
        // import testifies — but uniqueness in java.base is not intent. With zero usage
        // testimony the name is equally a domain-vocabulary collision (field 2026-07-15: the
        // library goal's own Member entity resolved to java.lang.reflect.Member 3x, and the
        // injected single-type import SHADOWED same-package resolution, re-poisoning the file
        // after every rewrite until evolution dropped it). The rule's only live firings on
        // record are those three wrong ones. Same principle as (d): only usage context this
        // source lacks can decide — abstain; the registry/LLM fallbacks carry the genuinely
        // missing-platform-import case at the cost of one lookup.
        if (jdkBase.size() == 1 && onClasspath.isEmpty() && imported.isEmpty()) {
            log.debug("T4-BM: {} matches java.base ({}) with no usage testimony — abstaining (domain-name collision risk)",
                    simpleName, jdkBase.iterator().next());
            return Optional.empty();
        }

        // (d) Conflict or multi-way ambiguity (several classpath packages; classpath vs java.base,
        // e.g. jakarta.validation.constraints.Pattern vs java.util.regex.Pattern) — only usage
        // context this source lacks can decide; the registry/LLM fallbacks carry it. Abstain.
        if (!onClasspath.isEmpty()) {
            log.debug("T4-BM: {} is ambiguous ({} classpath / {} platform candidates, imports do not disambiguate) — abstaining",
                    simpleName, onClasspath.size(), jdkExported.size());
            return Optional.empty();
        }

        // Source 2: the project's own imports. One consistent FQN across the sources is the
        // project's answer — but only for EXTERNAL types, and only when coordinates confirm:
        //  - An FQN under a package the project itself declares is a missing PROJECT type
        //    (the import is an aspiration, not an ecosystem observation — run 26 field lesson:
        //    45 junk resolutions of not-yet-created own-project repositories). Those belong
        //    to the skeleton/evolution paths, never to import resolution.
        //  - An FQN that is neither on the classpath nor searchable is a broken observation — the
        //    file importing it cannot compile either, and an import without a dependency
        //    cannot fix anything. Abstain so the registry fallback (which may know the
        //    providing artifact) gets its turn (run 26: import-only here would have masked
        //    the registry's correct spring-data row).
        if (imported.size() == 1) {
            String fqn = imported.iterator().next();
            String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
            if (declaredPackages(projectRoot).contains(pkg)) {
                log.debug("T4-BM: {} is imported under the project's own package {} — a missing project type, abstaining",
                        simpleName, pkg);
                return Optional.empty();
            }
            Optional<CoordinateSearchClient.Coordinates> coords =
                    coordinateSearchClient.findByFullyQualifiedName(fqn);
            if (coords.isPresent()) {
                return Optional.of(discovered(simpleName, "project-imports", new Resolution(
                        fqn, coords.get().groupId(), coords.get().artifactId(), coords.get().version())));
            }
        }

        return Optional.empty();
    }

    /**
     * Resolves a missing package name to the coordinates providing it — from the classpath only
     * (a package can't be searched by {@code fc:}, and imports of a missing package are the
     * problem, not an observation). The interesting case is failure mode 3: the project ALREADY has
     * the package on its resolved classpath under coordinates the registry would have gotten
     * wrong — the project's answer wins.
     */
    public Optional<Discovery> lookupPackage(String packageName, Path projectRoot) {
        if (packageName == null || packageName.isBlank() || !packageName.contains(".")) return Optional.empty();
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return Optional.empty();

        String dirPrefix = packageName.replace('.', '/') + "/";
        for (String jar : classpathJars(projectRoot)) {
            if (jarContainsPackage(jar, dirPrefix)) {
                Resolution resolution = resolutionFor(packageName, jar);
                if (resolution.hasGav()) {
                    return Optional.of(discovered(packageName, "classpath", resolution));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * T2-FA: coordinates for a package the build reports as ABSENT, when the classpath cannot
     * answer because the dependency was never declared. {@link #lookupPackage} covers the case
     * where the package is already resolved; this covers the case where nothing provides it.
     *
     * <p>A package name cannot be searched directly, but the same build names a type from it —
     * {@code package p does not exist} on the import line, {@code symbol: class T} on every use —
     * and {@code p.T} is an exact fully-qualified name, which is the only query this index answers
     * precisely. So the compiler's own two diagnostics, read together, supply what neither
     * supplies alone.
     *
     * <p>A package the project DECLARES in its own sources is never searched: that is a type the
     * loop has not written yet, and no dependency can supply it (the same distinction the
     * classpath path draws for an imported FQN under an own package).
     */
    public Optional<Discovery> lookupUndeclaredPackage(String packageName, String typeName, Path projectRoot) {
        Optional<Discovery> onClasspath = lookupPackage(packageName, projectRoot);
        if (onClasspath.isPresent()) return onClasspath;

        if (packageName == null || packageName.isBlank() || !packageName.contains(".")) return Optional.empty();
        if (typeName == null || !SIMPLE_TYPE_NAME.matcher(typeName).matches()) return Optional.empty();
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return Optional.empty();
        if (sharesNamespaceWithProject(packageName, declaredPackages(projectRoot))) {
            log.debug("T2-FA: {} is inside the project's own namespace — a type it has not written,"
                    + " not a dependency to add; abstaining", packageName);
            return Optional.empty();
        }

        String fqn = packageName + "." + typeName;
        Optional<CoordinateSearchClient.Coordinates> coords = coordinateSearchClient.findByFullyQualifiedName(fqn);
        if (coords.isEmpty()) return Optional.empty();
        return Optional.of(discovered(packageName, "coordinate-search", new Resolution(
                fqn, coords.get().groupId(), coords.get().artifactId(), coords.get().version())));
    }

    /**
     * Compared at namespace depth rather than by exact membership, because the package that is
     * MISSING is by definition one the project has not written yet — an exact check would call
     * an unwritten sibling of `…domain` an undeclared library and send its name to a public
     * artifact index.
     */
    private static boolean sharesNamespaceWithProject(String packageName, Set<String> declared) {
        String ns = namespaceOf(packageName);
        return ns != null && declared.stream().map(PackageDiscoveryService::namespaceOf)
                .anyMatch(ns::equals);
    }

    private static String namespaceOf(String pkg) {
        if (pkg == null) return null;
        String[] parts = pkg.split("\\.");
        return parts.length < 2 ? pkg : parts[0] + "." + parts[1];
    }

    // ── source 1: classpath jars ─────────────────────────────────────────────

    private List<String> classpathJars(Path projectRoot) {
        String classpath = classpathService.resolveClasspath(projectRoot, CLASSPATH_TIMEOUT_MS);
        if (classpath == null || classpath.isBlank()) return List.of();
        List<String> jars = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isBlank() && entry.endsWith(".jar")) jars.add(entry);
        }
        return jars;
    }

    /** FQN → providing-jar-path for every top-level class named {@code simpleName} on the classpath. */
    private static Map<String, String> scanJarsForType(List<String> jars, String simpleName) {
        String needle = "/" + simpleName + ".class";
        String rootNeedle = simpleName + ".class";
        Map<String, String> hits = new LinkedHashMap<>();
        for (String jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("META-INF/")) continue; // multi-release duplicates
                    if (name.indexOf('$') >= 0) continue;       // nested types can't be imported by simple name alone
                    if (!name.endsWith(needle) && !name.equals(rootNeedle)) continue;
                    String fqn = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    hits.putIfAbsent(fqn, jarPath);
                }
            } catch (IOException e) {
                log.debug("T4-BM: unreadable classpath jar {} — {}", jarPath, e.getMessage());
            }
        }
        return hits;
    }

    private static boolean jarContainsPackage(String jarPath, String dirPrefix) {
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(dirPrefix) && name.endsWith(".class")) return true;
            }
        } catch (IOException e) {
            log.debug("T4-BM: unreadable classpath jar {} — {}", jarPath, e.getMessage());
        }
        return false;
    }

    /**
     * Recovers coordinates from the local-repository path layout
     * ({@code …/repository/<group…>/<artifact>/<version>/<artifact>-<version>[….jar]}).
     * A jar outside that layout yields an FQN-only resolution — the type IS on the classpath,
     * so an import without a pom edit is still a complete fix.
     */
    private static Resolution resolutionFor(String fqn, String jarPath) {
        Optional<CoordinateSearchClient.Coordinates> gav = gavFromRepositoryPath(jarPath);
        return gav.map(c -> new Resolution(fqn, c.groupId(), c.artifactId(), c.version()))
                .orElseGet(() -> new Resolution(fqn, null, null, null));
    }

    static Optional<CoordinateSearchClient.Coordinates> gavFromRepositoryPath(String jarPath) {
        if (jarPath == null) return Optional.empty();
        String normalized = jarPath.replace('\\', '/');
        String[] segments = normalized.split("/");
        if (segments.length < 4) return Optional.empty();
        String fileName = segments[segments.length - 1];
        String version = segments[segments.length - 2];
        String artifactId = segments[segments.length - 3];
        if (!fileName.startsWith(artifactId + "-" + version)) return Optional.empty();
        int repoIdx = -1;
        for (int i = 0; i < segments.length - 3; i++) {
            if ("repository".equals(segments[i])) repoIdx = i;
        }
        if (repoIdx < 0 || repoIdx + 1 >= segments.length - 3) return Optional.empty();
        String groupId = String.join(".",
                List.of(segments).subList(repoIdx + 1, segments.length - 3));
        return Optional.of(new CoordinateSearchClient.Coordinates(groupId, artifactId, version));
    }

    /** Packages the project's own sources declare ({@code package a.b;} lines). An import
     *  under one of these is a reference to a project type, existing or not. */
    private static Set<String> declaredPackages(Path projectRoot) {
        Path srcRoot = projectRoot.resolve("src");
        if (!Files.isDirectory(srcRoot)) return Set.of();
        Set<String> packages = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            for (String line : Files.readAllLines(p)) {
                                Matcher m = PACKAGE_LINE.matcher(line);
                                if (m.find()) {
                                    packages.add(m.group(1));
                                    break;
                                }
                                if (!line.isBlank() && !line.strip().startsWith("//")
                                        && !line.strip().startsWith("/*") && !line.strip().startsWith("*")) {
                                    break; // past the header — no package declaration
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.debug("T4-BM: declared-package walk failed at {} — {}", srcRoot, e.getMessage());
        }
        return packages;
    }

    private static final Pattern PACKAGE_LINE = Pattern.compile(
            "^\\s*package\\s+([\\w$]+(?:\\.[\\w$]+)*)\\s*;");

    // ── usage testimony ──────────────────────────────────────────────────────

    /** How the project's sources use a simple name: exclusively as an annotation ({@code @Name}),
     *  in any other identifier position, or not at all. Mixed usage counts as OTHER — only
     *  unanimous @-form testimony constrains a resolution. */
    enum UsageForm { NONE, ANNOTATION, OTHER }

    static UsageForm usageForm(Path projectRoot, String simpleName) {
        Path srcRoot = projectRoot.resolve("src");
        if (!Files.isDirectory(srcRoot)) return UsageForm.NONE;
        Pattern token = Pattern.compile("(@\\s*)?\\b" + Pattern.quote(simpleName) + "\\b");
        boolean[] seen = new boolean[2]; // [annotation, other]
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            for (String line : Files.readAllLines(p)) {
                                String stripped = line.strip();
                                if (stripped.startsWith("import ") || stripped.startsWith("package ")
                                        || stripped.startsWith("//") || stripped.startsWith("*")
                                        || stripped.startsWith("/*")) {
                                    continue;
                                }
                                Matcher m = token.matcher(line);
                                while (m.find()) {
                                    if (m.group(1) != null) seen[0] = true;
                                    else seen[1] = true;
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.debug("T4-BM: usage-form walk failed at {} — {}", srcRoot, e.getMessage());
            return UsageForm.NONE;
        }
        if (seen[0] && !seen[1]) return UsageForm.ANNOTATION;
        if (seen[1]) return UsageForm.OTHER;
        return UsageForm.NONE;
    }

    /** True only when the jar entry for {@code fqn} parses as a class file whose access flags
     *  carry ACC_ANNOTATION. Anything unreadable is unconfirmed (false). */
    private static boolean confirmedAnnotationType(String jarPath, String fqn) {
        String entryName = fqn.replace('.', '/') + ".class";
        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) return false;
            try (java.io.DataInputStream in = new java.io.DataInputStream(jar.getInputStream(entry))) {
                return classAccessFlagsShowAnnotation(in);
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** Minimal class-file walk: magic, version, constant pool (skipped by tag), access flags.
     *  ACC_ANNOTATION = 0x2000 (JVMS 4.1). */
    private static boolean classAccessFlagsShowAnnotation(java.io.DataInputStream d) throws IOException {
        if (d.readInt() != 0xCAFEBABE) return false;
        d.readUnsignedShort(); // minor
        d.readUnsignedShort(); // major
        int constantPoolCount = d.readUnsignedShort();
        for (int i = 1; i < constantPoolCount; i++) {
            int tag = d.readUnsignedByte();
            switch (tag) {
                case 1 -> d.skipNBytes(d.readUnsignedShort());      // Utf8
                case 7, 8, 16, 19, 20 -> d.skipNBytes(2);           // Class, String, MethodType, Module, Package
                case 15 -> d.skipNBytes(3);                          // MethodHandle
                case 3, 4, 9, 10, 11, 12, 17, 18 -> d.skipNBytes(4); // int/float/refs/NameAndType/Dynamic
                case 5, 6 -> { d.skipNBytes(8); i++; }               // long/double take two pool slots
                default -> { return false; }                         // unknown tag — not a class file we can trust
            }
        }
        int accessFlags = d.readUnsignedShort();
        return (accessFlags & 0x2000) != 0;
    }

    // ── source 2: project source imports ────────────────────────────────────

    /**
     * Simple name → set of FQNs the project's sources import it as. Wildcard and static imports
     * are skipped (no simple-name→type mapping to extract). Rebuilt per lookup — the walk is a
     * few dozen files and lookups only happen on unresolved symbols.
     */
    private static Map<String, Set<String>> importIndex(Path projectRoot) {
        Path srcRoot = projectRoot.resolve("src");
        if (!Files.isDirectory(srcRoot)) return Map.of();
        Map<String, Set<String>> index = new HashMap<>();
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            for (String line : Files.readAllLines(p)) {
                                Matcher m = IMPORT_LINE.matcher(line);
                                if (!m.find()) continue;
                                if (m.group(1) != null || m.group(3) != null) continue; // static / wildcard
                                String fqn = m.group(2);
                                int lastDot = fqn.lastIndexOf('.');
                                if (lastDot <= 0) continue;
                                index.computeIfAbsent(fqn.substring(lastDot + 1), k -> new LinkedHashSet<>())
                                        .add(fqn);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.debug("T4-BM: import-index walk failed at {} — {}", srcRoot, e.getMessage());
        }
        return index;
    }

    // ── provenance ───────────────────────────────────────────────────────────

    private Discovery discovered(String symbol, String source, Resolution resolution) {
        log.info("T4-BM: discovery resolved {} -> {} via {}{}",
                symbol, resolution.fqn(), source,
                resolution.hasGav()
                        ? " (" + resolution.groupId() + ":" + resolution.artifactId()
                                + (resolution.version() != null ? ":" + resolution.version() : "") + ")"
                        : "");
        if (behaviourLogger != null) {
            try {
                behaviourLogger.packageDiscoveryHit(symbol, source, resolution.fqn(),
                        resolution.groupId(), resolution.artifactId(), resolution.version());
            } catch (Exception e) {
                log.debug("packageDiscoveryHit emit failed: {}", e.getMessage());
            }
        }
        return new Discovery(resolution, source);
    }
}
