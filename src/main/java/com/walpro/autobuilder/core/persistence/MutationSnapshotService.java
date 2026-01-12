package com.walpro.autobuilder.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
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
    
    public CycleContextData writeMutationMeta(CycleContextData context) throws IOException {
        String mutationPath = "mutations/" + context.cycleName();
        String targetPath = mutationPath + "/meta.json";
        fileService.writeJson(targetPath, context);
        log.debug("Wrote mutation meta for {} to {}", context.cycleName(), targetPath);
        return context.withMutationPath(mutationPath);
    }


    public CycleContextData readMutationMeta(CycleContextData context) throws IOException {
       return(readMutationMeta("mutations/" + context.cycleName() + "/meta.json"));
    }
    
    public CycleContextData readMutationMeta(String relativePath) throws IOException {
//    	String relativePath = "mutations/" + cycleName + "/meta.json";
        Path path = fileService.resolveSecurePath(relativePath);
        return readMutationMetaResolved(path);
    }
    
    public CycleContextData readMutationMetaResolved(Path path) throws IOException {
        if (!fileService.existsResolved(path)) {
            throw new IOException("Snapshot meta not found at: " + path);
        }
        String json = fileService.readStringResolved(path);
        return objectMapper.readValue(json, CycleContextData.class);
    }

    public List<CycleContextData> listAllMutations(CycleContextData context) {
    	String currentCycleId = context.cycleName();
        List<CycleContextData> mutations = new ArrayList<>();
        try {
            List<Path> folders = fileService.listSubfolders("mutations");
            for (Path folder : folders) {
            	if (folder.endsWith(currentCycleId)) {
            		log.debug("Skipping current cycle dir {}", currentCycleId);
            		continue;
            	}
                try {
                	mutations.add(readMutationMeta(folder.toString() + "/meta.json"));
                } catch (IOException e) {
                    log.warn("Skipping invalid snapshot at {}: {}", folder, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to list snapshot folders", e);
        }
        return mutations;
    }

    public List<CycleContextData> listMergedMutations(CycleContextData context) {
        return listAllMutations(context).stream()
                .filter(CycleContextData::merged)
                .toList();
    }

    public CycleContextData writeSnapshot(CycleContextData context) throws IOException {
        String targetDir = "snapshots/" + context.cycleName();
        fileService.copyFolder(context.outputPath().toString(), targetDir);
        log.debug("Snapshot saved for {} at {}", context.cycleName(), targetDir);
        return context.withSnapshotPath(targetDir);
    }

}

