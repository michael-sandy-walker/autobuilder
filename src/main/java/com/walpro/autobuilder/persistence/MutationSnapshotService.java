package com.walpro.autobuilder.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.lifecycle.CycleContextMapper;
import com.walpro.autobuilder.model.CycleContextData;
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

    public CycleContext writeMutationMeta(CycleContextData context) throws IOException {
        CycleContext meta = CycleContextMapper.from(context);
        return writeMutationMeta(meta);
    }
    
    public CycleContext writeMutationMeta(CycleContext context) throws IOException {
        String targetPath = "mutations/" + context.getName() + "/meta.json";
        fileService.writeJson(targetPath, context);
        log.info("📝 Wrote mutation meta for {} to {}", context.getName(), targetPath);
        return context;
    }


    public CycleContext readMutationMeta(CycleContextData context) throws IOException {
       return(readMutationMeta("mutations/" + context.cycleName() + "/meta.json"));
    }
    
    public CycleContext readMutationMeta(String relativePath) throws IOException {
//    	String relativePath = "mutations/" + cycleName + "/meta.json";
        Path path = fileService.resolveSecurePath(relativePath);
        return readMutationMetaResolved(path);
    }
    
    public CycleContext readMutationMetaResolved(Path path) throws IOException {
        if (!fileService.existsResolved(path)) {
            throw new IOException("Snapshot meta not found at: " + path);
        }
        String json = fileService.readStringResolved(path);
        return objectMapper.readValue(json, CycleContext.class);
    }

    public List<CycleContext> listAllMutations(CycleContextData context) {
    	String currentCycleId = context.cycleName();
        List<CycleContext> mutations = new ArrayList<>();
        try {
            List<Path> folders = fileService.listSubfolders("mutations");
            for (Path folder : folders) {
            	if (folder.endsWith(currentCycleId)) {
            		log.debug("Skipping current cycle dir {}", currentCycleId);
            		continue;
            	}
                try {
                	mutations.add(readMutationMeta(context));
                } catch (IOException e) {
                    log.warn("⚠️ Skipping invalid snapshot at {}: {}", folder, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("❌ Failed to list snapshot folders", e);
        }
        return mutations;
    }

    public List<CycleContext> listMergedMutations(CycleContextData context) {
        return listAllMutations(context).stream()
                .filter(CycleContext::isMerged)
                .toList();
    }

    public void writeSnapshot(CycleContextData context) throws IOException {
        String targetDir = "snapshots/" + context.cycleName();
        fileService.copyFolder(context.outputPath().toString(), targetDir);
        log.info("📸 Snapshot saved for {} at {}", context.cycleName(), targetDir);
    }

}

