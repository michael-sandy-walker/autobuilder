package com.walpro.autobuilder.heuristic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class DefaultMutationHeuristicAnalyzer implements MutationHeuristicAnalyzer {

    @Override
    public List<HeuristicFinding> analyze(Map<String, String> mutatedFiles, Map<String, String> referenceFiles) {
        List<HeuristicFinding> findings = new ArrayList<>();

        for (Map.Entry<String, String> entry : referenceFiles.entrySet()) {
            String path = entry.getKey();
            String oldContent = entry.getValue();
            String newContent = mutatedFiles.get(path);

            // Check for deletion
            if (newContent == null) {
                findings.add(new HeuristicFinding(path, "DELETED", "File was deleted", 3));
                continue;
            }

            // Check for excessive shrink
            int oldLines = countLines(oldContent);
            int newLines = countLines(newContent);

            if (oldLines > 0 && newLines < oldLines * 0.2) {
                findings.add(new HeuristicFinding(path, "SHRINK", "File shrank from %d to %d lines".formatted(oldLines, newLines), 2));
            }

            // Placeholder or empty
            if (newContent.trim().isEmpty() || newContent.matches("(?s)^\\s*#.*$")) {
                findings.add(new HeuristicFinding(path, "EMPTY_OR_PLACEHOLDER", "File contains only placeholder or comment", 2));
            }

            // Lost config (example heuristic)
            if (path.endsWith("application.properties") && !newContent.contains("server.") && oldContent.contains("server.")) {
                findings.add(new HeuristicFinding(path, "CONFIG_LOSS", "Config keys like 'server.port' removed", 3));
            }
        }

        return findings;
    }

    private int countLines(String content) {
        return (int) content.lines().count();
    }
}
