package com.walpro.autobuilder.persistence;

import com.walpro.autobuilder.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;

@Service
public class MutationSnapshotService {

    private static final Path BASE_DIR = Path.of("mutations");

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
} 
