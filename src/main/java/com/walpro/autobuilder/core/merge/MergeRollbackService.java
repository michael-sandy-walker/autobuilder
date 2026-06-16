package com.walpro.autobuilder.core.merge;

import com.walpro.autobuilder.core.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * T1-DW: captures pre-merge filesystem state and restores it when a post-merge build gate
 * detects that the mutation broke compilation.
 */
@Slf4j
@Component
public class MergeRollbackService {

    public MergeRollbackPoint snapshot(String cycleId, Map<String, String> capturedOriginals, Set<String> newlyAddedPaths) {
        return new MergeRollbackPoint(cycleId, new LinkedHashMap<>(capturedOriginals), new LinkedHashSet<>(newlyAddedPaths));
    }

    public void rollback(MergeRollbackPoint point, String outputBasePath, FileService fileService) {
        rollback(point, outputBasePath, fileService, Set.of());
    }

    /**
     * T4-AQ-rollback-fix: roll back a staged mutation but PRESERVE {@code preservePaths}. Used when
     * a mutation compiled clean and was rolled back because <i>evaluation</i> rejected it (not a
     * compile failure): the compile-fix injector's deterministic infrastructure (a bootstrapped
     * {@code pom.xml}, skeleton classes) made the build green and is not the evaluated LLM work, so
     * deleting it just forces a re-bootstrap that the T2-DY ledger then blacklists — stranding the
     * project with no pom (observed 2026-06-16: the bootstrapped pom was rolled back with a
     * coherence-rejected mutation, bootstrap-pom blacklisted, run wedged on "no POM" for 25 min).
     * On a genuine compile failure the caller still uses the no-preserve overload (the fixes did not
     * achieve a green build, so they go too).
     */
    public void rollback(MergeRollbackPoint point, String outputBasePath, FileService fileService,
                         Set<String> preservePaths) {
        int preserved = 0;
        for (Map.Entry<String, String> entry : point.originalContents().entrySet()) {
            String relPath = outputBasePath + "/" + entry.getKey();
            if (preservePaths.contains(entry.getKey())) {
                preserved++;
                log.debug("T1-DW rollback: preserving injector-created {}", entry.getKey());
                continue;
            }
            if (point.newlyAddedPaths().contains(entry.getKey())) {
                try {
                    fileService.deleteFile(relPath);
                    log.debug("T1-DW rollback: deleted newly-added {}", entry.getKey());
                } catch (IOException e) {
                    log.warn("T1-DW rollback: failed to delete {}: {}", entry.getKey(), e.getMessage());
                }
            } else {
                try {
                    Path p = fileService.resolveSecurePath(relPath);
                    fileService.writeFileResolved(p, entry.getValue());
                    log.debug("T1-DW rollback: restored {}", entry.getKey());
                } catch (IOException e) {
                    log.warn("T1-DW rollback: failed to restore {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }
        log.info("T1-DW: rolled back {} paths — restored to pre-merge state{}",
                point.originalContents().size() - preserved,
                preserved > 0 ? " (preserved " + preserved + " injector-created file(s))" : "");
    }
}
