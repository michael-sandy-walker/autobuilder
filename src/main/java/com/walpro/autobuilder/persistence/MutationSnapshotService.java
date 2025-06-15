package com.walpro.autobuilder.persistence;

import com.walpro.autobuilder.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;

@Service
public class MutationSnapshotService {

    private static final Path BASE_DIR = Path.of("firestarter", "mutations");

    @Autowired
    private FileService fileService;

    public Path createSnapshot(CycleContext context, Path outputDir) throws IOException {
        Path snapshotDir = BASE_DIR.resolve(context.getId());
        Path snapshotSrc = snapshotDir.resolve("src");
        fileService.copyFolder(outputDir.toString(), snapshotSrc.toString());

        // optionally write metadata
        Path meta = snapshotDir.resolve("meta.json");
        Map<String, Object> metaInfo = new HashMap<>();
        metaInfo.put("cycle", context.getName());
        metaInfo.put("timestamp", context.getTimestamp().toString());
        metaInfo.put("score", context.getScore());
        metaInfo.put("comment", context.getComment());
        fileService.writeFile(meta.toString(), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(metaInfo));

        return snapshotDir;
    }

    
    private void writeMetadata(Path snapshotDir, double score, String summary) throws IOException {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("score", score);
        metadata.put("summary", summary);
        metadata.put("timestamp", Instant.now().toString());

        ObjectMapper mapper = new ObjectMapper();
        Path metaFile = snapshotDir.resolve("meta.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), metadata);
    }
} 
