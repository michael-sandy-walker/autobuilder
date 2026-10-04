package com.walpro.autobuilder.core.checkpoint;

import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * T4-DQ slice 4: exploring more than one path forward from the same point, and choosing between
 * them on what they achieved rather than on which one happened to be last.
 *
 * <p><b>Sequential, which is the whole reason this is reachable.</b> {@code T4-AO} wanted
 * speculative exploration and was blocked on there being one GPU: two paths cannot be run at once.
 * A rewind removes that constraint — explore one path, return to the fork point, explore another,
 * compare. The cost is wall clock, not hardware.
 *
 * <p><b>What makes two branches from one point actually differ.</b> Nothing here randomises
 * anything; prompts go out at temperature zero, so an identical restored state asks identical
 * questions. The divergence is structural and was already built in slice 3: goals planned on the
 * abandoned path are rewound OUT of the live tree but stay in the graph's description corpus,
 * which is what the planner's duplicate check reads. The second branch therefore cannot re-propose
 * the first branch's plan and has to decompose the goal some other way.
 *
 * <p><b>The ranking rule, fixed before it was measured</b>
 * ([[project_coverage_guard_experiment]]'s discipline: state the decision rule in advance):
 * <ol>
 *   <li>more subgoals completed WITH a merge — the convergence brake's own root-progress measure,
 *       and the one thing the planner cannot inflate by re-decomposing;</li>
 *   <li>then more merges landed;</li>
 *   <li>then a tip that compiles over one that does not;</li>
 *   <li>then the later branch, which knows everything the earlier one did.</li>
 * </ol>
 * Merge RATE is recorded and deliberately not ranked on: a two-cycle branch that merged once would
 * outrank a thirty-cycle branch that merged ten times, which is an artefact of length rather than
 * a statement about the path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BranchExplorer {

    private final BranchLedger branchLedger;
    private final CheckpointService checkpointService;
    private final RewindService rewindService;
    private final GoalPlannerService goalPlannerService;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    /**
     * Seal the branch the loop is on and open a new one from {@code forkPoint}. The caller has
     * already rewound; this records what was explored so the next fork does not re-walk it.
     */
    public ExplorationBranch forkAt(CheckpointRecord forkPoint, String atCycleId, String label) {
        if (false) return null;
        sealCurrent(atCycleId);
        ExplorationBranch branch = new ExplorationBranch(
                nextBranchId(), label, forkPoint.cycleId(), null, null, null, java.time.Instant.now());
        branchLedger.write(branch);
        log.warn("T4-DQ: branch {} opened from {} — {}", branch.id(), forkPoint.cycleId(), label);
        if (behaviourLogger != null) {
            behaviourLogger.branchForked(atCycleId, branch.id(), forkPoint.cycleId(), label);
        }
        return branch;
    }

    /**
     * Seal whatever branch is open, measuring what it achieved. Opens the implicit {@code main}
     * branch first when the run has never forked — the original path is a branch too, and a
     * comparison that could not see it would only ever be able to choose between detours.
     */
    public Optional<ExplorationBranch> sealCurrent(String atCycleId) {
        if (false) return Optional.empty();
        ExplorationBranch open = branchLedger.openBranch().orElseGet(this::openMainRetrospectively);
        if (open == null) return Optional.empty();
        ExplorationBranch sealed = open.sealedAt(atCycleId, measure(open, atCycleId));
        branchLedger.write(sealed);
        log.info("T4-DQ: branch {} sealed at {} — {} cycle(s), {} merge(s), {} subgoal(s) completed with a merge, tip={}",
                sealed.id(), atCycleId, sealed.outcome().cycles(), sealed.outcome().merges(),
                sealed.outcome().subgoalsCompletedWithMerge(), sealed.outcome().tipCycleId());
        if (behaviourLogger != null) {
            behaviourLogger.branchSealed(atCycleId, sealed.id(), sealed.outcome().cycles(),
                    sealed.outcome().merges(), sealed.outcome().subgoalsCompletedWithMerge(),
                    sealed.outcome().tipCycleId());
        }
        return Optional.of(sealed);
    }

    /**
     * The checkpoint to fork from next: the newest point that landed work and that no branch has
     * been forked from yet. Empty when every such point has been tried, which is the honest end of
     * exploration rather than a loop.
     */
    public Optional<CheckpointRecord> nextUntriedForkPoint() {
        // The candidates an automatic rewind is allowed: still open, and by default this run's own.
        for (CheckpointRecord record : checkpointService.autoRewindCandidates()) {
            if (!record.merged()) continue;
            if (branchLedger.alreadyForkedFrom(record.cycleId())) continue;
            return Optional.of(record);
        }
        return Optional.empty();
    }

    /** The sealed branch that got furthest, by the rule in this class's documentation. */
    public Optional<ExplorationBranch> mostAdvancedSealedBranch() {
        return branchLedger.sealedBranches().stream()
                .filter(b -> b.outcome() != null)
                .max(Comparator
                        .comparingInt((ExplorationBranch b) -> b.outcome().subgoalsCompletedWithMerge())
                        .thenComparingInt(b -> b.outcome().merges())
                        .thenComparing(b -> b.outcome().tipCompiles())
                        .thenComparing(ExplorationBranch::id));
    }

    /**
     * Return to the tip of the branch that got furthest, when that is not the branch the loop is
     * already on.
     *
     * <p>This is what branching is FOR. Without it a run that explored three paths ends wherever
     * the third one happened to leave it, and the goal state written to disk — which a
     * {@code T4-CD} resume continues from — is the last path tried rather than the best one found.
     * {@code T4-CA} already picks the best TREE to ship; nothing picked the best state to carry on
     * from.
     *
     * @param atCycleId the cycle the decision is taken in
     * @return the rewind that moved the run, or empty when it is already on the most advanced path
     */
    public Optional<RewindService.Result> adoptMostAdvanced(String atCycleId) {
        if (false) return Optional.empty();
        List<ExplorationBranch> sealed = branchLedger.sealedBranches();
        if (sealed.size() < 2) return Optional.empty();
        Optional<ExplorationBranch> best = mostAdvancedSealedBranch();
        if (best.isEmpty()) return Optional.empty();
        ExplorationBranch target = best.get();
        ExplorationBranch current = sealed.get(sealed.size() - 1);
        if (target.id().equals(current.id())) {
            log.info("T4-DQ: the branch the run is on ({}) is already the most advanced — nothing to adopt.",
                    current.id());
            return Optional.empty();
        }
        String tip = target.outcome() != null ? target.outcome().tipCycleId() : null;
        if (tip == null) return Optional.empty();

        RewindService.Result result = rewindService.rewindTo(
                tip, CacheMode.FAITHFUL, "branch-adoption", atCycleId);
        if (!result.performed()) {
            log.warn("T4-DQ: branch {} got furthest but its tip {} could not be adopted ({}) — staying on {}.",
                    target.id(), tip, result.refusedBecause(), current.id());
            return Optional.empty();
        }
        log.warn("T4-DQ: adopted branch {} (tip {}): {} subgoal(s) completed with a merge and {} merge(s), "
                        + "against {} and {} on the branch the run was leaving ({}).",
                target.id(), tip, target.outcome().subgoalsCompletedWithMerge(), target.outcome().merges(),
                current.outcome() != null ? current.outcome().subgoalsCompletedWithMerge() : 0,
                current.outcome() != null ? current.outcome().merges() : 0, current.id());
        if (behaviourLogger != null) {
            behaviourLogger.branchAdopted(atCycleId, target.id(), current.id(), tip,
                    target.outcome().subgoalsCompletedWithMerge(), target.outcome().merges());
        }
        return Optional.of(result);
    }

    /** A readable one-line comparison of everything explored, for the halt log. */
    public String readout() {
        List<ExplorationBranch> all = branchLedger.branches();
        if (all.isEmpty()) return "no branches explored";
        StringBuilder sb = new StringBuilder();
        for (ExplorationBranch b : all) {
            ExplorationBranch.BranchOutcome o = b.outcome() != null ? b.outcome()
                    : ExplorationBranch.BranchOutcome.NONE;
            sb.append(sb.isEmpty() ? "" : " | ")
                    .append(b.id())
                    .append(b.open() ? " (open)" : "")
                    .append(" from=").append(b.forkedFromCycleId() == null ? "start" : b.forkedFromCycleId())
                    .append(" cycles=").append(o.cycles())
                    .append(" merges=").append(o.merges())
                    .append(" rootProgress=").append(o.subgoalsCompletedWithMerge());
        }
        return sb.toString();
    }

    /**
     * Measure a branch over the checkpoints taken while it was live. The cycle range is
     * {@code (forkedFrom, atCycleId]} — cycle ids are monotonic, so the range IS the membership
     * and nothing needed stamping.
     */
    private ExplorationBranch.BranchOutcome measure(ExplorationBranch branch, String atCycleId) {
        String from = branch.forkedFromCycleId();
        int cycles = 0;
        int merges = 0;
        String tip = null;
        String tipBuild = null;
        boolean tipEntryPoint = false;
        for (String cycleId : checkpointService.listCheckpointCycles()) {
            if (from != null && cycleId.compareTo(from) <= 0) continue;
            if (atCycleId != null && cycleId.compareTo(atCycleId) > 0) continue;
            Optional<CheckpointRecord> record = checkpointService.read(cycleId);
            if (record.isEmpty()) continue;
            cycles++;
            if (record.get().merged()) {
                merges++;
                tip = cycleId;
            }
            if (tip == null) tip = cycleId;
        }
        if (tip != null) {
            var state = checkpointService.snapshotStateOf(tip);
            tipBuild = state.map(com.walpro.autobuilder.core.persistence.MutationSnapshotService.SnapshotState::buildResult)
                    .orElse(null);
            tipEntryPoint = state
                    .map(com.walpro.autobuilder.core.persistence.MutationSnapshotService.SnapshotState::entryPointPresent)
                    .orElse(false);
        }
        int rootProgress = 0;
        try {
            rootProgress = goalPlannerService.countCompletedSubgoalsWithMerges();
        } catch (Exception e) {
            log.debug("T4-DQ: root-progress measure unavailable while sealing {}: {}",
                    branch.id(), e.getMessage());
        }
        return new ExplorationBranch.BranchOutcome(cycles, merges, rootProgress, tip, tipBuild, tipEntryPoint);
    }

    /**
     * The run has been working a path since it started without ever calling it a branch. Record it
     * now, so the first fork has something to compare against.
     */
    private ExplorationBranch openMainRetrospectively() {
        ExplorationBranch main = new ExplorationBranch(ExplorationBranch.MAIN,
                "the run's original path", null, null, null, null, java.time.Instant.now());
        branchLedger.write(main);
        return main;
    }

    private String nextBranchId() {
        return "branch-" + (branchLedger.branches().size());
    }
}
