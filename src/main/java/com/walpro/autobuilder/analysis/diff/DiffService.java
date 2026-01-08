package com.walpro.autobuilder.analysis.diff;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.config.DiffConfigProperties;
import com.walpro.autobuilder.core.service.FileService;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiffService {

    private final FileService fileService;
    private final DiffConfigProperties diffConfig;

    public DiffReport compareFolders(String oldDir, String newDir, Set<String> deletedFiles) throws IOException {
        if (newDir == null) {
            throw new IllegalArgumentException("Target folder (newDir) must be provided");
        }

        Map<String, String> oldFiles = (oldDir != null) ? loadFiles(oldDir) : Map.of();
        Map<String, String> newFiles = loadFiles(newDir);

        log.info("🔬 Comparing: {} -> {}", oldDir != null ? oldDir : "[empty]", newDir);
        log.info("📄 Old files: {}", oldFiles.keySet());
        log.info("📄 New files: {}", newFiles.keySet());

        return compareInMemory(oldFiles, newFiles, deletedFiles != null ? deletedFiles : Set.of());
    }

    public DiffReport compareInMemory(Map<String, String> oldFiles, Map<String, String> newFiles, Set<String> deletedFiles) {
        Map<String, String> normalizedOld = normalizeMapKeys(oldFiles);
        Map<String, String> normalizedNew = normalizeMapKeys(newFiles);

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(normalizedOld.keySet());
        allKeys.addAll(normalizedNew.keySet());
        allKeys.addAll(deletedFiles);

        List<DiffEntry> changes = new ArrayList<>();

        for (String key : allKeys) {
            String oldContent = normalizedOld.get(key);
            String newContent = normalizedNew.get(key);
            
            if (deletedFiles.contains(key)) {
                if (oldContent != null) {
                	int linesDeleted = countLines(oldContent);
                    log.debug("Explicitly deleted by LLM: {}", key);
                    changes.add(new DiffEntry(DiffType.DELETED, key, oldContent, null, 0, linesDeleted));
                }
                continue; // skip any further checks
            }

            if (oldContent == null) {
                int linesAdded = countLines(newContent);
                log.debug("Added: {}", key);
                changes.add(new DiffEntry(DiffType.ADDED, key, null, newContent, linesAdded, 0));
            } else if (newContent == null) {
            	// do nothing here, because the file is omitted
            } else if (!oldContent.equals(newContent)) {
                int[] diffLines = computeLineDiff(oldContent, newContent);
                int linesAdded = diffLines[0];
                int linesDeleted = diffLines[1];
                
                log.debug("Modified: {}", key);
                changes.add(new DiffEntry(DiffType.MODIFIED, key, oldContent, newContent, linesAdded, linesDeleted));
            }
        }

        return new DiffReport(changes);
    }

    private Map<String, String> loadFiles(String folder) throws IOException {
        if (folder == null) return Map.of(); // defensive, shouldn't be needed anymore

        Map<String, String> fileMap = new HashMap<>();
        for (var info : fileService.listFiles(folder)) {
            String rawPath = info.getName();
            String normalized = rawPath.replace("\\", "/");

            if (diffConfig.getExcludeFolders().stream().anyMatch(normalized::startsWith)) {
                log.debug("Skipping excluded folder: {}", normalized);
                continue;
            } else if (diffConfig.getExcludeFiles().stream().anyMatch(normalized::endsWith)) {
                log.debug("Skipping excluded file: {}", normalized);
                continue;
            }


            String content = fileService.readFile(folder + "/" + rawPath);
            fileMap.put(rawPath, content);
        }
        return fileMap;
    }


    private Map<String, String> normalizeMapKeys(Map<String, String> original) {
        return original.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().replace("\\", "/"),
                        Map.Entry::getValue,
                        (v1, v2) -> v1 // handle duplicates if any
                ));
    }
    
    private int countLines(String content) {
        if (content == null || content.isEmpty()) return 0;
        return (int) content.lines().count();
    }

    private int[] computeLineDiff(String oldContent, String newContent) {
    	List<String> oldLines = oldContent.lines().toList();
    	List<String> newLines = null;
    	
    	if (newContent == null) {
    		newLines = "".lines().toList();
    	} else {
    		newLines = newContent.lines().toList();
    	}

        Set<String> oldLineSet = new HashSet<>(oldLines);
        Set<String> newLineSet = new HashSet<>(newLines);

        Set<String> addedLines = new HashSet<>(newLineSet);
        addedLines.removeAll(oldLineSet);

        Set<String> deletedLines = new HashSet<>(oldLineSet);
        deletedLines.removeAll(newLineSet);

        return new int[]{addedLines.size(), deletedLines.size()};
    }

}
