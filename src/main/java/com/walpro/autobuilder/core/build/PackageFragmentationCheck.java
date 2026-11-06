package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T4-BH: the single home for the "no two on-disk {@code .java} files declare the same top-level
 * simple name under different packages" invariant. A type that exists under two packages compiles
 * clean but breaks runtime dispatch (instanceof, exception → HTTP status mapping, Spring bean
 * resolution by type), so we need to catch the collision <i>before</i> any file lands in
 * {@code output/}.
 *
 * <p>Used from {@link CompileProbeService#probe} (the single write-time gate — covers the normal
 * evolution path and the deferred-mutation re-stage path transitively) and from
 * {@link com.walpro.autobuilder.agent.merge.MergeAgent} as defence-in-depth for code paths that
 * bypass the probe (probe disabled, future writers).
 *
 * <p>Idempotent and side-effect-free — does no writes, takes the proposed file map and a base
 * path, and returns the first conflict found.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackageFragmentationCheck {

    private final FileService fileService;

    /**
     * Scans {@code proposedFiles} against the existing tree under {@code outputPath} for a
     * same-simple-name-different-directory collision. Returns the first {@link Conflict} found,
     * or {@link Optional#empty()} when the proposed write set is fragmentation-safe.
     *
     * @param proposedFiles map of relative-path → content the caller is about to write
     * @param outputPath    target project root relative to {@code file.base-path}
     */
    public Optional<Conflict> check(Map<String, String> proposedFiles, String outputPath) {
        List<Conflict> all = findAll(proposedFiles, outputPath);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Like {@link #check} but returns one {@link Conflict} per proposed file that collides
     * (first existing match per candidate). Used by {@link PackageConsolidationService} to
     * resolve every fragmenting write in a mutation set, not just the first.
     */
    public List<Conflict> findAll(Map<String, String> proposedFiles, String outputPath) {
        return findAll(proposedFiles, outputPath, List.of());
    }

    /**
     * T4-EH: as {@link #check}, for a write set that also deletes files. A path the same set
     * deletes is not a place the type "already lives" -- writing it elsewhere and deleting it
     * here is a move, and the tree the set describes holds the type once.
     */
    public Optional<Conflict> check(Map<String, String> proposedFiles, String outputPath,
                                    Collection<String> deletedFiles) {
        List<Conflict> all = findAll(proposedFiles, outputPath, deletedFiles);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** As {@link #findAll(Map, String)}, with the set's own deletions taken out of the tree first. */
    public List<Conflict> findAll(Map<String, String> proposedFiles, String outputPath,
                                  Collection<String> deletedFiles) {
        if (proposedFiles == null || proposedFiles.isEmpty()) return List.of();

        List<String> existing;
        try {
            existing = fileService.listAllRelativeFilePathsUnder(outputPath);
        } catch (IOException e) {
            log.warn("T4-BH: could not scan output tree for fragmentation check: {}", e.getMessage());
            return List.of();
        }
        if (deletedFiles != null && !deletedFiles.isEmpty()) {
            existing = existing.stream().filter(p -> !deletedFiles.contains(p)).toList();
        }
        return findAllAgainst(proposedFiles, existing);
    }

    /**
     * T2-EZ: same invariant against an explicitly supplied tree listing rather than the tree as it
     * is on disk right now. The caller that needs this is
     * {@link CompileProbeService}'s decision about which injector-created files to keep through a
     * failed probe: the files are all still written at that moment, so a disk scan would compare
     * the candidates against the very state that is about to be undone. Passing the listing the
     * tree will have <i>after</i> the rollback is the only way to ask the question truthfully, and
     * it keeps the rule itself in this one home.
     */
    public List<Conflict> findAllAgainst(Map<String, String> proposedFiles, Collection<String> existing) {
        if (proposedFiles == null || proposedFiles.isEmpty()) return List.of();
        if (existing == null || existing.isEmpty()) return List.of();

        List<Conflict> conflicts = new ArrayList<>();
        for (String candidatePath : proposedFiles.keySet()) {
            if (candidatePath == null || !candidatePath.endsWith(".java")) continue;
            String simpleName = typeNameOf(candidatePath);
            String candidateParent = parentDirOf(candidatePath);

            for (String p : existing) {
                if (p == null || !p.endsWith(".java")) continue;
                if (p.equals(candidatePath)) continue;
                if (!simpleName.equals(typeNameOf(p))) continue;
                if (!candidateParent.equals(parentDirOf(p))) {
                    conflicts.add(new Conflict(simpleName, candidatePath, p));
                    break;
                }
            }
        }
        return conflicts;
    }

    private static String typeNameOf(String path) {
        String base = basenameOf(path);
        return base.endsWith(".java") ? base.substring(0, base.length() - ".java".length()) : base;
    }

    private static String basenameOf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String parentDirOf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    /**
     * @param simpleName    the top-level type name shared by two files in different directories
     * @param candidatePath the proposed file path (relative to {@code outputPath})
     * @param existingPath  the already-on-disk file path that would collide (relative to {@code outputPath})
     */
    public record Conflict(String simpleName, String candidatePath, String existingPath) {}
}
