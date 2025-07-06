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
        bootstrapCoordinator.bootstrapIfNecessary();
    }

    @Scheduled(fixedDelay = 60000)
    public void runAutobuilderCycle() {
        Optional<CycleContextData> initialContext = bootstrapCoordinator.prepareContext();
        if (initialContext.isEmpty()) {
            log.warn("Aborting cycle: unable to prepare context.");
            return;
        }

        CycleContextData context = initialContext.get();
        context = evolutionCoordinator.evolve(context);

        try {
            applyMutations(context);
        } catch (IOException e) {
            log.error("Failed to apply mutations", e);
            return; // abort this cycle
        }

        context = evaluationCoordinator.evaluate(context);

        // Promote goal if evaluation is successful
        var current = goalPlannerService.getCurrentSubGoal();
        if (current == null) {
            log.warn("❌ No current subgoal found — skipping promotion.");
        } else if (context.evaluationOutcome() != null && context.evaluationOutcome().score() >= 0.9) {
            log.info("🎯 Goal '{}' considered fulfilled — promoting...", current.getId());
            goalPlannerService.promoteCurrentSubGoal();
            context = context.withSubgoalPromoted(true);
        }

        try {
            double score = context.evaluationOutcome() != null ? context.evaluationOutcome().score() : -1.0;
            String comment = context.evaluationFeedback() != null ? context.evaluationFeedback() : "(no feedback)";

            String subgoalId = context.currentSubGoalId();
            String subgoalDesc = context.currentSubGoalDescription();
            SubgoalMetadata subgoalMetadata = (subgoalId != null && subgoalDesc != null)
                    ? SubgoalMetadata.of(subgoalId, subgoalDesc)
                    : SubgoalMetadata.of(null, context.currentGoalDescription());

            CycleContext snapshotContext = CycleContext.of(
                    context.cycleName(),
                    score,
                    comment,
                    context.merged(),
                    subgoalMetadata
            );

            mutationSnapshotService.writeMutationMeta(snapshotContext);
            log.info("Mutation meta saved: {}", snapshotContext);

            mutationSnapshotService.createSnapshot(snapshotContext, context.outputPath());
            log.info("📸 Snapshot saved: {}", snapshotContext.getName());

        } catch (IOException e) {
            log.error("❌ Failed to create snapshot for cycle {}", context.cycleName(), e);
        }

        context = plannerCoordinator.plan(context);
        if (context.subgoalPromoted()) {
            log.info("🔀 Subgoal completed — initiating branch merge...");
            context = mergeCoordinator.mergeCompletedBranch(context)
                    .withMerged(true);
        }
        log.info("🧬 Cycle {} merge status: {}", context.cycleName(), context.merged() ? "MERGED" : "SKIPPED");

        log.info("Cycle {} completed.", context.cycleName());
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



