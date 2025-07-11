package com.walpro.autobuilder.diff;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.config.DiffConfig;
import com.walpro.autobuilder.service.FileService;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiffService {

    private final FileService fileService;
    private final DiffConfig diffConfig;

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
                    log.debug("Explicitly deleted by LLM: {}", key);
                    changes.add(new DiffEntry(DiffType.DELETED, key, oldContent, null));
                }
                continue; // skip any further checks
            }

            if (oldContent == null) {
                log.debug("Added: {}", key);
                changes.add(new DiffEntry(DiffType.ADDED, key, null, newContent));
            } else if (!oldContent.equals(newContent)) {
                log.debug("Modified: {}", key);
                changes.add(new DiffEntry(DiffType.MODIFIED, key, oldContent, newContent));
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
}
