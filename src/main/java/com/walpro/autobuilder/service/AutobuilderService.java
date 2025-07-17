package com.walpro.autobuilder.service;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.config.AutobuilderConfigProperties;
import com.walpro.autobuilder.coordinator.BootstrapCoordinator;
import com.walpro.autobuilder.coordinator.CycleContextCoordinator;
import com.walpro.autobuilder.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.coordinator.MergeCoordinator;
import com.walpro.autobuilder.coordinator.PlannerCoordinator;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.init.SystemResetConfig;
import com.walpro.autobuilder.lifecycle.CycleOrchestrator;
import com.walpro.autobuilder.lifecycle.CycleResultState;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.persistence.CycleContext;
import com.walpro.autobuilder.persistence.MutationSnapshotService;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class AutobuilderService {

    private final BootstrapCoordinator bootstrapCoordinator;
    private final EvolutionCoordinator evolutionCoordinator;
    private final EvaluationCoordinator evaluationCoordinator;
    private final PlannerCoordinator plannerCoordinator;
    private final MergeCoordinator mergeCoordinator;
    private final FileService fileService;
    private final MutationSnapshotService mutationSnapshotService;
    private final GoalPlannerService goalPlannerService;
    private final AutobuilderConfigProperties autobuilderConfig;
    private final SystemResetConfig systemResetConfig;
    private final CycleContextCoordinator contextCoordinator;
    private final CycleOrchestrator cycleOrchestrator;

    private CycleContextData context = null;

    @PostConstruct
    public void init() throws IOException {
        if (autobuilderConfig.isResetOnStart()) {
            log.warn("⚠️ Autobuilder system reset triggered via config (AutobuilderService)");
            systemResetConfig.resetSystem();
        }

        boolean bootstrapped = bootstrapCoordinator.bootstrapIfNecessary();
        if (!bootstrapped) return;

        fileService.ensureFolderExists("output");

        Optional<CycleContextData> initialContext = bootstrapCoordinator.prepareContext(context);
        if (initialContext.isEmpty()) {
            log.warn("Aborting bootstrap cycle: unable to prepare context.");
            return;
        }

        context = initialContext.get();
        context = evolutionCoordinator.evolve(context); // First build by LLM

        try {
            applyBootstrapMutations(context);
            mutationSnapshotService.writeMutationMeta(context);
            log.info("✅ Bootstrap completed. First files written.");
        } catch (IOException e) {
            log.error("Failed to apply bootstrap mutations", e);
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void runAutobuilderCycle() {
        Optional<CycleContextData> initialContext = contextCoordinator.prepareRuntimeContext(context);
        if (initialContext.isEmpty()) {
            log.warn("Aborting cycle: unable to prepare context.");
            return;
        }

        context = runCycleStep(initialContext.get(), 0, false);

        try {
            CycleContext snapshotContext = mutationSnapshotService.writeMutationMeta(context);
            log.info("Mutation meta saved: {}", snapshotContext);

            mutationSnapshotService.writeSnapshot(context);
            log.info("📸 Snapshot saved: {}", snapshotContext.getName());

        } catch (IOException e) {
            log.error("❌ Failed to create snapshot", e);
        }

        log.info("🧬 Cycle {} complete. Result: {}, Merged: {}", context.cycleName(), context.cycleResultState(), context.merged());
    }

    private CycleContextData runCycleStep(CycleContextData context, int depth, boolean recombine) {
        if (depth >= 5) {
            log.warn("🛑 Max depth reached. Stopping recursion.");
            return context;
        }

        // Step 1: Evolution
        if (recombine) {
            context = evolutionCoordinator.recombineOnStagnation(context);
        } else {
            context = evolutionCoordinator.evolve(context);
        }

        String newFingerprint = computeFingerprint(context.evolutionOutcome().addedOrChangedFiles());

        if (context.recombined()) {
            if (newFingerprint.equals(context.lastRecombinationFingerprint())) {
                log.warn("🚨 Repeated recombination output detected.");
                context = context
                        .withRepeatedRecombination(true)
                        .withLastEvolutionFingerprint(newFingerprint);
            } else {
                context = context
                        .withRepeatedRecombination(false)
                        .withLastRecombinationFingerprint(newFingerprint)
                        .withLastEvolutionFingerprint(newFingerprint);
            }
        } else {
            context = context
                    .withRepeatedRecombination(false)
                    .withLastEvolutionFingerprint(newFingerprint);
        }

        try {
            applyMutations(context);
        } catch (IOException e) {
            log.error("Failed to apply mutations", e);
            return context;
        }

        // Step 2: Evaluation
        context = evaluationCoordinator.evaluate(context);
        var interpretation = context.evaluationInterpretation();
        if (interpretation != null) {
            context = context.withGoalProgress(interpretation.goalProgress());
        }

        // Step 3: Route via orchestrator
        CycleResultState result = cycleOrchestrator.determineNextState(context);
        context = context.withCycleResultState(result);

        switch (result) {
            case SUCCESSFUL -> {
                var current = goalPlannerService.getCurrentSubGoal();
                if (current != null) {
                    log.info("🎯 Subgoal '{}':'{}' fulfilled. Promoting.", current.getId(), current.getDescription());
                    boolean promoted = goalPlannerService.promoteCurrentSubGoal();
                    context = context.withSubgoalPromoted(promoted);
                }

                if (context.subgoalPromoted()) {
                    log.info("🔀 Merging after subgoal promotion.");
                    try {
                        context = mergeCoordinator.mergeCompletedBranch(context);
                    } catch (IOException e) {
                        log.error("Error during merge of cycle {}: {}", context.cycleName(), e.getMessage());
                    }
                }

                context = context.withPreviousAcceptedCycleName(context.cycleName());
                return context;
            }

            case STAGNATED -> {
                var current = goalPlannerService.getCurrentSubGoal();

                if (current != null
                        && interpretation.mutationState() == MutationAcceptanceState.ACCEPTED
                        && interpretation.current() >= 0.75) {

                    log.info("✅ Acceptable stagnation — promoting subgoal '{}':'{}'", current.getId(), current.getDescription());
                    boolean promoted = goalPlannerService.promoteCurrentSubGoal();
                    context = context.withSubgoalPromoted(promoted);
                } else {
                    log.info("🛑 Stagnation not promotable (score: {}, state: {})",
                            interpretation.current(), interpretation.mutationState());
                }

                return runCycleStep(context, depth + 1, !context.subgoalPromoted());
            }

            case REJECTED, FALLBACK_PLANNING -> {
                context = plannerCoordinator.plan(context);
                return runCycleStep(context, depth + 1, false);
            }
        }

        return context;
    }

    private void applyBootstrapMutations(CycleContextData context) throws IOException {
        String outputDir = "output/";
        var outcome = context.evolutionOutcome();

        if (outcome == null) {
            log.warn("No bootstrap outcome found — skipping application.");
            return;
        }

        for (var mutation : outcome.addedOrChangedFiles()) {
            String relativePath = outputDir + "/" + mutation.path();
            fileService.writeFile(relativePath, mutation.content());
            log.info("🚀 Bootstrap: wrote {}", relativePath);
        }

        for (var deletedPath : outcome.deletedFiles()) {
            String relativePath = outputDir + "/" + deletedPath;
            fileService.deleteFile(relativePath);
            log.info("🚀 Bootstrap: deleted {}", relativePath);
        }

        mutationSnapshotService.writeMutationMeta(context);
        log.info("💾 Bootstrap meta written for initial cycle {}", context.cycleName());
    }

    private void applyMutations(CycleContextData context) throws IOException {
        String parentDir = "mutations/" + context.cycleName();
        var outcome = context.evolutionOutcome();

        if (outcome == null) {
            log.warn("No evolution outcome found — skipping mutation application.");
            return;
        }

        for (var mutation : outcome.addedOrChangedFiles()) {
            String relativePath = parentDir + "/" + mutation.path();
            fileService.writeFile(relativePath, mutation.content());
            log.info("Applied mutation: wrote {}", relativePath);
        }

        for (var deletedPath : outcome.deletedFiles()) {
            String relativePath = parentDir + "/" + deletedPath;
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
        return content.replaceAll("\\s+", ""); // crude but effective for early detection
    }
}
