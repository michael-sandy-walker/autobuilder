package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionCoordinator implements AgentCoordinator {

    private final EvolutionAgent evolutionAgent;
    private final MutationApplier mutationApplier;
    private final FingerprintService fingerprintService;
    private final ProjectDiffService projectDiffService;

    public CycleContextData evolve(CycleContextData context) throws IOException {
        CycleContextData updated = evolutionAgent.evolve(context);
        logEvolution("⚙️ Evolution", updated);

        context = updateFingerprint(context);

        try {
            mutationApplier.applyRegularMutations(context);
            ProjectDiffResult diff = computeProjectDiff(context);
            context = context.withProjectDiff(diff);
        } catch (IOException e) {
            log.error("❌ Failed to apply mutations", e);
            return context;
        }

        return updated.withLastEvolutionOutcome(updated.evolutionOutcome());
    }

    public CycleContextData recombineOnStagnation(CycleContextData context) throws IOException {
        log.info("♻️ Recombination triggered due to score stagnation — initiating fallback evolution...");
        CycleContextData updated = evolutionAgent.evolve(context.withRecombined(true));
        logEvolution("♻️ Recombination", updated);
        return updated.withRecombined(true);
    }

    @Override
    public String role() {
        return "evolution";
    }

    @Override
    public boolean canExecute(CycleContextData ctx) {
        return ctx.hasSignal("PLAN_COMPLETE") && !ctx.hasSignal("EVOLUTION_IN_PROGRESS");
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        context = context.withSignal(Signal.of("EVOLUTION_IN_PROGRESS", 1.0, 0.1, "evaluation"));
        try {
            context = context.recombined()
                    ? recombineOnStagnation(context)
                    : evolve(context);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        context = context.withSignal(Signal.of("EVOLUTION_COMPLETE", 1.0, 0.1, "evaluation"));
        context = context.withoutSignal("EVOLUTION_IN_PROGRESS");

        return context;
    }

    private void logEvolution(String prefix, CycleContextData context) {
        var outcome = context.evolutionOutcome();
        log.info("{} produced {} file changes and {} deletions.",
                prefix,
                outcome.addedOrChangedFiles().size(),
                outcome.deletedFiles().size()
        );
    }

    public ProjectDiffResult computeProjectDiff(CycleContextData context) throws IOException {
        Path previous;
        Path current;

        boolean isBootstrapPhase = context.cycleName() == null || context.cycleName().startsWith("bootstrap");
        boolean isFirstMutationAfterBootstrap = context.previousAcceptedCycleName() == null
                && context.cycleName() != null && context.cycleName().startsWith("cycle-");

        if (isBootstrapPhase) {
            // Skip comparison or compare output to null (clean start)
            previous = null;
            current = mutationApplier.resolveOutputPath();
        } else if (isFirstMutationAfterBootstrap) {
            previous = mutationApplier.resolveOutputPath(); // Last accepted is bootstrap output
            current = mutationApplier.resolveMutationPath(context.cycleName()); // First mutation
        } else {
            previous = mutationApplier.resolveMutationPath(context.previousAcceptedCycleName());
            current = mutationApplier.resolveMutationPath(context.cycleName());
        }

        return projectDiffService.computeDiff(current, previous);
    }

    private CycleContextData updateFingerprint(CycleContextData context) {
        String newFp = fingerprintService.computeFingerprint(context.evolutionOutcome() == null ? Collections.emptyList() : context.evolutionOutcome().addedOrChangedFiles());
        boolean isRepeat = newFp.equals(context.lastRecombinationFingerprint());

        if (context.recombined()) {
            return context
                    .withRepeatedRecombination(isRepeat)
                    .withLastRecombinationFingerprint(isRepeat ? context.lastRecombinationFingerprint() : newFp)
                    .withLastEvolutionFingerprint(newFp);
        }

        return context
                .withRepeatedRecombination(false)
                .withLastEvolutionFingerprint(newFp);
    }
}
