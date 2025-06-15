package com.walpro.autobuilder.diff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.service.FileService;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiffService {

    private final FileService fileService;

    public DiffReport compareFolders(String oldDir, String newDir) throws IOException {
        Map<String, String> oldFiles = loadFiles(oldDir);
        Map<String, String> newFiles = loadFiles(newDir);

        log.info("🔬 Comparing: {} -> {}", oldDir, newDir);
        log.info("📄 Old files: {}", oldFiles.keySet());
        log.info("📄 New files: {}", newFiles.keySet());

        return compareInMemory(oldFiles, newFiles);
    }

    public DiffReport compareInMemory(Map<String, String> oldFiles, Map<String, String> newFiles) {
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(oldFiles.keySet());
        allKeys.addAll(newFiles.keySet());

        List<DiffEntry> changes = new ArrayList<>();

        for (String key : allKeys) {
            String oldContent = oldFiles.get(key);
            String newContent = newFiles.get(key);

            if (oldContent == null) {
                log.debug("Added: {}", key);
                changes.add(new DiffEntry(DiffType.ADDED, key, null, newContent));
            } else if (newContent == null) {
                log.debug("Deleted: {}", key);
                changes.add(new DiffEntry(DiffType.DELETED, key, oldContent, null));
            } else if (!oldContent.equals(newContent)) {
                log.debug("Modified: {}", key);
                changes.add(new DiffEntry(DiffType.MODIFIED, key, oldContent, newContent));
            }
        }

        return new DiffReport(changes);
    }

    private Map<String, String> loadFiles(String folder) throws IOException {
        Map<String, String> fileMap = new HashMap<>();
        for (var info : fileService.listFiles(folder)) {
            String path = info.getName();
            String content = fileService.readFile(folder + "/" + path);
            fileMap.put(path, content);
        }
        return fileMap;
    }
} 
