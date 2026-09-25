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

        log.info("Comparing: {} -> {}", oldDir != null ? oldDir : "[empty]", newDir);
        log.info("Old files: {}", oldFiles.keySet());
        log.info("New files: {}", newFiles.keySet());

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
        int unreadable = 0;
        String firstUnreadable = null;
        var files = fileService.listFiles(folder);
        Set<String> moduleRoots = moduleRoots(files.stream()
                .map(info -> info.getName().replace("\\", "/")).toList());
        for (var info : files) {
            String rawPath = info.getName();
            String normalized = rawPath.replace("\\", "/");

            if (isExcluded(normalized, moduleRoots)) {
                log.debug("Skipping excluded path: {}", normalized);
                continue;
            } else if (diffConfig.getExcludeFiles().stream().anyMatch(normalized::endsWith)) {
                log.debug("Skipping excluded file: {}", normalized);
                continue;
            }

            try {
                fileMap.put(rawPath, fileService.readFile(folder + "/" + rawPath));
            } catch (java.nio.charset.MalformedInputException e) {
                // A file that is not text is not a change to the project's source, and one of them
                // must never cost the whole diff. Until 2026-09-05 it did: the first imported
                // project carried a Maven wrapper jar, reading it threw, computeDiff failed on
                // EVERY cycle, and with no diff nothing was scored or merged. Eighteen cycles,
                // sixteen proposals, zero of them judged.
                unreadable++;
                if (firstUnreadable == null) firstUnreadable = normalized;
            }
        }
        if (unreadable > 0) {
            // Counted and named, not silent: a diff that quietly ignores part of a tree is how a
            // change goes unnoticed, which is the failure this whole service exists to prevent.
            log.info("Diff skipped {} file(s) that are not text (first: {}) — they cannot hold a "
                    + "source change and are not part of the comparison", unreadable, firstUnreadable);
        }
        return fileMap;
    }

    /**
     * Whether a path lies under an excluded folder.
     *
     * <p>This compared prefixes until 2026-09-05, which works only for a tree the loop wrote
     * itself, where every excluded folder sits at the root. An imported project puts them
     * anywhere: {@code .mvn/wrapper/} holds a binary, and a module of a multi-module project has
     * its own build output beside its own descriptor.
     *
     * <p><b>But matching the name at ANY depth hid source code.</b> Run 2026-09-26_00-16-35 (the
     * criterion-status change target, T4-CF) adopted a module whose one class to change lives in
     * a package named {@code build}. Every file under {@code .../core/build/} was dropped from
     * both sides of the diff, so the model's correct implementation read as no change at all:
     * cycle 2 was skipped as an empty diff, and the evaluator of cycles 3 and 4 answered "no code
     * changes provided". A build-output folder is a place in the PROJECT's layout, not a word, so
     * a name matches only where a build writes its output:
     * <ul>
     *   <li>at the root of the tree;</li>
     *   <li>directly under a module root -- a directory that holds a file of the same name as
     *       one the root holds, the way a nested project repeats its root's descriptor;</li>
     *   <li>at any depth when the name is hidden (dot-prefixed): tool state, never a source
     *       folder.</li>
     * </ul>
     */
    private boolean isExcluded(String normalizedPath, Set<String> moduleRoots) {
        String[] segments = normalizedPath.split("/");
        String parent = "";
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            if (diffConfig.getExcludeFolders().contains(segment)
                    && (segment.startsWith(".") || moduleRoots.contains(parent))) {
                return true;
            }
            parent = parent.isEmpty() ? segment : parent + "/" + segment;
        }
        return false;
    }

    /**
     * The root ({@code ""}) and every directory holding a file named like one the root holds
     * directly: where a build writes its output. Derived from the tree itself, so no build
     * system's file names are written down here.
     */
    static Set<String> moduleRoots(List<String> paths) {
        Set<String> rootFiles = new HashSet<>();
        for (String p : paths) {
            if (p.indexOf('/') < 0) rootFiles.add(p);
        }
        Set<String> roots = new HashSet<>();
        roots.add("");
        for (String p : paths) {
            int slash = p.lastIndexOf('/');
            if (slash > 0 && rootFiles.contains(p.substring(slash + 1))) roots.add(p.substring(0, slash));
        }
        return roots;
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
