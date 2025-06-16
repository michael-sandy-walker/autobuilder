package com.walpro.autobuilder.heuristic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.service.FileService;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;

@Slf4j
public class ScoreTracker {
	
	private final FileService fileService;
	
	public ScoreTracker(FileService fileService) {
		this.fileService = fileService;
	}

    private final Path mutationBaseDir = Path.of("mutations");
    private final ObjectMapper mapper = new ObjectMapper();

    public Double getLastAcceptedScore() {
        try {
            return fileService.list(mutationBaseDir)
                .filter(t -> {
					try {
						return fileService.isDirectory(t);
					} catch (IOException e) {
						log.error("Error during processing isDirectory for dir {}", t);
						return false;
					}
				})
                .sorted(Comparator.comparing(Path::toString).reversed()) // latest first
                .map(path -> path.resolve("meta.json"))
                .filter(Files::exists)
                .map(this::readScore)
                .filter(score -> score != null && score > 0)
                .findFirst()
                .orElse(0d);
        } catch (IOException e) {
            return 0d;
        }
    }

    private Double readScore(Path metaPath) {
        try {
            Map<String, Object> meta = mapper.readValue(metaPath.toFile(), Map.class);
            Object score = meta.get("score");
            return score instanceof Number ? ((Number) score).doubleValue() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
