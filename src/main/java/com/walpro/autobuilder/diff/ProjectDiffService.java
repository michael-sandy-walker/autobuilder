package com.walpro.autobuilder.diff;

import com.walpro.autobuilder.service.FileService;
import com.walpro.autobuilder.tools.diff.ChangedMethod;
import com.walpro.autobuilder.tools.diff.ClassChangeAnalyzer;
import com.walpro.autobuilder.tools.diff.MethodChangeAnalyzer;
import com.walpro.autobuilder.tools.tracker.MethodHashTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectDiffService {

    private final FileService fileService;
    private final MethodHashTracker methodHashTracker;
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
            List<ChangedMethod> changedMethods = new ArrayList<>();
            Set<String> deletedPaths = new HashSet<>();

            for (DiffEntry entry : diffReport.entries()) {
                String relPath = entry.path();
                switch (entry.type()) {
                    case MODIFIED, ADDED -> {
                        try {
                            String newContent = fileService.readFile(currentPathStr + "/" + relPath);
                            changedFiles.put(relPath, newContent);

                            if (relPath.endsWith(".java")) {
                                List<ChangedMethod> methodChanges =
                                        MethodChangeAnalyzer.compareMethodBodies(relPath,
                                                previousPathStr != null ? fileService.readFile(previousPathStr + "/" + relPath) : null,
                                                newContent,
                                                methodHashTracker);
                                List<ChangedMethod> sigChanges =
                                        ClassChangeAnalyzer.compareMethods(
                                                previousPathStr != null ? fileService.readFile(previousPathStr + "/" + relPath) : null,
                                                newContent
                                        ).getRemoved(); // only removed for now

                                methodChanges.addAll(sigChanges.stream()
                                        .filter(m -> methodChanges.stream().noneMatch(existing ->
                                                existing.name().equals(m.name()) && existing.type() == ChangedMethod.ChangeType.REMOVED))
                                        .collect(Collectors.toList()));

                                changedMethods.addAll(methodChanges);
                            }

                        } catch (IOException e) {
                            log.warn("⚠️ Could not diff file: {}", relPath);
                        }
                    }

                    case DELETED -> {
                    	deletedPaths.add(relPath);
                    }
                }
            }

            return new ProjectDiffResult(diffReport, changedMethods, changedFiles, deletedPaths);

        } catch (Exception e) {
            log.error("❌ Failed to compute project diff", e);
            return new ProjectDiffResult(new DiffReport(List.of()), List.of(), Map.of(), Set.of());
        }
    }
}