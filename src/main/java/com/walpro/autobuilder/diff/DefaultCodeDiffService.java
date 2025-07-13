package com.walpro.autobuilder.diff;

import com.walpro.autobuilder.service.FileService;
import com.walpro.autobuilder.tools.diff.ChangedMethod;
import com.walpro.autobuilder.tools.diff.ClassChangeAnalyzer;
import com.walpro.autobuilder.tools.diff.MethodChangeAnalyzer;
import com.walpro.autobuilder.tools.tracker.MethodHashTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class DefaultCodeDiffService implements CodeDiffService {

    private final FileService fileService;

    @Override
    public List<ChangedMethod> diffFile(String currentFile, String currentRelativeCyclePath, String previousRelativeCyclePath, MethodHashTracker tracker) {
        try {
            String newSource = fileService.readFile(currentRelativeCyclePath + "/" + currentFile);
            String oldSource = null;
            try {
            	oldSource = fileService.readFile(previousRelativeCyclePath + "/" + currentFile);
            } catch (IOException e) {
                return fallbackAllMethodsAsAdded(currentRelativeCyclePath + "/" + currentFile, newSource, tracker);
            }
            
            if (currentFile.endsWith("java")) {
	
	            List<ChangedMethod> bodyDiffs = MethodChangeAnalyzer.compareMethodBodies(
	                    currentFile.toString(), oldSource, newSource, tracker
	            );
	
	            var sigDiff = ClassChangeAnalyzer.compareMethods(oldSource, newSource);
	            for (var removed : sigDiff.getRemoved()) {
	                boolean alreadyIncluded = bodyDiffs.stream()
	                        .anyMatch(m -> m.name().equals(removed.name()) && m.type() == ChangedMethod.ChangeType.REMOVED);
	                if (!alreadyIncluded) {
	                    bodyDiffs.add(removed);
	                }
	            }
	            
	            return bodyDiffs;
            } else {
            	// TODO: Add comparisons for other file types here (MW; 2025-JUL-14)
            }
            
            return Collections.emptyList();

        } catch (Exception e) {
            log.warn("⚠️ Diff failed for file: " + currentFile + " → " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private Path extractRelativePath(Path fullPath) {
        int srcIndex = -1;
        for (int i = 0; i < fullPath.getNameCount(); i++) {
            if (fullPath.getName(i).toString().equals("src") &&
                i + 2 < fullPath.getNameCount() &&
                fullPath.getName(i + 1).toString().equals("main") &&
                fullPath.getName(i + 2).toString().equals("java")) {
                srcIndex = i + 3;
                break;
            }
        }
        return srcIndex > 0
                ? fullPath.subpath(srcIndex, fullPath.getNameCount())
                : fullPath.getFileName();
    }

    private List<ChangedMethod> fallbackAllMethodsAsAdded(String currentFile, String newSource, MethodHashTracker tracker) {
        return MethodChangeAnalyzer.compareMethodBodies(currentFile, null, newSource, tracker);
    }
}
