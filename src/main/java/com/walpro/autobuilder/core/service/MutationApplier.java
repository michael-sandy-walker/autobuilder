package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class MutationApplier {

    private final FileService fileService;
    private final MutationSnapshotService mutationSnapshotService;

    public void applyRegularMutations(CycleContextData context) throws IOException {
        applyMutations(context, false);
        mutationSnapshotService.writeMutationMeta(context);
        log.info("💾 Mutation meta written for cycle {}", context.cycleName());
    }

    private void applyMutations(CycleContextData context, boolean includeOutput) throws IOException {
        var mutations = context.evolutionOutcome();
        if (mutations == null) {
            log.warn("No evolution outcome found — skipping mutation application.");
            return;
        }

        String outputPrefix = "output/";
        String mutationPrefix = "mutations/" + context.cycleName() + "/";

        for (FileMutation file : mutations.addedOrChangedFiles()) {
            if (includeOutput) {
                writeFile(outputPrefix + file.path(), file.content(), "output");
            } else {
            	writeFile(mutationPrefix + file.path(), file.content(), "mutations");
            }
        }

        for (String deleted : mutations.deletedFiles()) {
            if (includeOutput) {
                deleteFile(outputPrefix + deleted, "output");
            } else {
            	deleteFile(mutationPrefix + deleted, "mutations");
            }
        }
    }

    public Path resolveMutationPath(String cycleName) throws IOException {
        return fileService.resolveSecurePath("mutations/" + cycleName);
    }
    
    public Path resolveOutputPath() throws IOException {
        return fileService.resolveSecurePath("output");
    }

    private void writeFile(String path, String content, String label) throws IOException {
        fileService.writeFile(path, content);
        log.info("✍️ Wrote to {}: {}", label, path);
    }

    private void deleteFile(String path, String label) throws IOException {
        fileService.deleteFile(path);
        log.info("🗑️ Deleted from {}: {}", label, path);
    }
}
