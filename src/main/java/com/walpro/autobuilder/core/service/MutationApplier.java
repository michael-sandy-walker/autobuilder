package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class MutationApplier {

    private final FileService fileService;
    private final MutationSnapshotService mutationSnapshotService;

    /** T4-CQ: operator-declared paths no writer may touch; optional in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.core.build.ProtectedPaths protectedPaths;

    public void applyRegularMutations(CycleContextData context) throws IOException {
        applyMutations(context, false);
        mutationSnapshotService.writeMutationMeta(context);
        log.info("Mutation meta written for cycle {}", context.cycleName());
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
            try {
                if (includeOutput) {
                    // T2-EW: this is the door the merge-agent filter did not cover. Run
                    // 2026-07-29_00-54-22 failed at artifact-build-failed on broken
                    // BookControllerTest/BookServiceTest while the log shows MergeAgent had
                    // SKIPPED exactly those two files — evolution writes its mutations here,
                    // straight into output/, so guarding the merge loop alone changed nothing.
                    // Same policy, applied where the write actually happens.
                    if (com.walpro.autobuilder.core.build.UnrequestedTestSourcePolicy
                            .isUnrequested(file.path(), context.currentGoalDescription())) {
                        log.info("T2-EW: not writing unrequested test source {} — the goal"
                                + " specification asks for no tests", file.path());
                        continue;
                    }
                    if (protectedPaths != null && protectedPaths.refuse("evolution", file.path())) {
                        continue;
                    }
                    writeFile(outputPrefix + file.path(), file.content(), "output");
                } else {
                    writeFile(mutationPrefix + file.path(), file.content(), "mutations");
                }
            } catch (FileAlreadyExistsException e) {
                log.warn("Skipping mutation for [{}]: path conflicts with an existing directory — LLM may have returned a directory path as a file path", file.path());
            } catch (IOException e) {
                log.warn("Skipping mutation for [{}]: {}", file.path(), e.getMessage());
            }
        }

        for (String deleted : mutations.deletedFiles()) {
            try {
                if (includeOutput) {
                    if (protectedPaths != null && protectedPaths.refuse("evolution", deleted)) continue;
                    deleteFile(outputPrefix + deleted, "output");
                } else {
                    deleteFile(mutationPrefix + deleted, "mutations");
                }
            } catch (IOException e) {
                log.warn("Skipping delete for [{}]: {}", deleted, e.getMessage());
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
        log.info("Wrote to {}: {}", label, path);
    }

    private void deleteFile(String path, String label) throws IOException {
        fileService.deleteFile(path);
        log.info("Deleted from {}: {}", label, path);
    }
}
