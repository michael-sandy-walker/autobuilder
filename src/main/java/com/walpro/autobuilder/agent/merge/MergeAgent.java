package com.walpro.autobuilder.agent.merge;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildExecutor;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.service.merge.CycleMergeTracker;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.core.service.merge.MergeProcessor;
import com.walpro.autobuilder.core.service.merge.MergeResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.graph.merge.MergeActionRegistrar;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MergeAgent implements ExecutionAgent {

    private final FileService fileService;
    private final FileVariantCollector fileVariantCollector;
    private final MergeProcessor mergeProcessor;
    private final CycleMergeTracker cycleMergeTracker;
    private final MergeActionRegistrar mergeActionRegistrar;
    private final GoalPlannerService goalPlannerService;
    private final BuildExecutor buildExecutor;

    /** T4-AN-1: target project root used as the build executor's working directory. */
    @Value("${file.base-path:}")
    private String fileBasePath;

    /** T5-H: optional behaviour trace logger. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    private static final String AGENT_ID = "StaticMergeAgent";

    /** T2-CT: metadata key recording which {@link BuildErrorClassifier.Kind} was detected. */
    static final String METADATA_KEY_BUILD_ERROR_KIND = "buildErrorKind";

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing agent (role={})", role());

        try {
            return mergeCompletedBranch(context);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public CycleContextData mergeCompletedBranch(CycleContextData context) throws IOException {
        // T1-BP: defensive guard — eligibility explainer already blocks this path, but guard here
        // as a safety net in case MergeAgent is invoked directly (e.g. tests or future call sites).
        if (context.currentSubGoalId() == null || context.currentSubGoalId().isBlank()) {
            log.warn("MergeAgent: no active subgoal (currentSubGoalId=null/blank) — skipping merge.");
            return context;
        }

        String outputPath = context.outputPath();
        String cycleId = context.cycleName();
//        String mergeCycleId = (context.lastAttemptId() != null && !context.lastAttemptId().isBlank())
//                ? context.lastAttemptId()
//                : context.cycleName();

        if (cycleMergeTracker.isAlreadyMerged(cycleId)) {
            log.debug("Skipping merge — cycle {} already marked as merged", cycleId);
            return context;
        }

        List<String> alreadyMergedCycles = cycleMergeTracker.getMergedCycles(context);
        Set<String> allPaths = fileVariantCollector.getAllVariantPaths(context);

        boolean anyMergeRegistered = false;
        List<String> successfullyMergedPaths = new java.util.ArrayList<>();

        for (String path : allPaths) {
            List<FileVariant> variants;
            try {
                variants = fileVariantCollector.collect(path, alreadyMergedCycles, context.currentSubGoalId());
            } catch (IOException e) {
                log.warn("Failed to collect variants for {}: {}", path, e.getMessage());
                continue;
            }

            if (variants.isEmpty()) {
                log.debug("No variants found for {}", path);
                continue;
            }

            String outputFileRel = outputPath + "/" + path;

            boolean fileExistsInOutput = fileService.exists(outputFileRel);
            String baseContent = fileExistsInOutput ? fileService.readString(outputFileRel) : "";

            MergeResult result = mergeProcessor.process(path, baseContent, variants, fileExistsInOutput, context);

            if (!result.wasSuccessful()) {
                log.warn("Merge failed for {}", path);
                continue;
            }

            try {
                Path outputFile = fileService.resolveSecurePath(outputFileRel);
                fileService.writeFileResolved(outputFile, result.currentContent());
            } catch (IOException e) {
                log.warn("Failed to write output file {}: {}", path, e.getMessage());
                continue;
            }

            context = cycleMergeTracker.track(context, path, result);

            mergeActionRegistrar.registerMerge(AGENT_ID, context, result); // TODO: here, we do not have the ultimate status, including markCyclesAsMerged and withoutSignal ROLE_MERGE, think about this
            successfullyMergedPaths.add(path);
            anyMergeRegistered = true;
        }

        // T1-CD: FileVariantCollector.collect() now filters to ACCEPTED-only variants. When no
        // ACCEPTED variants exist (all already merged, or the acceptance happened in a cycle that
        // is no longer on disk), the loop above is a no-op and mergeActionRegistrar is never
        // called. Without a graph record, isAcceptedNotMergedAttempt() stays permanently true,
        // blocking evolution forever. Register a SKIPPED action so the snapshot's lastMerge
        // becomes non-null and the stale "accepted-not-merged" state is cleared.
        if (!anyMergeRegistered) {
            log.debug("No ACCEPTED variants found to merge — registering SKIPPED merge action to clear graph state");
            mergeActionRegistrar.registerMerge(AGENT_ID, context, null);
        }

        // Compute file count of this mutation for T2-AJ complexity-adaptive tolerance.
        EvolutionOutcome evolutionOutcome = context.evolutionOutcome();
        int mergedFileCount = 0;
        if (evolutionOutcome != null) {
            mergedFileCount += (evolutionOutcome.addedOrChangedFiles() != null ? evolutionOutcome.addedOrChangedFiles().size() : 0);
            mergedFileCount += (evolutionOutcome.deletedFiles() != null ? evolutionOutcome.deletedFiles().size() : 0);
        }

        if (mergedFileCount > 0) {
            goalPlannerService.incrementGoalMergeCount(context.currentSubGoalId());
        }

        if (!successfullyMergedPaths.isEmpty()) {
            goalPlannerService.recordMergedFiles(context.currentSubGoalId(), successfullyMergedPaths);
        }

        if (behaviourLogger != null && !successfullyMergedPaths.isEmpty()) {
            behaviourLogger.mutationMerged(
                    context.cycleName(),
                    context.lastEvolutionFingerprint(),
                    successfullyMergedPaths
            );
        }

        // T4-AN-1: post-merge compile gate. Only invoke when something was actually written —
        // running the build on a SKIPPED merge would just re-validate the prior state.
        if (!successfullyMergedPaths.isEmpty() && buildExecutor.isEnabled()) {
            BuildOutcome buildOutcome = buildExecutor.execute(fileBasePath, context.cycleName());
            log.info("MergeAgent T4-AN-1: post-merge build result={} ({} paths merged)",
                    buildOutcome.result(), successfullyMergedPaths.size());
            context = context.withBuildOutcome(buildOutcome);

            // T1-DM: when the build is broken, deterministically inject a BUILD_FIX subgoal
            // and make it the current subgoal. Sidesteps PlannerAgent's count-based guards
            // (the LLM-driven structured-replan path is unreliable while the backlog is full
            // of unrelated work). Idempotent: skipped when an active BUILD_FIX subgoal exists.
            if (buildOutcome.result() == BuildResult.COMPILE_ERROR) {
                injectBuildFixSubgoalIfAbsent(buildOutcome);
            }
        }

        // Consume merge role signal so it doesn't keep firing.
        return cycleMergeTracker
                .markCyclesAsMerged(context)
                .withLastAcceptedMutationFileCount(mergedFileCount)
                .withoutSignal(BasicSignals.ROLE_MERGE);
    }

    /**
     * T1-DM: ensure exactly one active BUILD_FIX subgoal exists when the post-merge build fails.
     * Scans active backlog (currentSubGoal + remaining) for a goal already tagged with
     * {@code metadata[blockerType]=BUILD_FIX}; if absent, creates one and sets it as current.
     * Bypasses PlannerAgent guards entirely — calls GoalPlannerService directly.
     */
    private void injectBuildFixSubgoalIfAbsent(BuildOutcome buildOutcome) {
        if (hasActiveBuildFixSubgoal()) {
            log.debug("T1-DM: build-fix subgoal already active — skipping injection");
            return;
        }

        String description = "Fix compile error: " + summariseStderr(buildOutcome.stderrTail());
        String newId = "goal-" + UUID.randomUUID();
        try {
            // setAsCurrent=true so the next cycle works on the build-fix immediately rather than
            // continuing on whatever stuck subgoal the LLM was looping on.
            goalPlannerService.addSubGoal(newId, description, true);
        } catch (RuntimeException e) {
            log.warn("T1-DM: failed to inject BUILD_FIX subgoal: {}", e.getMessage());
            return;
        }

        // T2-CT: classify the error so criteria name the actual missing artifact.
        Classification classification = BuildErrorClassifier.classify(buildOutcome.stderrTail());
        GoalEntity injected = goalPlannerService.getGoalById(newId);
        if (injected != null) {
            injected.getMetadata().put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
            injected.getMetadata().put(METADATA_KEY_BUILD_ERROR_KIND, classification.kind().name());
            goalPlannerService.saveGoalMetadata(injected);
        }
        goalPlannerService.setAcceptanceCriteria(newId, classification.criteria());

        log.info("T1-DM: injected BUILD_FIX subgoal [{}] as current — '{}'", newId, description);
    }

    private boolean hasActiveBuildFixSubgoal() {
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (isActiveBuildFix(current)) return true;
        for (GoalEntity g : goalPlannerService.getRemainingGoals()) {
            if (isActiveBuildFix(g)) return true;
        }
        return false;
    }

    private static boolean isActiveBuildFix(GoalEntity g) {
        if (g == null || g.getMetadata() == null) return false;
        if (g.getStatus() != GoalStatus.ACTIVE) return false;
        return BlockerTypes.BUILD_FIX.equals(g.getMetadata().get(BlockerTypes.METADATA_KEY));
    }

    private static final java.util.regex.Pattern ANSI_ESCAPE = BuildErrorClassifier.ANSI_ESCAPE;

    private static String summariseStderr(String stderrTail) {
        if (stderrTail == null || stderrTail.isBlank()) return "(no stderr captured)";
        // Prefer the first informative [ERROR]-prefixed line: Maven marks every error with
        // [ERROR], and `[INFO] Scanning…`/banner lines tend to dominate the stream otherwise.
        // Skip framing-only lines: bare "[ERROR]" or "-> [Help N]" / "-> [Help]" continuation.
        // Strip ANSI colour codes before matching — Maven colourises [ERROR] as ESC[...m[ERROR]ESC[m.
        String summary = stderrTail.lines()
                .map(line -> ANSI_ESCAPE.matcher(line).replaceAll("").strip())
                .filter(line -> line.startsWith("[ERROR]"))
                .map(line -> line.substring("[ERROR]".length()).strip())
                .filter(stripped -> !stripped.isEmpty() && !stripped.startsWith("-> [Help"))
                .findFirst()
                .orElseGet(() -> stderrTail.lines()
                        .map(line -> ANSI_ESCAPE.matcher(line).replaceAll("").strip())
                        .filter(s -> !s.isEmpty())
                        .findFirst()
                        .orElse(stderrTail.strip()));
        return summary.length() > 200 ? summary.substring(0, 200) + "…" : summary;
    }

    @Override
    public String name() {
        return "merge";
    }

    @Override
    public String role() {
        return "merge";
    }

    @Override
    public Set<SignalType> getSupportedSignals() {
        return BasicSignals.MERGE_SIGNALS;
    }
}
