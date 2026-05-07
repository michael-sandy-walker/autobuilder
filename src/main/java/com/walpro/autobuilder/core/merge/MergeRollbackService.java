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
        for (Map.Entry<String, String> entry : point.originalContents().entrySet()) {
            String relPath = outputBasePath + "/" + entry.getKey();
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
        log.info("T1-DW: rolled back {} paths — restored to pre-merge state", point.originalContents().size());
    }
}
