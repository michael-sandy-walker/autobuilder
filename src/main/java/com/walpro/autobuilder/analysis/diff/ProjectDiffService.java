package com.walpro.autobuilder.analysis.diff;

import com.walpro.autobuilder.analysis.diff.builder.StructuralJavaDiffBuilder;
import com.walpro.autobuilder.analysis.diff.model.ElementDescriptor;
import com.walpro.autobuilder.analysis.diff.model.ElementType;
import com.walpro.autobuilder.analysis.diff.model.StructuralDiffNode;
import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectDiffService {

    private final FileService fileService;
    private final DiffService diffService;

    public ProjectDiffResult computeDiff(Path currentPath, Path previousPath) {
        return computeDiff(currentPath, previousPath, Set.of());
    }

    /**
     * @param explicitDeletions the paths the mutation asked to delete. The mutation directory
     *                          holds only what was written, so a deletion has no footprint in the
     *                          folder comparison and must be told to the diff; without it the
     *                          delete directive was parsed, logged and then dropped — absent from
     *                          the diff, the probe tree, the heuristics' view and the merge.
     */
    public ProjectDiffResult computeDiff(Path currentPath, Path previousPath, Set<String> explicitDeletions) {
        try {
            String currentPathStr = currentPath.toString();
            String previousPathStr = previousPath != null && fileService.existsResolved(previousPath)
                    ? previousPath.toString()
                    : null;

            Set<String> asked = explicitDeletions == null ? Set.of() : explicitDeletions;
            DiffReport diffReport = diffService.compareFolders(
                    previousPathStr,
                    currentPathStr,
                    asked.isEmpty() ? null : asked
            );

            Map<String, String> changedFiles = new HashMap<>();
            Set<String> deletedPaths = new HashSet<>();
            List<StructuralDiffNode> topLevelDiffs = new ArrayList<>();

            for (DiffEntry entry : diffReport.entries()) {
                String relPath = entry.path();
                switch (entry.type()) {
                    case MODIFIED, ADDED -> {
                        try {
                            if (relPath.endsWith("meta.json")) {
                                // skip metadata files from diff
                                continue;
                            }
                            String newContent = fileService.readFile(currentPathStr + "/" + relPath);
                            String oldContent = previousPathStr != null && fileService.exists(previousPathStr + "/" + relPath)
                                    ? fileService.readFile(previousPathStr + "/" + relPath)
                                    : null;

                            changedFiles.put(relPath, newContent);

                            if (relPath.endsWith(".java")) {
                                try {
                                    StructuralDiffNode javaDiff =
                                            StructuralJavaDiffBuilder.diffJavaFile(relPath, oldContent, newContent);
                                    topLevelDiffs.add(javaDiff);
                                } catch (IllegalArgumentException e) {
                                    log.warn("Could not parse Java source for structural diff (falling back to file-level): {}", relPath);
                                    topLevelDiffs.add(new StructuralDiffNode(
                                            oldContent != null ? new ElementDescriptor(relPath, ElementType.FILE, oldContent, Map.of()) : null,
                                            new ElementDescriptor(relPath, ElementType.FILE, newContent, Map.of()),
                                            List.of()
                                    ));
                                }
                            } else {
                                // Non-Java: create a basic file-level diff node
                                topLevelDiffs.add(new StructuralDiffNode(
                                        oldContent != null ? new ElementDescriptor(relPath, ElementType.FILE, oldContent, Map.of()) : null,
                                        new ElementDescriptor(relPath, ElementType.FILE, newContent, Map.of()),
                                        List.of()
                                ));
                            }

                        } catch (IOException e) {
                            log.warn("Could not diff file: {}", relPath);
                        }
                    }

                    case DELETED -> {
                        deletedPaths.add(relPath);
                        String oldContent = previousPathStr != null && fileService.exists(previousPathStr + "/" + relPath)
                                ? fileService.readFile(previousPathStr + "/" + relPath)
                                : null;

                        topLevelDiffs.add(new StructuralDiffNode(
                                new ElementDescriptor(relPath, ElementType.FILE, oldContent, Map.of()),
                                null,
                                List.of()
                        ));
                    }
                }
            }

            // A deletion the mutation asked for is a deletion whether or not the previous
            // mutation directory happened to hold the file (it usually does not: the file was
            // merged in an earlier cycle and lives in output/, not in mutations/<previous>/).
            for (String path : asked) {
                if (path != null && !path.isBlank()) deletedPaths.add(path.replace('\\', '/'));
            }

            // Create a synthetic root node for the full project
            StructuralDiffNode rootNode = new StructuralDiffNode(null, null, topLevelDiffs);

            return new ProjectDiffResult(rootNode, changedFiles, deletedPaths);

        } catch (Exception e) {
            log.error("Failed to compute project diff", e);
            return new ProjectDiffResult(new StructuralDiffNode(null, null, List.of()), Map.of(), Set.of());
        }
    }

}