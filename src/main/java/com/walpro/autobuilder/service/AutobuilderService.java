package com.walpro.autobuilder.service;

import com.walpro.autobuilder.config.AutobuilderConfig;
import com.walpro.autobuilder.coordinator.BootstrapCoordinator;
import com.walpro.autobuilder.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.coordinator.MergeCoordinator;
import com.walpro.autobuilder.coordinator.PlannerCoordinator;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.init.SystemResetConfig;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.model.SubgoalMetadata;
import com.walpro.autobuilder.persistence.CycleContext;
import com.walpro.autobuilder.persistence.MutationSnapshotService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
    private final AutobuilderConfig autobuilderConfig;
    private final SystemResetConfig systemResetConfig;

    @PostConstruct
    public void init() throws IOException {
        if (autobuilderConfig.isResetOnStart()) {
            log.warn("⚠️ Autobuilder system reset triggered via config (AutobuilderService)");
            systemResetConfig.resetSystem();
        }
        boolean bootstrapped = bootstrapCoordinator.bootstrapIfNecessary();
        if (bootstrapped) {
            Optional<CycleContextData> initialContext = bootstrapCoordinator.prepareContext();
            CycleContextData context = plannerCoordinator.plan(initialContext.get());
            log.info("Bootstrap planner executed.");
        }
    }
    

    @Scheduled(fixedDelay = 60000)
    public void runAutobuilderCycle() {
        Optional<CycleContextData> initialContext = bootstrapCoordinator.prepareContext();
        if (initialContext.isEmpty()) {
            log.warn("Aborting cycle: unable to prepare context.");
            return;
        }

        CycleContextData context = runCycleStep(initialContext.get(), 0, false);

        try {
            double score = context.evaluationOutcome() != null ? context.evaluationOutcome().score() : -1.0;
            String comment = context.evaluationFeedback() != null ? context.evaluationFeedback() : "(no feedback)";

            SubgoalMetadata metadata = context.currentSubGoalId() != null
                    ? SubgoalMetadata.of(context.currentSubGoalId(), context.currentSubGoalDescription())
                    : SubgoalMetadata.of(null, context.currentGoalDescription());

            CycleContext snapshotContext = CycleContext.of(
                    context.cycleName(), score, comment, context.merged(), metadata
            );

            mutationSnapshotService.writeMutationMeta(snapshotContext);
            log.info("Mutation meta saved: {}", snapshotContext);

            mutationSnapshotService.createSnapshot(snapshotContext, context.outputPath());
            log.info("📸 Snapshot saved: {}", snapshotContext.getName());

        } catch (IOException e) {
            log.error("❌ Failed to create snapshot", e);
        }

        log.info("🧬 Cycle {} complete. Merged: {}", context.cycleName(), context.merged());
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
        try {
            applyMutations(context);
        } catch (IOException e) {
            log.error("Failed to apply mutations", e);
            return context; // abort this cycle
        }

        // Step 2: Evaluation
        context = evaluationCoordinator.evaluate(context);
        var interpretation = context.evaluationInterpretation();

        // Step 3: Goal success → promote and exit
        if (interpretation != null && interpretation.success()) {
            var current = goalPlannerService.getCurrentSubGoal();
            if (current != null) {
                log.info("🎯 Subgoal '{}':'{}' fulfilled. Promoting.", current.getId(), current.getDescription());
                goalPlannerService.promoteCurrentSubGoal();
                context = context.withSubgoalPromoted(true);
            }

            // Step 4: Merge if subgoal promoted
            if (context.subgoalPromoted()) {
                log.info("🔀 Merging after subgoal promotion.");
                context = mergeCoordinator.mergeCompletedBranch(context).withMerged(true);
            }

            return context;
        }

        // Step 5: Score stagnation → recombine + recurse
        if (interpretation != null && interpretation.stagnation()) {
            log.warn("💤 Stagnation detected. Recombining...");
            return runCycleStep(context, depth + 1, true); // Recurse
        }

        // Step 6: Otherwise → try to plan next steps
        context = plannerCoordinator.plan(context);
        return runCycleStep(context, depth + 1, false);
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
    }

}



