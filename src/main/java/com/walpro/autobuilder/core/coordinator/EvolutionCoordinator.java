package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.ProjectDiffService;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.core.messaging.AgentMessage;
import com.walpro.autobuilder.core.messaging.AgentMessageType;
import com.walpro.autobuilder.core.messaging.AgentRoles;
import com.walpro.autobuilder.core.messaging.MessageBus;
import com.walpro.autobuilder.core.service.FingerprintService;
import com.walpro.autobuilder.core.service.MutationApplier;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionCoordinator implements AgentCoordinator {

    private static final int MAX_RECENT_FINGERPRINTS = 10;

    private final EvolutionAgent evolutionAgent;
    private final MutationApplier mutationApplier;
    private final FingerprintService fingerprintService;
    private final ProjectDiffService projectDiffService;
    private final MessageBus messageBus;

    /**
     * One-shot flag: if true, next execute() will use recombination / divergent evolution.
     */
    private Map<String, Object> lastEvaluationResponse = Map.of();

    @PostConstruct
    void registerSignalSubscriptions() {
        messageBus.subscribe(AgentRoles.EVOLUTION, this::onAgentMessage);
    }

    public CycleContextData evolve(CycleContextData context) throws IOException {
        return runEvolution(context, false, "Evolution");
    }

    public CycleContextData recombineOnStagnation(CycleContextData context) throws IOException {
        log.info("Recombination triggered due to score stagnation — initiating fallback evolution...");
        return runEvolution(context, true, "Recombination");
    }

    private CycleContextData runEvolution(CycleContextData context,
                                          boolean divergent,
                                          String logPrefix) throws IOException {
        context = divergent
                ? evolutionAgent.evolveDivergent(context)
                : evolutionAgent.evolve(context);

        context = updateFingerprint(context);
        logEvolution(logPrefix, context);

        var outcome = context.evolutionOutcome();
        boolean changed = outcome != null && (!outcome.addedOrChangedFiles().isEmpty()
                || !outcome.deletedFiles().isEmpty());

        if (changed) {
            context = context.withSignal(
                    Signal.of(BasicSignals.EVOLUTION_OCCURRED, 1.0, 0.1, "evolution")
            );
        }

        try {
            mutationApplier.applyRegularMutations(context);
            ProjectDiffResult diff = computeProjectDiff(context);
            context = context.withProjectDiff(diff);
        } catch (IOException e) {
            log.error("Failed to apply mutations", e);
            return context;
        }

        return context.withLastEvolutionOutcome(context.evolutionOutcome());
    }

    @Override
    public String role() {
        return "evolution";
    }

    @Override
    public boolean canExecute(CycleContextData context) {
        if (context == null) return false;

        // 1) Needs the role signal
        boolean hasRole = context.hasStrongSignal(BasicSignals.ROLE_EVOLUTION, 0.10);
        if (!hasRole) return false;

        // 2) Hard gate: do not evolve if we don't even have a goal/subgoal
        boolean hasGoal = context.currentGoalId() != null && !context.currentGoalId().isBlank();
        boolean hasSubGoal = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank();

        return hasGoal || hasSubGoal;
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing coordinator (role={})", role());

        try {
            boolean recombinationFromContext = context.hasSignal(BasicSignals.RECOMBINATION_REQUEST); // same-cycle fallback
            boolean divergenceFromContext = context.hasSignal(BasicSignals.DIVERGENCE_REQUEST);
            boolean responseDivergence = shouldDivergeBasedOnResponse(context);
            boolean stagnationDivergence = context.hasSignal(BasicSignals.STAGNATION);
            boolean useDivergent = recombinationFromContext || divergenceFromContext || stagnationDivergence || responseDivergence;

//            if ((stagnationDivergence || responseDivergence) && !divergenceFromContext) { // if there is currently no divergence signal; currently commented out, because STAGNATION / INSIGHT divergence should be one-shot flag
//                context = context.withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "evolution-routing"));
//            }

            if (recombinationFromContext) {
                context = context.withoutSignal(BasicSignals.RECOMBINATION_REQUEST);
            }
            if (divergenceFromContext) {
                context = context.withoutSignal(BasicSignals.DIVERGENCE_REQUEST);
            }
            if (stagnationDivergence) {
                log.info("EvolutionCoordinator: using divergent evolution due to STAGNATION signal.");
                // consume signal so it doesn't trigger again next cycle
                context = context.withoutSignal(BasicSignals.STAGNATION);
            }

//            boolean divergence = context.hasSignal(BasicSignals.RECOMBINATION_REQUEST);
            context = useDivergent
                    ? recombineOnStagnation(context)
                    : evolve(context);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

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
        EvolutionOutcome outcome = context.evolutionOutcome();
        if (outcome == null) {
            return context;
        }

        String newFp = fingerprintService.computeEvolutionFingerprint(outcome);
        if (newFp == null || newFp.isBlank()) {
            return context;
        }

        String lastFp = context.lastEvolutionFingerprint();
        List<String> recent = context.recentEvolutionFingerprints() == null
                ? List.of()
                : context.recentEvolutionFingerprints();

        boolean isRepeat = Objects.equals(newFp, lastFp) || recent.contains(newFp);

        // maintain rolling window (newest first; remove duplicates)
        List<String> updatedRecent = new ArrayList<>(MAX_RECENT_FINGERPRINTS);
        updatedRecent.add(newFp);
        for (String fp : recent) {
            if (fp == null || fp.isBlank()) continue;
            if (Objects.equals(fp, newFp)) continue;
            updatedRecent.add(fp);
            if (updatedRecent.size() >= MAX_RECENT_FINGERPRINTS) break;
        }

        CycleContextData out = context
                .withLastEvolutionFingerprint(newFp)
                .withRecentEvolutionFingerprints(updatedRecent);

        if (isRepeat) {
            out = out
                    .withSignal(Signal.of(BasicSignals.REPETITION_DETECTED, 1.0, 0.05, "EvolutionCoordinator"))
                    .withSignal(Signal.of(BasicSignals.DIVERGENCE_REQUEST, 1.0, 0.10, "repetition"));
        } else if (!Objects.equals(newFp, lastFp)) {
            out = out.withSignal(
                    Signal.of(BasicSignals.EVOLUTION_FINGERPRINT_CHANGED, 1.0, 0.05, "EvolutionCoordinator")
            );
        }

        return out;
    }

    void onAgentMessage(AgentMessage message) {
        if (!AgentRoles.EVALUATION.equals(message.fromAgent())) {
            return;
        }

        if (message.type() == AgentMessageType.RESPONSE) {
            lastEvaluationResponse = message.payload();
            log.debug("EvolutionCoordinator stored Evaluation RESPONSE payload for future evolution decisions: {}",
                    lastEvaluationResponse);
        }
    }

    Map<String, Object> getLastEvaluationResponse() {
        return lastEvaluationResponse;
    }

    private boolean shouldDivergeBasedOnResponse(CycleContextData ctx) {
        if (lastEvaluationResponse == null || lastEvaluationResponse.isEmpty()) {
            return false;
        }

        Object cycleName = lastEvaluationResponse.get("cycleName");
        if (cycleName == null || !String.valueOf(cycleName).equals(ctx.cycleName())) {
            // Ignore stale response from another cycle
            return false;
        }

        Object gp = lastEvaluationResponse.get("goalProgress");
        boolean diverge = gp != null && "REGRESSING".equals(String.valueOf(gp));

        if (diverge) {
            log.info("EvolutionCoordinator: divergence requested due to goalProgress=REGRESSING "
                    + "in evaluation response.");
        }

        // consume so it can't affect later cycles
        lastEvaluationResponse = null;
        return diverge;
    }

}
