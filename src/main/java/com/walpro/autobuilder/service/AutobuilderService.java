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
import com.walpro.autobuilder.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.coordinator.MergeCoordinator;
import com.walpro.autobuilder.coordinator.PlannerCoordinator;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.init.SystemResetConfig;
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
    
    private CycleContextData context = null;

    @PostConstruct
    public void init() throws IOException {
        if (autobuilderConfig.isResetOnStart()) {
            log.warn("⚠️ Autobuilder system reset triggered via config (AutobuilderService)");
            systemResetConfig.resetSystem();
        }
        boolean bootstrapped = bootstrapCoordinator.bootstrapIfNecessary();
        if (bootstrapped) {
            Optional<CycleContextData> initialContext = bootstrapCoordinator.prepareContext(context);
            
            if (initialContext.isEmpty()) {
                log.warn("Aborting cycle: unable to prepare context.");
                return;
            }
            
            context = initialContext.get();
            
            context = plannerCoordinator.plan(context).withPreviousAcceptedCycleName(context.cycleName());
            log.info("Bootstrap planner executed.");
            	
            applyMutations(context);
            mutationSnapshotService.writeMutationMeta(context);
        }
    }
    

    @Scheduled(fixedDelay = 60000)
    public void runAutobuilderCycle() {
        Optional<CycleContextData> initialContext = bootstrapCoordinator.prepareContext(context);
        
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
        
        context = context.withLastEvolutionFingerprint(newFingerprint);
        
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
                try {
					mergeCoordinator.mergeCompletedBranch(context);
				} catch (IOException e) {
					log.error("Error during merge of cycle {}: {}", context.cycleName(), e.getMessage());
				}
            }
            
            context.withPreviousAcceptedCycleName(context.cycleName());

            return context;
        }

        // Step 5: Score stagnation → recombine + recurse
        if (interpretation != null && interpretation.stagnation()) {
            log.warn("💤 Stagnation detected.");

            var current = goalPlannerService.getCurrentSubGoal();

            if (current != null
                && interpretation.mutationState() == MutationAcceptanceState.ACCEPTED
                && interpretation.current() >= 0.75) {

                log.info("✅ Acceptable stagnation — promoting subgoal '{}':'{}'", current.getId(), current.getDescription());
                goalPlannerService.promoteCurrentSubGoal();
                context = context.withSubgoalPromoted(true);
            } else {
                log.info("🛑 Stagnation not promotable (score: {}, state: {})",
                    interpretation.current(), interpretation.mutationState());
            }

            return runCycleStep(context, depth + 1, !context.subgoalPromoted());
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



