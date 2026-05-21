package com.walpro.autobuilder.agent.merge;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildExecutor;
import com.walpro.autobuilder.core.build.BuildFixTargets;
import com.walpro.autobuilder.core.build.CompileProbeService;
import com.walpro.autobuilder.core.merge.MergeRollbackPoint;
import com.walpro.autobuilder.core.merge.MergeRollbackService;
import com.walpro.autobuilder.core.merge.PackageFragmentationEvidence;
import com.walpro.autobuilder.core.merge.RolledBackEvidence;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.model.FileInfo;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.persistence.MutationSnapshotService;
import com.walpro.autobuilder.core.service.merge.CycleMergeTracker;
import com.walpro.autobuilder.core.service.merge.FileVariantCollector;
import com.walpro.autobuilder.core.service.merge.MergeProcessor;
import com.walpro.autobuilder.core.service.merge.MergeResult;
import com.walpro.autobuilder.goal.GoalPlannerService;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final BlockerSubgoalInjector blockerSubgoalInjector;
    private final MergeRollbackService mergeRollbackService;
    private final MutationSnapshotService mutationSnapshotService;
    private final CompileProbeService compileProbeService;

    /** T4-AN-1: target project root used as the build executor's working directory. */
    @Value("${file.base-path:}")
    private String fileBasePath;

    /** T5-H: optional behaviour trace logger. */
    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    private static final String AGENT_ID = "StaticMergeAgent";

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

        // T2-DB: skip per-file merges when the previous cycle rolled back AND this cycle's
        // mutation does not address the rollback target (e.g. mutation lacks pom.xml after a
        // MISSING_POM rollback, or none of the mutation paths overlap restoredPaths). Avoids
        // wasted per-file LLM merge calls + the trailing build run on a state that's already
        // known to fail. The existing BUILD_FIX subgoal carries forward to the next cycle.
        RolledBackEvidence rb = context.lastRollback();
        if (rb != null && !mutationAddressesRollback(context.evolutionOutcome(), rb)) {
            log.info("T2-DB: skipping merges — prior cycle {} rolled back (kind={}, restoredPaths={}) and current mutation does not address the rollback target",
                    rb.cycleId(), rb.errorKind(), rb.restoredPaths());
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

        // T1-EH: re-probe deferred ACCEPTED mutations against the current output state.
        // A mutation that compiled at evaluation time may no longer compile if a later cycle
        // moved or removed a symbol it depends on (observed 2026-05-19 with setReturned(boolean)
        // disappearing from Loan.java between evaluation and merge). Re-probing now catches the
        // staleness up-front and marks the mutation REJECTED via T1-EG — preventing the
        // T1-DW rollback + repeat-apply loop that T1-EG alone only breaks after the first hit.
        // The probe rolls itself back, so the actual merge runs against unmodified output.
        boolean anyRejected = reprobeDeferredMutations(context, allPaths, alreadyMergedCycles);
        if (anyRejected) {
            // Refresh: a deferred mutation flagged REJECTED may have been the only ACCEPTED
            // variant for some paths, which now have no merge candidate.
            allPaths = fileVariantCollector.getAllVariantPaths(context);
        }

        boolean anyMergeRegistered = false;
        List<String> successfullyMergedPaths = new java.util.ArrayList<>();
        // T1-DW: pre-merge originals for rollback; populated as each file is about to be written.
        Map<String, String> originalContents = new LinkedHashMap<>();
        Set<String> newlyAddedPaths = new LinkedHashSet<>();
        // T1-EG: union of every mutation cycle whose variant fed a successful merge this cycle.
        // Used in the rollback branch to mark each contributing cycle's meta.json as REJECTED so
        // FileVariantCollector does not re-pick it on subsequent cycles.
        Set<String> contributingCyclesAccumulated = new LinkedHashSet<>();
        // T4-BA: package-fragmentation conflicts found by the interim guard this cycle. Threaded
        // into PackageFragmentationEvidence after the loop so the next evolution prompt sees them.
        List<PackageFragmentationEvidence.Conflict> fragmentationConflicts = new java.util.ArrayList<>();

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

            // T4-BA: interim package-fragmentation guard. A simple type name must map to
            // exactly one package. If this mutation creates a *new* .java file whose top-level
            // type already exists under a different package in output/, the two become
            // distinct types — code compiles but runtime dispatch (instanceof, exception →
            // HTTP status mapping) silently breaks. The contributing mutation is marked
            // REJECTED (so FileVariantCollector does not re-pick it into a no-op loop) and the
            // conflict is carried forward to the next evolution prompt. The full
            // PackageFragmentationCritic (T4-AW SPI) will additionally catch splits already on
            // disk that this write-time guard cannot.
            if (!fileExistsInOutput) {
                String conflict = findPackageFragmentation(outputPath, path);
                if (conflict != null) {
                    String typeName = typeNameOf(path);
                    log.warn("T4-BA: package fragmentation — type {} already exists at {}; "
                                    + "rejecting fragmenting file {}",
                            typeName, conflict, path);
                    fragmentationConflicts.add(
                            new PackageFragmentationEvidence.Conflict(typeName, conflict, path));
                    // Mark every contributing mutation REJECTED in its meta.json — without this
                    // the ACCEPTED variant stays on disk and FileVariantCollector re-picks it
                    // every cycle, producing a silent no-op re-skip loop instead of progress.
                    for (FileVariant v : variants) {
                        if (v.originCycleId() != null && !v.originCycleId().isBlank()) {
                            mutationSnapshotService.markMutationRejected(v.originCycleId());
                        }
                    }
                    if (behaviourLogger != null) {
                        behaviourLogger.packageFragmentationDetected(cycleId, typeName,
                                conflict, path);
                    }
                    continue;
                }
            }

            String baseContent = fileExistsInOutput ? fileService.readString(outputFileRel) : "";

            MergeResult result = mergeProcessor.process(path, baseContent, variants, fileExistsInOutput, context);

            if (!result.wasSuccessful()) {
                log.warn("Merge failed for {}", path);
                continue;
            }

            try {
                Path outputFile = fileService.resolveSecurePath(outputFileRel);
                // T1-DW: snapshot before write so we can restore if the build gate fails.
                originalContents.put(path, baseContent);
                if (!fileExistsInOutput) {
                    newlyAddedPaths.add(path);
                }
                fileService.writeFileResolved(outputFile, result.currentContent());
            } catch (IOException e) {
                log.warn("Failed to write output file {}: {}", path, e.getMessage());
                continue;
            }

            context = cycleMergeTracker.track(context, path, result);

            mergeActionRegistrar.registerMerge(AGENT_ID, context, result); // TODO: here, we do not have the ultimate status, including markCyclesAsMerged and withoutSignal ROLE_MERGE, think about this
            successfullyMergedPaths.add(path);
            if (result.contributingCycles() != null) {
                contributingCyclesAccumulated.addAll(result.contributingCycles());
            }
            anyMergeRegistered = true;
        }

        // T4-BA: if the guard rejected any fragmenting file this cycle, carry the conflicts
        // forward exactly one cycle (single-cycle TTL via ContextLineageTracker) so the next
        // evolution prompt tells the LLM to consolidate the type rather than re-split it.
        // Both return paths below derive from `context`, so setting it here is sufficient.
        // The same evidence is registered in the orchestration graph so the meta-deliberation
        // and analysis agents see recurring fragmentation without scraping the behaviour log.
        if (!fragmentationConflicts.isEmpty()) {
            PackageFragmentationEvidence fragmentationEvidence =
                    new PackageFragmentationEvidence(cycleId, List.copyOf(fragmentationConflicts));
            context = context
                    .withPackageFragmentationThisCycle(true)
                    .withPackageFragmentation(fragmentationEvidence);
            mergeActionRegistrar.registerPackageFragmentationRejection(AGENT_ID, context, fragmentationEvidence);
        }

        // T1-DW: snapshot pre-merge state; used in the build gate below.
        MergeRollbackPoint rollbackPoint = originalContents.isEmpty() ? null
                : mergeRollbackService.snapshot(cycleId, originalContents, newlyAddedPaths);

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

        // T1-DY: persistent merge-state mutations (incrementGoalMergeCount, recordMergedFiles,
        // mutationMerged behaviour event) USED to fire here, BEFORE the post-merge build runs.
        // Now they are deferred to commitMergeState() which is called only when the build gate
        // confirms the merge persists on disk (not rolled back). Keeps mergeCount = 0 for
        // rolled-back cycles so T3-BL's criteria-banked early-exit doesn't fire on phantom merges.

        // T1-DX: track whether this cycle was rolled back so the trailer skips
        // markCyclesAsMerged + clears state instead of reporting phantom progress.
        boolean rolledBackThisCycle = false;

        // T4-AN-1: post-merge compile gate. Only invoke when something was actually written —
        // running the build on a SKIPPED merge would just re-validate the prior state.
        if (!successfullyMergedPaths.isEmpty() && buildExecutor.isEnabled()) {
            // T1-DW: capture previous outcome BEFORE overwriting — rollback condition needs it.
            BuildOutcome previousBuildOutcome = context.buildOutcome();
            BuildOutcome buildOutcome = buildExecutor.execute(fileBasePath, context.cycleName());
            log.info("MergeAgent T4-AN-1: post-merge build result={} ({} paths merged)",
                    buildOutcome.result(), successfullyMergedPaths.size());
            context = context.withBuildOutcome(buildOutcome);

            if (buildOutcome.result() == BuildResult.COMPILE_ERROR) {
                // T1-DW: rollback when the previous build was COMPILE_OK (or unknown for first cycle)
                // and the mutation was not a build-config file. Build-config mutations are exempt —
                // T1-DQ / T1-DV (escalation) are the correct response for those.
                boolean prevWasOk = previousBuildOutcome == null
                        || previousBuildOutcome.result() == BuildResult.COMPILE_OK;
                boolean buildConfigMutation = touchesBuildConfig(successfullyMergedPaths);

                if (prevWasOk && !buildConfigMutation && rollbackPoint != null && !rollbackPoint.isEmpty()) {
                    mergeRollbackService.rollback(rollbackPoint, outputPath, fileService);
                    // Restore pre-merge build outcome so the next cycle doesn't see a stale COMPILE_ERROR.
                    context = context.withBuildOutcome(previousBuildOutcome);
                    log.info("T1-DW: rolled back {} paths — non-build-config merge broke COMPILE_OK build",
                            successfullyMergedPaths.size());

                    // T1-EG: mark every contributing mutation's meta.json as REJECTED. Without
                    // this, the deferred ACCEPTED variant stays on disk and FileVariantCollector
                    // re-picks it next cycle — producing the same merge → build-fail → rollback
                    // loop indefinitely.
                    for (String contributing : contributingCyclesAccumulated) {
                        mutationSnapshotService.markMutationRejected(contributing);
                    }

                    // T1-DX: classify and capture forward-signal evidence so the next cycle's
                    // planner / evolution prompts see what was rolled back and why.
                    BuildErrorClassifier.Classification classification =
                            BuildErrorClassifier.classify(buildOutcome.stderrTail());
                    String stderrSummary = BlockerSubgoalInjector.summariseStderr(buildOutcome.stderrTail());
                    RolledBackEvidence evidence = new RolledBackEvidence(
                            cycleId,
                            List.copyOf(successfullyMergedPaths),
                            stderrSummary,
                            classification.kind()
                    );
                    context = context.withLastRollback(evidence);
                    rolledBackThisCycle = true;

                    if (behaviourLogger != null) {
                        behaviourLogger.mergeRolledBack(cycleId, successfullyMergedPaths,
                                stderrSummary, classification.kind().name());
                    }
                } else {
                    // T1-DM: inject BUILD_FIX subgoal — rollback not applicable (already broken
                    // before this cycle, or build-config mutation on the force-accept path).
                    // T2-CY: pass the merged paths so the BUILD_FIX subgoal description can name
                    // the actual file when stderr lacks a file-coordinate prefix.
                    blockerSubgoalInjector.injectBuildFixSubgoalIfAbsent(buildOutcome, successfullyMergedPaths,
                            context.rollbackStreak(), context.rollbackStreakKey());
                    // T1-DQ: count build-config merge attempts.
                    if (buildConfigMutation) {
                        context = context.withConsecutiveBuildFixAttempts(context.consecutiveBuildFixAttempts() + 1);
                        log.debug("T1-DQ: consecutiveBuildFixAttempts now {}", context.consecutiveBuildFixAttempts());
                    }
                }
            } else if (buildOutcome.result() == BuildResult.COMPILE_OK) {
                // T1-DR: build is fixed — clean up any stale BUILD_FIX subgoals
                blockerSubgoalInjector.clearActiveBuildFixSubgoals();
                // T1-DU: clear the BUILD_BROKEN priority surge — lineage tracker will re-emit
                // it next cycle only if a fresh COMPILE_ERROR appears.
                context = context.withoutSignal(BasicSignals.BUILD_BROKEN);
            }
        }

        // T1-DX: on the rollback path, skip markCyclesAsMerged (the cycle did NOT merge)
        // and clear cycle-level state that downstream closure helpers in CycleExecutor would
        // otherwise read as phantom progress (mutationState=ACCEPTED, merged=true, score>=0.75).
        if (rolledBackThisCycle) {
            return context
                    .withRolledBackThisCycle(true)
                    .withMerged(false)
                    .withMutationState(null)
                    .withCycleResultState(CycleResultState.ROLLED_BACK)
                    .withLastAcceptedMutationFileCount(0)
                    .withoutSignal(BasicSignals.ROLE_MERGE);
        }

        // T1-DY: commit persistent merge-state mutations only when the cycle actually persisted
        // a merge — i.e. something was written AND rollback did not undo it. Build-disabled mode
        // satisfies this trivially (rolledBackThisCycle stays false). The SKIPPED-merge branch
        // (successfullyMergedPaths empty) does not commit because there is nothing to commit.
        if (!successfullyMergedPaths.isEmpty()) {
            commitMergeState(context, successfullyMergedPaths, mergedFileCount);
        }

        // Consume merge role signal so it doesn't keep firing.
        return cycleMergeTracker
                .markCyclesAsMerged(context)
                .withLastAcceptedMutationFileCount(mergedFileCount)
                .withoutSignal(BasicSignals.ROLE_MERGE);
    }

    /**
     * T1-EH: probes every deferred ACCEPTED mutation against the current on-disk state. A
     * "deferred" mutation is one whose variants originate from a cycle other than the current
     * one — meaning its ACCEPTED decision was made by EvaluationAgent's compile probe (T1-EC)
     * in a prior cycle, against a now-stale codebase. Mutations that no longer compile are
     * marked REJECTED via {@link MutationSnapshotService#markMutationRejected} so the main
     * merge loop skips them. The probe rolls itself back regardless of result so the merge
     * loop runs against unmodified output.
     */
    private boolean reprobeDeferredMutations(CycleContextData context, Set<String> allPaths, List<String> alreadyMergedCycles) {
        if (!buildExecutor.isEnabled() || allPaths == null || allPaths.isEmpty()) return false;

        Set<String> deferredCycles = new LinkedHashSet<>();
        for (String path : allPaths) {
            List<FileVariant> variants;
            try {
                variants = fileVariantCollector.collect(path, alreadyMergedCycles, context.currentSubGoalId());
            } catch (IOException e) {
                continue;
            }
            for (FileVariant v : variants) {
                if (v.originCycleId() != null && !v.originCycleId().equals(context.cycleName())) {
                    deferredCycles.add(v.originCycleId());
                }
            }
        }

        boolean anyRejected = false;
        for (String deferredCycleId : deferredCycles) {
            if (probeAndMaybeReject(context, deferredCycleId)) {
                anyRejected = true;
            }
        }
        return anyRejected;
    }

    private boolean probeAndMaybeReject(CycleContextData context, String deferredCycleId) {
        EvolutionOutcome probeOutcome = buildProbeOutcome(deferredCycleId);
        if (probeOutcome == null) return false;

        CycleContextData probeContext = context.withEvolutionOutcome(probeOutcome);
        // T1-EH re-probe verifies that a deferred ACCEPTED mutation still compiles. The injector
        // must NOT fire here: a deterministic fix would leak into the current cycle and mask the
        // stale-mutation signal we want — MergeAgent should mark the deferred mutation REJECTED
        // cleanly rather than have it auto-patched.
        CompileProbeService.ProbeResult probe = compileProbeService.probe(probeContext, false);
        if (probe == null) return false;

        if (probe.outcome().result() == BuildResult.COMPILE_ERROR) {
            log.info("T1-EH: deferred mutation {} fails re-probe — marking REJECTED. stderr: {}",
                    deferredCycleId, probe.outcome().stderrTail());
            mutationSnapshotService.markMutationRejected(deferredCycleId);
            return true;
        }

        // COMPILE_OK leaves files on disk — roll back so the actual merge runs against the
        // original output state and produces normal merge semantics.
        if (probe.rollbackPoint() != null) {
            compileProbeService.rollback(probe.rollbackPoint(), context.outputPath());
        }
        return false;
    }

    private EvolutionOutcome buildProbeOutcome(String cycleId) {
        try {
            List<FileInfo> files = fileService.listFiles("mutations/" + cycleId);
            List<FileMutation> mutations = new java.util.ArrayList<>();
            for (FileInfo info : files) {
                String relPath = info.getName().replace('\\', '/');
                if ("meta.json".equals(relPath)) continue;
                String content = fileService.readString("mutations/" + cycleId + "/" + relPath);
                mutations.add(new FileMutation(relPath, content));
            }
            if (mutations.isEmpty()) return null;
            return new EvolutionOutcome(mutations, List.of(), "T1-EH re-probe of " + cycleId, "");
        } catch (IOException e) {
            log.warn("T1-EH: could not assemble re-probe outcome for cycle {}: {}", cycleId, e.getMessage());
            return null;
        }
    }

    /**
     * T1-DY: persistent goal-entity mutations gated on the post-merge build gate confirming
     * the merge survives. Called only when {@code !rolledBackThisCycle && !successfullyMergedPaths.isEmpty()}.
     * <p>
     * Was previously called BEFORE the build gate (MergeAgent:175-189), causing rolled-back
     * cycles to leave behind {@code mergeCount > 0} and {@code recordMergedFiles} entries that
     * survived rollback and tricked T3-BL's criteria-banked early-exit into firing on phantom
     * progress (observed 2026-05-07 01:38 run, ended GOAL_FULFILLED with empty output/).
     */
    private void commitMergeState(CycleContextData context, List<String> mergedPaths, int mergedFileCount) {
        if (mergedFileCount > 0) {
            goalPlannerService.incrementGoalMergeCount(context.currentSubGoalId());
        }
        goalPlannerService.recordMergedFiles(context.currentSubGoalId(), mergedPaths);
        if (behaviourLogger != null) {
            behaviourLogger.mutationMerged(
                    context.cycleName(),
                    context.lastEvolutionFingerprint(),
                    mergedPaths
            );
        }
    }

    // T1-DQ: check whether any of the successfully merged paths is a build-config file.
    private static boolean touchesBuildConfig(List<String> mergedPaths) {
        if (mergedPaths == null) return false;
        return mergedPaths.stream().anyMatch(p -> {
            String name = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
            return "pom.xml".equals(name) || name.startsWith("build.gradle") || name.startsWith("settings.gradle");
        });
    }

    /**
     * T2-DB: returns true iff the current cycle's mutation includes a path that addresses the
     * prior-cycle rollback. A mutation addresses the rollback when it overlaps either:
     * <ul>
     *   <li>the kind-derived repair target ({@link BuildFixTargets#preferredRepairTarget} —
     *       e.g. {@code "pom.xml"} for {@code MISSING_POM}), or</li>
     *   <li>any of the rolled-back paths from the previous cycle.</li>
     * </ul>
     * Matching is by basename to tolerate path-prefix variations (absolute vs relative,
     * {@code /output/} segment present or not). When the current mutation is empty or null
     * (no fresh evolution this cycle), we treat that as "not addressing" so we skip — there
     * is nothing new to merge anyway.
     */
    static boolean mutationAddressesRollback(EvolutionOutcome evolutionOutcome, RolledBackEvidence rb) {
        if (evolutionOutcome == null) return false;
        Set<String> mutationBasenames = collectBasenames(evolutionOutcome);
        if (mutationBasenames.isEmpty()) return false;

        String kindTarget = BuildFixTargets.preferredRepairTarget(rb.errorKind(), rb.restoredPaths());
        if (kindTarget != null && mutationBasenames.contains(basenameOf(kindTarget))) {
            return true;
        }

        if (rb.restoredPaths() != null) {
            for (String p : rb.restoredPaths()) {
                if (p == null || p.isBlank()) continue;
                if (mutationBasenames.contains(basenameOf(p))) return true;
            }
        }
        return false;
    }

    private static Set<String> collectBasenames(EvolutionOutcome evolutionOutcome) {
        Set<String> out = new java.util.HashSet<>();
        List<FileMutation> added = evolutionOutcome.addedOrChangedFiles();
        if (added != null) {
            for (FileMutation fm : added) {
                if (fm == null || fm.path() == null) continue;
                out.add(basenameOf(fm.path()));
            }
        }
        List<String> deleted = evolutionOutcome.deletedFiles();
        if (deleted != null) {
            for (String p : deleted) {
                if (p == null || p.isBlank()) continue;
                out.add(basenameOf(p));
            }
        }
        return out;
    }

    private static String basenameOf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * T4-BA: scans the output tree for a {@code .java} file declaring the same top-level type
     * as {@code newPath} but under a different package directory. Returns the conflicting path
     * (relative to {@code outputPath}) or {@code null} when none exists. {@code newPath} is
     * also relative to {@code outputPath}. The top-level type name is taken from the filename
     * (Java requires a public top-level class to match its file name).
     */
    private String findPackageFragmentation(String outputPath, String newPath) {
        if (newPath == null || !newPath.endsWith(".java")) return null;
        String typeName = typeNameOf(newPath);
        String newParent = parentDirOf(newPath);

        List<String> existing;
        try {
            existing = fileService.listAllRelativeFilePathsUnder(outputPath);
        } catch (IOException e) {
            log.warn("T4-BA: could not scan output tree for fragmentation check: {}", e.getMessage());
            return null;
        }
        if (existing == null) return null;

        for (String p : existing) {
            if (p == null || !p.endsWith(".java")) continue;
            if (p.equals(newPath)) continue;
            if (!typeName.equals(typeNameOf(p))) continue;
            if (!newParent.equals(parentDirOf(p))) {
                return p;
            }
        }
        return null;
    }

    private static String typeNameOf(String path) {
        String base = basenameOf(path);
        return base.endsWith(".java") ? base.substring(0, base.length() - ".java".length()) : base;
    }

    private static String parentDirOf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
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
