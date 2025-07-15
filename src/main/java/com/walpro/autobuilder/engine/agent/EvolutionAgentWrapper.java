package com.walpro.autobuilder.engine.agent;

import com.walpro.autobuilder.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.engine.ExecutionAgent;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.service.FileService;
import com.walpro.autobuilder.persistence.MutationSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class EvolutionAgentWrapper implements ExecutionAgent {

    private final EvolutionCoordinator evolutionCoordinator;
    private final FileService fileService;
    private final MutationSnapshotService mutationSnapshotService;

    @Override
    public boolean canExecute(CycleContextData context) {
        return true; // always applies unless constrained
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        CycleContextData evolved;

        // Apply evolution or recombination
        if (context.recombined()) {
            evolved = evolutionCoordinator.recombineOnStagnation(context);
        } else {
            evolved = evolutionCoordinator.evolve(context);
        }

        String newFingerprint = computeFingerprint(evolved.evolutionOutcome().addedOrChangedFiles());

        // Handle recombination tracking
        if (evolved.recombined()) {
            if (newFingerprint.equals(evolved.lastRecombinationFingerprint())) {
                log.warn("🚨 Repeated recombination output detected.");
                evolved = evolved
                        .withRepeatedRecombination(true)
                        .withLastEvolutionFingerprint(newFingerprint);
            } else {
                evolved = evolved
                        .withRepeatedRecombination(false)
                        .withLastRecombinationFingerprint(newFingerprint)
                        .withLastEvolutionFingerprint(newFingerprint);
            }
        } else {
            evolved = evolved
                    .withRepeatedRecombination(false)
                    .withLastEvolutionFingerprint(newFingerprint);
        }

        try {
            applyMutations(evolved);
        } catch (IOException e) {
            log.error("❌ Failed to apply mutations", e);
            return evolved;
        }

        return evolved;
    }

    @Override
    public String name() {
        return "EvolutionAgent";
    }

    private void applyMutations(CycleContextData context) throws IOException {
        var outcome = context.evolutionOutcome();
        if (outcome == null) {
            log.warn("No evolution outcome found — skipping mutation application.");
            return;
        }

        String cycleDir = "mutations/" + context.cycleName();

        for (var mutation : outcome.addedOrChangedFiles()) {
            String relativePath = cycleDir + "/" + mutation.path();
            fileService.writeFile(relativePath, mutation.content());
            log.info("Applied mutation: wrote {}", relativePath);
        }

        for (var deletedPath : outcome.deletedFiles()) {
            String relativePath = cycleDir + "/" + deletedPath;
            fileService.deleteFile(relativePath);
            log.info("Applied mutation: deleted {}", relativePath);
        }

        mutationSnapshotService.writeMutationMeta(context);
        log.info("💾 Mutation meta written for cycle {}", context.cycleName());
    }

    private String computeFingerprint(List<FileMutation> files) {
        return files.stream()
                .sorted(Comparator.comparing(FileMutation::path))
                .map(f -> f.path() + ":" + normalize(f.content()))
                .collect(Collectors.joining("||"));
    }

    private String normalize(String content) {
        return content.replaceAll("\\s+", "");
    }
}
