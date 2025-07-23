package com.walpro.autobuilder.diff;

import com.walpro.autobuilder.diff.builder.StructuralJavaDiffBuilder;
import com.walpro.autobuilder.diff.model.ElementDescriptor;
import com.walpro.autobuilder.diff.model.ElementType;
import com.walpro.autobuilder.diff.model.StructuralDiffNode;
import com.walpro.autobuilder.service.FileService;
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
//    private final MethodHashTracker methodHashTracker;
    private final DiffService diffService;

    public ProjectDiffResult computeDiff(Path currentPath, Path previousPath) {
        try {
            String currentPathStr = currentPath.toString();
            String previousPathStr = previousPath != null && fileService.existsResolved(previousPath)
                    ? previousPath.toString()
                    : null;

            DiffReport diffReport = diffService.compareFolders(
                    previousPathStr,
                    currentPathStr,
                    null
            );

            Map<String, String> changedFiles = new HashMap<>();
            Set<String> deletedPaths = new HashSet<>();
            List<StructuralDiffNode> topLevelDiffs = new ArrayList<>();

            for (DiffEntry entry : diffReport.entries()) {
                String relPath = entry.path();
                switch (entry.type()) {
                    case MODIFIED, ADDED -> {
                        try {
                            String newContent = fileService.readFile(currentPathStr + "/" + relPath);
                            String oldContent = previousPathStr != null && fileService.exists(previousPathStr + "/" + relPath)
                                    ? fileService.readFile(previousPathStr + "/" + relPath)
                                    : null;

                            changedFiles.put(relPath, newContent);

                            if (relPath.endsWith(".java")) {
                                StructuralDiffNode javaDiff =
                                        StructuralJavaDiffBuilder.diffJavaFile(relPath, oldContent, newContent);
                                topLevelDiffs.add(javaDiff);
                            } else {
                                // Non-Java: create a basic file-level diff node
                                topLevelDiffs.add(new StructuralDiffNode(
                                        oldContent != null ? new ElementDescriptor(relPath, ElementType.FILE, oldContent, Map.of()) : null,
                                        new ElementDescriptor(relPath, ElementType.FILE, newContent, Map.of()),
                                        List.of()
                                ));
                            }

                        } catch (IOException e) {
                            log.warn("⚠️ Could not diff file: {}", relPath);
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

            // Create a synthetic root node for the full project
            StructuralDiffNode rootNode = new StructuralDiffNode(null, null, topLevelDiffs);

            return new ProjectDiffResult(rootNode, changedFiles, deletedPaths);

        } catch (Exception e) {
            log.error("❌ Failed to compute project diff", e);
            return new ProjectDiffResult(new StructuralDiffNode(null, null, List.of()), Map.of(), Set.of());
        }
    }

}