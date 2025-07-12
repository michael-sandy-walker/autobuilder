package com.walpro.autobuilder.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MutationSnapshotService {

    private final FileService fileService;
    private final ObjectMapper objectMapper;

    public Path writeMutationMeta(CycleContext context) throws IOException {
        Path mutationDir = fileService.resolveSecurePath("mutations/" + context.getName());
        Path metaFile = mutationDir.resolve("meta.json");

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
        fileService.writeFile(metaFile.toString(), json);

        return mutationDir;
    }

    public CycleContext readMutationMeta(Path snapshotDir) throws IOException {
        Path meta = snapshotDir.resolve("meta.json");
        if (!fileService.exists(meta)) {
            throw new IOException("Snapshot meta not found at: " + meta);
        }
        String json = fileService.readString(meta);
        return objectMapper.readValue(json, CycleContext.class);
    }

    public List<CycleContext> listAllMutations(String currentCycleId) {
        List<CycleContext> snapshots = new ArrayList<>();
        try {
            List<Path> folders = fileService.listSubfolders(Path.of("mutations"));
            for (Path folder : folders) {
            	if (folder.endsWith(currentCycleId)) {
            		log.debug("Skipping current cycle dir {}", currentCycleId);
            		continue;
            	}
                try {
                    snapshots.add(readMutationMeta(folder));
                } catch (IOException e) {
                    log.warn("⚠️ Skipping invalid snapshot at {}: {}", folder, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("❌ Failed to list snapshot folders", e);
        }
        return snapshots;
    }

    public List<CycleContext> listMergedMutations(String currentCycleId) {
        return listAllMutations(currentCycleId).stream()
                .filter(CycleContext::isMerged)
                .toList();
    }

    public Path createSnapshot(CycleContext context, Path sourceDir) throws IOException {
        Path snapshotDir = fileService.resolveSecurePath("snapshots/" + context.getName());
        fileService.copyFolder(sourceDir.toString(), snapshotDir.toString());
        
        String json = objectMapper.writeValueAsString(context);
        fileService.writeFile(snapshotDir + "/meta.json", json);

        return snapshotDir;
    }
}

