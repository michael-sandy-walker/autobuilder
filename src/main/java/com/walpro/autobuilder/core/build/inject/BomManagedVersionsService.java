package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.ProjectClasspathService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T4-BM step 4: the project's own dependency-management (BOM) chain as the version authority.
 * Walks the pom's {@code <parent>} chain (and {@code scope=import} BOM imports) through the local
 * repository — files that are already on disk because the project builds — and answers "which
 * version does this project's own build system pick for (groupId, artifactId)?".
 *
 * <p>Why: every version literal a fix writes into the pom is a chance to drift from what the
 * project's BOM manages. Field 2026-07-09..14: the coordinate search picked a MILESTONE
 * ({@code jakarta.validation-api:3.1.0-M1}, its index's latest) while the project's parent BOM
 * managed 3.0.2 — the milestone propagated into the pom and later classpath resolutions carried
 * it. When the BOM manages an artifact, the right pom edit declares NO version at all and lets
 * the BOM govern; this service is how callers know.
 *
 * <p>Pure build-system semantics: parent chains, {@code <dependencyManagement>}, property
 * interpolation. No framework or artifact names are special-cased anywhere.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BomManagedVersionsService {

    private static final int MAX_POM_CHAIN_DEPTH = 8;
    private static final long CLASSPATH_TIMEOUT_MS = 30_000L;

    private final ProjectClasspathService classpathService;

    /** Cached managed-versions map keyed by {@code (pomPath, pomMtime)}. Single-entry — the loop
     *  only ever evolves one project (same shape as {@link ProjectClasspathService}). */
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    private record CacheEntry(String pomPath, long pomMtimeMs, Map<String, String> managed) {}

    /**
     * The version the project's own dependency-management chain picks for the artifact, or empty
     * when nothing in the chain manages it (or the chain cannot be read). Callers adding a
     * dependency should omit the version element on a hit — the managed version then governs.
     */
    public Optional<String> managedVersion(Path projectRoot, String groupId, String artifactId) {
        if (projectRoot == null || groupId == null || groupId.isBlank()
                || artifactId == null || artifactId.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> managed = managedMap(projectRoot);
        return Optional.ofNullable(managed.get(groupId + ":" + artifactId));
    }

    private Map<String, String> managedMap(Path projectRoot) {
        Path pomPath = projectRoot.resolve("pom.xml");
        long mtime;
        try {
            if (!Files.isRegularFile(pomPath)) return Map.of();
            mtime = Files.getLastModifiedTime(pomPath).toMillis();
        } catch (IOException e) {
            return Map.of();
        }
        CacheEntry cached = cache.get();
        if (cached != null && cached.pomPath().equals(pomPath.toString()) && cached.pomMtimeMs() == mtime) {
            return cached.managed();
        }
        Map<String, String> managed = buildManagedMap(pomPath, localRepositoryRoot(projectRoot));
        cache.set(new CacheEntry(pomPath.toString(), mtime, managed));
        return managed;
    }

    /**
     * The local repository root, derived from the project's own resolved classpath (the first jar
     * living under a {@code …/repository/…} layout names it); falls back to the conventional
     * {@code ~/.m2/repository} when the classpath gives no signal.
     */
    private Path localRepositoryRoot(Path projectRoot) {
        String classpath = classpathService.resolveClasspath(projectRoot, CLASSPATH_TIMEOUT_MS);
        if (classpath != null && !classpath.isBlank()) {
            for (String entry : classpath.split(File.pathSeparator)) {
                int idx = entry.replace('\\', '/').lastIndexOf("/repository/");
                if (idx > 0) {
                    return Path.of(entry.substring(0, idx + "/repository".length()));
                }
            }
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /**
     * Walks the pom chain (parents + import-scoped BOMs) breadth-limited by
     * {@link #MAX_POM_CHAIN_DEPTH}, accumulating {@code <dependencyManagement>} entries
     * nearest-declaration-wins and properties child-overrides-parent, then resolves
     * {@code ${…}} version placeholders.
     */
    private Map<String, String> buildManagedMap(Path projectPom, Path repoRoot) {
        Map<String, String> managedRaw = new HashMap<>();   // g:a -> version expression (nearest wins)
        Map<String, String> properties = new HashMap<>();   // child wins
        Set<String> visited = new LinkedHashSet<>();

        Path current = projectPom;
        for (int depth = 0; depth < MAX_POM_CHAIN_DEPTH && current != null; depth++) {
            if (!visited.add(current.toString())) break;
            Document doc = parse(current);
            if (doc == null) break;
            Element project = doc.getDocumentElement();

            String pomVersion = firstChildText(project, "version");
            if (pomVersion == null) pomVersion = childText(project, "parent", "version");
            if (pomVersion != null) properties.putIfAbsent("project.version", pomVersion);

            Element props = firstChild(project, "properties");
            if (props != null) {
                for (Element p : childElements(props)) {
                    properties.putIfAbsent(p.getTagName(), p.getTextContent().trim());
                }
            }

            Element depMgmt = firstChild(project, "dependencyManagement");
            if (depMgmt != null) {
                Element deps = firstChild(depMgmt, "dependencies");
                if (deps != null) {
                    for (Element dep : childElements(deps)) {
                        if (!"dependency".equals(dep.getTagName())) continue;
                        String g = firstChildText(dep, "groupId");
                        String a = firstChildText(dep, "artifactId");
                        String v = firstChildText(dep, "version");
                        if (g == null || a == null || v == null) continue;
                        String scope = firstChildText(dep, "scope");
                        String type = firstChildText(dep, "type");
                        if ("import".equals(scope) && "pom".equals(type)) {
                            // An imported BOM: fold its managed entries in at this level.
                            String resolvedV = interpolate(v, properties);
                            if (resolvedV != null) {
                                foldImportedBom(repoRoot, interpolate(g, properties),
                                        interpolate(a, properties), resolvedV,
                                        managedRaw, properties, visited, depth + 1);
                            }
                            continue;
                        }
                        managedRaw.putIfAbsent(g + ":" + a, v);
                    }
                }
            }

            current = parentPomPath(project, repoRoot, properties);
        }

        Map<String, String> managed = new HashMap<>();
        for (Map.Entry<String, String> e : managedRaw.entrySet()) {
            String v = interpolate(e.getValue(), properties);
            if (v != null) managed.put(e.getKey(), v);
        }
        if (!managed.isEmpty()) {
            log.info("T4-BM: dependency-management chain of {} manages {} artifacts", projectPom, managed.size());
        }
        return Map.copyOf(managed);
    }

    private void foldImportedBom(Path repoRoot, String groupId, String artifactId, String version,
                                 Map<String, String> managedRaw, Map<String, String> properties,
                                 Set<String> visited, int depth) {
        if (depth >= MAX_POM_CHAIN_DEPTH || groupId == null || artifactId == null) return;
        Path bomPom = pomInRepository(repoRoot, groupId, artifactId, version);
        if (bomPom == null || !visited.add(bomPom.toString())) return;
        Document doc = parse(bomPom);
        if (doc == null) return;
        Element project = doc.getDocumentElement();

        Element props = firstChild(project, "properties");
        if (props != null) {
            for (Element p : childElements(props)) {
                properties.putIfAbsent(p.getTagName(), p.getTextContent().trim());
            }
        }
        properties.putIfAbsent("project.version", version);

        Element depMgmt = firstChild(project, "dependencyManagement");
        if (depMgmt == null) return;
        Element deps = firstChild(depMgmt, "dependencies");
        if (deps == null) return;
        for (Element dep : childElements(deps)) {
            if (!"dependency".equals(dep.getTagName())) continue;
            String g = firstChildText(dep, "groupId");
            String a = firstChildText(dep, "artifactId");
            String v = firstChildText(dep, "version");
            if (g == null || a == null || v == null) continue;
            String scope = firstChildText(dep, "scope");
            String type = firstChildText(dep, "type");
            if ("import".equals(scope) && "pom".equals(type)) {
                String resolvedV = interpolate(v, properties);
                if (resolvedV != null) {
                    foldImportedBom(repoRoot, interpolate(g, properties), interpolate(a, properties),
                            resolvedV, managedRaw, properties, visited, depth + 1);
                }
                continue;
            }
            managedRaw.putIfAbsent(g + ":" + a, v);
        }
    }

    private Path parentPomPath(Element project, Path repoRoot, Map<String, String> properties) {
        Element parent = firstChild(project, "parent");
        if (parent == null) return null;
        String g = interpolate(firstChildText(parent, "groupId"), properties);
        String a = interpolate(firstChildText(parent, "artifactId"), properties);
        String v = interpolate(firstChildText(parent, "version"), properties);
        if (g == null || a == null || v == null) return null;
        return pomInRepository(repoRoot, g, a, v);
    }

    private Path pomInRepository(Path repoRoot, String groupId, String artifactId, String version) {
        if (repoRoot == null || groupId == null || artifactId == null || version == null) return null;
        Path pom = repoRoot.resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".pom");
        if (!Files.isRegularFile(pom)) {
            log.debug("T4-BM: chain pom not in the local repository: {}", pom);
            return null;
        }
        return pom;
    }

    /** Resolves {@code ${property}} indirection (possibly chained) against the accumulated
     *  properties; null when a step is unresolvable — the entry is then unusable, skip it. */
    private static String interpolate(String value, Map<String, String> properties) {
        String v = value;
        for (int i = 0; v != null && i < 5; i++) {
            if (!v.startsWith("${") || !v.endsWith("}")) return v.isBlank() ? null : v;
            v = properties.get(v.substring(2, v.length() - 1));
        }
        return null;
    }

    private static Document parse(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(pom.toFile());
        } catch (Exception e) {
            log.debug("T4-BM: unparseable pom {} — {}", pom, e.getMessage());
            return null;
        }
    }

    private static Element firstChild(Element parent, String tagName) {
        for (Element e : childElements(parent)) {
            if (tagName.equals(e.getTagName())) return e;
        }
        return null;
    }

    private static String firstChildText(Element parent, String tagName) {
        Element child = firstChild(parent, tagName);
        if (child == null) return null;
        String text = child.getTextContent();
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    private static String childText(Element parent, String childTag, String grandChildTag) {
        Element child = firstChild(parent, childTag);
        return child == null ? null : firstChildText(child, grandChildTag);
    }

    private static Iterable<Element> childElements(Element parent) {
        NodeList nodes = parent.getChildNodes();
        java.util.List<Element> out = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e) out.add(e);
        }
        return out;
    }

    /** Test-only — clear the cache so a fresh chain walk happens on next call. */
    void __resetCacheForTest() {
        cache.set(null);
    }
}
