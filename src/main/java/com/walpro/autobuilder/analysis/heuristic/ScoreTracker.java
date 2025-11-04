package com.walpro.autobuilder.analysis.heuristic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;

@Slf4j
public class ScoreTracker {
	
	private final FileService fileService;
    private final Path mutationBaseDir;
    private final ObjectMapper mapper = new ObjectMapper();
	
	private boolean lastAccepted = false;
	
	public ScoreTracker(FileService fileService) throws IOException {
		this.fileService = fileService;
		mutationBaseDir = fileService.resolveSecurePath("mutations");
	}

    public Double getLastAcceptedScore() {
        try {

            return fileService.listSubfoldersResolved(mutationBaseDir).stream()
            	.sorted(Comparator.comparing(p -> p.toString().replace('\\', '/')).reversed())
                .map(subfolder -> subfolder.resolve("meta.json"))
                .filter(path -> {
                    try {
                        return fileService.existsResolved(path);
                    } catch (IOException e) {
                        log.warn("Error checking existence of {}", path, e);
                        return false;
                    }
                })
                .map(this::readScoreSafe)
                .filter(score -> score != null && score > 0)
                .findFirst()
                .orElse(0d);

        } catch (IOException e) {
            log.error("Failed to compute last accepted score", e);
            return 0d;
        }
    }
    
    private Double readScoreSafe(Path metaJson) {
        try {
            return readScore(metaJson);
        } catch (Exception e) {
            log.warn("Failed to read score from {}", metaJson, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
	private Double readScore(Path metaPath) {
        try {
            Map<String, Object> meta = mapper.readValue(metaPath.toFile(), Map.class);
            Object score = meta.get("score");
            return score instanceof Number ? ((Number) score).doubleValue() : null;
        } catch (IOException e) {
            return null;
        }
    }
    
    public boolean wasLastAccepted() {
        return lastAccepted;
    }

    public void markAccepted() {
        lastAccepted = true;
    }

    public void markRejected() {
        lastAccepted = false;
    }
}
