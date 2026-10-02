package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * T1-GF: the machine's own artifact repository, asked the questions the remote index answers
 * when that index cannot — no network, a rate limit, a timeout, or no hit.
 *
 * <p>Two questions, both deterministic and offline:
 * <ul>
 *   <li><b>Which artifact provides this class?</b> The same convention the remote lookup uses —
 *       the artifact's group is a segment-aligned prefix of the class's package, longest first —
 *       answered by opening the locally present jars of those groups.</li>
 *   <li><b>Which versions are present, newest first?</b> Ordered by the leading dotted-number
 *       part of each version, compared numerically, with a qualified version ranked just below
 *       the plain release of the same numbers. Not by build time: checked on this machine, every
 *       reproducibly built jar carries the same fixed 1980-02-01 entry timestamp, and a file's
 *       modification time is only when it was downloaded — neither orders releases. The remote
 *       index states publish times; a local repository states nothing, so the version string is
 *       the only fact left.</li>
 * </ul>
 *
 * <p>A locally present version is also the one version guaranteed to resolve without a network,
 * which is what makes it the right fallback rather than merely an available one.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "memo of the local artifact repository's contents")
public class LocalArtifactRepository {

    @Value("${autobuilder.build.discovery.local-repository:${user.home}/.m2/repository}")
    @Setter
    private String root = System.getProperty("user.home") + "/.m2/repository";

    // Whether a missing dependency is looked up in the local Maven repository.
    @Value("${autobuilder.build.discovery.local-enabled:true}")
    @Setter
    private boolean enabled = true;

    private final Map<String, List<String>> versionCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<CoordinateSearchClient.Coordinates>> classCache = new ConcurrentHashMap<>();

    /**
     * The locally present artifact providing {@code fqn}, with the most suitable locally present
     * version ({@link VersionSuitability}, aligned to {@code alignedVersion} when that is present).
     */
    public Optional<CoordinateSearchClient.Coordinates> findByFullyQualifiedName(String fqn, String alignedVersion) {
        if (!enabled || fqn == null || !fqn.contains(".")) return Optional.empty();
        Optional<CoordinateSearchClient.Coordinates> located = classCache.computeIfAbsent(fqn, this::locate);
        return located.map(c -> new CoordinateSearchClient.Coordinates(c.groupId(), c.artifactId(),
                VersionSuitability.choose(versionsOf(c.groupId(), c.artifactId()), alignedVersion)
                        .orElse(c.version())));
    }

    /** Locally present versions of {@code groupId:artifactId}, newest first ({@link #NEWEST_FIRST}). */
    public List<String> versionsOf(String groupId, String artifactId) {
        if (!enabled || groupId == null || artifactId == null) return List.of();
        return versionCache.computeIfAbsent(groupId + ":" + artifactId, k -> scanVersions(groupId, artifactId));
    }

    private Optional<CoordinateSearchClient.Coordinates> locate(String fqn) {
        String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
        String entry = fqn.replace('.', '/') + ".class";
        for (String group : CoordinateSearchClient.groupCandidates(pkg)) {
            Path groupDir = Path.of(root).resolve(group.replace('.', File.separatorChar));
            if (!Files.isDirectory(groupDir)) continue;
            List<Path> children;
            try (Stream<Path> s = Files.list(groupDir)) {
                children = s.filter(Files::isDirectory).sorted().toList();
            } catch (IOException e) {
                continue;
            }
            for (Path artifactDir : children) {
                String artifact = artifactDir.getFileName().toString();
                List<String> versions = versionsOf(group, artifact);
                if (versions.isEmpty()) continue; // a sub-group directory, not an artifact
                if (jarContains(jarOf(group, artifact, versions.get(0)), entry)) {
                    log.info("T1-GF: local repository provides {} in {}:{} ({} version(s) present)",
                            fqn, group, artifact, versions.size());
                    return Optional.of(new CoordinateSearchClient.Coordinates(group, artifact, versions.get(0)));
                }
            }
        }
        log.info("T1-GF: local repository has no artifact whose group prefixes the package of {}", fqn);
        return Optional.empty();
    }

    private List<String> scanVersions(String groupId, String artifactId) {
        Path dir = Path.of(root).resolve(groupId.replace('.', File.separatorChar)).resolve(artifactId);
        if (!Files.isDirectory(dir)) return List.of();
        List<String> present = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path versionDir : s.filter(Files::isDirectory).toList()) {
                String v = versionDir.getFileName().toString();
                if (Files.isRegularFile(jarOf(groupId, artifactId, v))) present.add(v);
            }
        } catch (IOException e) {
            return List.of();
        }
        present.sort(NEWEST_FIRST);
        return present;
    }

    /** Leading dotted numbers compared numerically; a qualified version just below its plain twin. */
    static final Comparator<String> NEWEST_FIRST = (a, b) -> {
        long[] na = leadingNumbers(a);
        long[] nb = leadingNumbers(b);
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            long x = i < na.length ? na[i] : 0;
            long y = i < nb.length ? nb[i] : 0;
            if (x != y) return Long.compare(y, x);
        }
        boolean qa = !a.matches("\\d+(\\.\\d+)*");
        boolean qb = !b.matches("\\d+(\\.\\d+)*");
        if (qa != qb) return qa ? 1 : -1;
        return b.compareTo(a);
    };

    private static long[] leadingNumbers(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(v);
        if (!m.find()) return new long[0];
        String[] parts = m.group(1).split("\\.");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Long.parseLong(parts[i]); } catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    private Path jarOf(String groupId, String artifactId, String version) {
        return Path.of(root).resolve(groupId.replace('.', File.separatorChar)).resolve(artifactId)
                .resolve(version).resolve(artifactId + "-" + version + ".jar");
    }

    private static boolean jarContains(Path jar, String entry) {
        if (!Files.isRegularFile(jar)) return false;
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getJarEntry(entry) != null;
        } catch (IOException e) {
            return false;
        }
    }
}
