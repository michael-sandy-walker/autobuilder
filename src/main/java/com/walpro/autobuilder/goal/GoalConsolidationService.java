package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.CycleResultState;
import com.walpro.autobuilder.graph.evaluation.CriterionResultEntity;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * T4-AJ Part B: catch-up consolidation pass over the goal tree. Injection-time dedup only checks
 * a candidate against the tree at add-time; two near-duplicate goals worded differently each pass
 * dedup and both go ACTIVE — and nothing ever revisits them. This service revisits them: when the
 * loop stagnates or the ACTIVE-leaf count grows past a threshold, it runs the shared equivalence
 * reviewer ({@link GoalAdoptionService#findEquivalent}) over the bloat candidates and folds each
 * equivalent pair onto the goal that is kept; the other goal transitions to {@code SUBSUMED/COVERED} carrying
 * its acceptance criteria and {@code mergeCount} to the kept goal (T2-DR banking — the carried criteria
 * keep blocking root closure until the kept goal satisfies them, so folding never silently drops
 * spec coverage).
 *
 * <p>Two fold cases, motivated by the 2026-06-10 terminal states:
 * <ul>
 *   <li><b>two ACTIVE near-duplicates</b> — overlapping duplicates strangling the planner
 *       (16:06 run: ~5 near-duplicate retrieval/injection subgoals among 12 ACTIVE at stall).</li>
 *   <li><b>a GIVEN_UP leaf covered by ACTIVE or COMPLETED work</b> — a GIVEN_UP leaf with unmet criteria whose work
 *       an equivalent goal carried out elsewhere blocks T2-DR root closure forever (18:16 run:
 *       root GIVEN_UP with 13 such blockers despite 48 merges). Folding it to SUBSUMED lets the
 *       evidence ledger see the work as covered; criteria are carried only onto ACTIVE kept goals
 *       (a COMPLETED goal already banked its criteria as the work that made it equivalent).</li>
 * </ul>
 *
 * <p>Cost guard: candidates below a string-similarity floor never reach the LLM (relevance
 * pruning with measured margin — real duplicates scored 0.69-0.81 on the string metric vs the
 * 0.5 floor), and each pass stops after {@code max-reviewer-calls} LLM calls. There is
 * deliberately no fixed-size candidate cap — see {@link GoalAdoptionService}. Ancestor/descendant pairs and BUILD_FIX subgoals are
 * excluded — a decomposition child names a slice of its parent's work by design, and BUILD_FIX
 * descriptions are diagnostic-shaped, owned by the injector's own dedup.
 *
 * <p>Fallback invariant: reviewer unavailable, LLM failure, or below-threshold confidence —
 * the pass no-ops and the tree is left as-is. Correctness never depends on consolidation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalConsolidationService {

    private final GoalPlannerService goalPlannerService;
    private final GoalAdoptionService goalAdoptionService;
    private final GoalSimilarityService goalSimilarityService;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    @Setter
    @Value("${autobuilder.goal.consolidation.enabled:true}")
    private boolean enabled = true;

    /** Consecutive STAGNATING/ROLLED_BACK cycles before a pass is attempted. */
    @Setter
    @Value("${autobuilder.goal.consolidation.stagnation-streak:3}")
    private int stagnationStreakTrigger = 3;

    /** ACTIVE-leaf count above which a pass is attempted regardless of stagnation. */
    @Setter
    @Value("${autobuilder.goal.consolidation.active-leaf-threshold:10}")
    private int activeLeafThreshold = 10;

    /** Reviewer LLM call budget per pass. */
    @Setter
    @Value("${autobuilder.goal.consolidation.max-reviewer-calls:5}")
    private int maxReviewerCalls = 5;

    /** Similarity floor below which a pair is not worth a reviewer call. */
    @Setter
    @Value("${autobuilder.goal.consolidation.similarity-floor:0.5}")
    private double similarityFloor = 0.5;

    private int stagnatingStreak = 0;
    private int cyclesObserved = 0;
    private boolean mergeSeenThisRun = false;

    /** Pairs whose proposed match was refused under role swap — never re-reviewed this run. */
    private final Set<String> unconfirmedPairs = new HashSet<>();

    /**
     * Goals that already took part in one fold this run, either side. Frozen thereafter:
     * absorbed criteria make a goal's criteria set span several components, which makes it
     * look equivalent to everything it overlaps — observed 2026-06-11 22:41-22:42 as a
     * runaway (3 folds into one goal, then that goal itself subsumed with 13 carried
     * criteria, then a feature endpoint folded into a handler goal). One fold per goal per
     * run stops both the cascade and the grab-bag growth structurally.
     */
    private final Set<String> foldParticipants = new HashSet<>();

    /**
     * Post-cycle hook. Cheap unless a trigger fires; never throws.
     */
    public void maybeConsolidate(CycleContextData context) {
        if (!enabled || context == null) return;
        if (context.hasSignal(BasicSignals.GOAL_EXHAUSTED) || context.hasSignal(BasicSignals.SYSTEM_PAUSED)) {
            return; // halting — tree mutation now would race the terminal-state report
        }

        CycleResultState state = context.cycleResultState();
        boolean stagnating = state == CycleResultState.STAGNATING || state == CycleResultState.ROLLED_BACK;
        stagnatingStreak = stagnating ? stagnatingStreak + 1 : 0;
        cyclesObserved++;
        if (context.merged()) mergeSeenThisRun = true;

        try {
            int activeLeaves = goalPlannerService.getRemainingGoals().size();
            boolean streakTrigger = stagnatingStreak >= stagnationStreakTrigger;
            // T2-EC: a fresh decomposition is not bloat — leaf count alone only triggers once
            // the tree has had a chance to do work: a merge landed, or one observed cycle per
            // active leaf has passed (the loop works one leaf at a time, so an N-leaf tree
            // needs ~N cycles before "no merge yet" means stagnation rather than freshness).
            // Grace scales with the tree the trigger measures — no fixed cycle constant.
            boolean treeHasHistory = mergeSeenThisRun || cyclesObserved >= activeLeaves;
            boolean bloatTrigger = activeLeaves > activeLeafThreshold && treeHasHistory;
            if (!streakTrigger && !bloatTrigger) {
                if (activeLeaves > activeLeafThreshold && !treeHasHistory) {
                    log.info("T2-EC: leaf-count trigger suppressed — fresh tree ({} leaves, cycle {}, no merge yet)",
                            activeLeaves, cyclesObserved);
                }
                return;
            }

            log.info("T4-AJ Part B: consolidation pass (trigger={}, activeLeaves={}, stagnatingStreak={})",
                    streakTrigger ? "stagnation-streak" : "active-leaf-count", activeLeaves, stagnatingStreak);
            stagnatingStreak = 0;
            runPass(context.cycleName());
        } catch (Exception e) {
            log.warn("T4-AJ Part B: consolidation pass failed ({}) — tree left as-is", e.getMessage());
        }
    }

    private void runPass(String cycleId) {
        List<GoalEntity> active = foldableActives().stream()
                .filter(g -> !foldParticipants.contains(g.getId()))
                .toList();
        int callsLeft = maxReviewerCalls;
        Set<String> foldedIds = new HashSet<>();

        // Fold each pair of ACTIVE near-duplicates onto one of the two. Both goals of a fold
        // are frozen for the rest of the run (see foldParticipants).
        for (GoalEntity subject : active) {
            if (callsLeft <= 0) break;
            if (foldedIds.contains(subject.getId()) || foldParticipants.contains(subject.getId())) continue;
            List<GoalEntity> candidates = active.stream()
                    .filter(c -> !c.getId().equals(subject.getId()))
                    .filter(c -> !foldedIds.contains(c.getId()))
                    .filter(c -> !foldParticipants.contains(c.getId()))
                    .filter(c -> !related(subject, c))
                    .filter(c -> goalSimilarityService.similarity(
                            subject.getDescription(), c.getDescription()) >= similarityFloor)
                    .toList();
            if (candidates.isEmpty()) continue;

            callsLeft--;
            Optional<GoalAdoptionService.ReviewedMatch> match =
                    goalAdoptionService.findEquivalent(subject, candidates, unconfirmedPairs);
            if (match.isEmpty()) continue;

            GoalEntity matched = match.get().goal();
            GoalEntity keptGoal = chooseGoalToKeep(subject, matched);
            GoalEntity duplicate = keptGoal == subject ? matched : subject;
            fold(cycleId, keptGoal, duplicate, match.get().confidence(), "active-duplicate", true);
            foldedIds.add(duplicate.getId());
            foldParticipants.add(duplicate.getId());
            foldParticipants.add(keptGoal.getId());
        }

        // Fold GIVEN_UP leaves with unmet criteria whose work an equivalent ACTIVE or
        // COMPLETED goal carried out. These are exactly the T2-DR root-closure blockers.
        List<GoalEntity> doneOrActive = new ArrayList<>(active.stream()
                .filter(g -> !foldedIds.contains(g.getId())).toList());
        doneOrActive.addAll(goalPlannerService.getCompletedGoals().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL && g.getDescription() != null)
                .toList());
        for (GoalEntity givenUp : blockedGivenUps()) {
            if (callsLeft <= 0) break;
            if (foldParticipants.contains(givenUp.getId())) continue;
            List<GoalEntity> candidates = doneOrActive.stream()
                    .filter(c -> !foldParticipants.contains(c.getId()))
                    .filter(c -> !related(givenUp, c))
                    .filter(c -> goalSimilarityService.similarity(
                            givenUp.getDescription(), c.getDescription()) >= similarityFloor)
                    .toList();
            if (candidates.isEmpty()) continue;

            callsLeft--;
            Optional<GoalAdoptionService.ReviewedMatch> match =
                    goalAdoptionService.findEquivalent(givenUp, candidates, unconfirmedPairs);
            if (match.isEmpty()) continue;

            GoalEntity keptGoal = match.get().goal();
            boolean carryCriteria = keptGoal.getStatus() == GoalStatus.ACTIVE;
            fold(cycleId, keptGoal, givenUp, match.get().confidence(), "given-up-covered", carryCriteria);
            foldParticipants.add(givenUp.getId());
            foldParticipants.add(keptGoal.getId());
        }
    }

    /** ACTIVE subgoals eligible for folding: described, not BUILD_FIX-tagged. */
    private List<GoalEntity> foldableActives() {
        return goalPlannerService.getRemainingGoals().stream()
                .filter(g -> g.getDescription() != null)
                .filter(g -> !isBuildFix(g))
                .toList();
    }

    /** GIVEN_UP subgoals that currently block T2-DR root closure (unmet or undeclared criteria). */
    private List<GoalEntity> blockedGivenUps() {
        return goalPlannerService.getAbandonedGoals().stream()
                .filter(g -> g.getType() == GoalType.SUBGOAL && g.getDescription() != null)
                .filter(g -> !isBuildFix(g))
                .filter(g -> goalPlannerService.countUnmetCriteria(g.getId()) > 0)
                .toList();
    }

    private static boolean isBuildFix(GoalEntity g) {
        return g.getMetadata() != null
                && BlockerTypes.BUILD_FIX.equals(g.getMetadata().get(BlockerTypes.METADATA_KEY));
    }

    /** True when one goal is an ancestor of the other — decomposition, not duplication. */
    private boolean related(GoalEntity a, GoalEntity b) {
        return isAncestor(a.getId(), b) || isAncestor(b.getId(), a);
    }

    private boolean isAncestor(String ancestorId, GoalEntity g) {
        String parentId = g.getParentId();
        int depthGuard = 0;
        while (parentId != null && depthGuard++ < 50) {
            if (parentId.equals(ancestorId)) return true;
            GoalEntity parent = goalPlannerService.getGoalById(parentId);
            parentId = parent != null ? parent.getParentId() : null;
        }
        return false;
    }

    /**
     * Which of an equivalent pair stays ACTIVE: the current subgoal (don't yank work in
     * progress), then the goal with more merged work behind it, then the subject (stable choice).
     */
    private GoalEntity chooseGoalToKeep(GoalEntity a, GoalEntity b) {
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (current != null) {
            if (a.getId().equals(current.getId())) return a;
            if (b.getId().equals(current.getId())) return b;
        }
        int mergesA = goalPlannerService.getGoalMetadataInt(a, "mergeCount");
        int mergesB = goalPlannerService.getGoalMetadataInt(b, "mergeCount");
        return mergesB > mergesA ? b : a;
    }

    private void fold(String cycleId, GoalEntity keptGoal, GoalEntity subsumedGoal, double confidence,
                      String kind, boolean carryCriteria) {
        int criteriaCarried = carryCriteria ? carryCriteria(subsumedGoal, keptGoal) : 0;
        carryMergeCount(subsumedGoal, keptGoal);
        goalPlannerService.transitionGoal(
                subsumedGoal.getId(),
                GoalStatus.SUBSUMED,
                "consolidated: same work as " + keptGoal.getId() + " (reviewer confidence " + confidence + ")",
                SubsumptionReason.COVERED);
        log.info("T4-AJ Part B: folded [{}] '{}' into [{}] '{}' (kind={}, confidence={}, criteriaCarried={})",
                subsumedGoal.getId(), truncate(subsumedGoal.getDescription()), keptGoal.getId(),
                truncate(keptGoal.getDescription()), kind, confidence, criteriaCarried);
        if (behaviourLogger != null) {
            try {
                behaviourLogger.goalConsolidation(cycleId, keptGoal.getId(), subsumedGoal.getId(),
                        confidence, kind, criteriaCarried);
            } catch (Exception e) {
                log.debug("T4-AJ Part B: goal_consolidation emit failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Appends the subsumed goal's acceptance criteria to the kept goal's, deduped by normalized
     * criterion identity (T4-BP-2 {@link CriterionResultEntity#keyOf}). Existing pass-status
     * entries keep their indices because criteria are only ever appended.
     *
     * @return number of criteria actually carried over
     */
    private int carryCriteria(GoalEntity subsumedGoal, GoalEntity keptGoal) {
        List<String> subsumedCriteria = criteriaOf(subsumedGoal);
        if (subsumedCriteria.isEmpty()) return 0;
        List<String> keptCriteria = criteriaOf(keptGoal);

        Set<String> known = new HashSet<>();
        for (String c : keptCriteria) known.add(CriterionResultEntity.keyOf(c));

        LinkedHashSet<String> merged = new LinkedHashSet<>(keptCriteria);
        int carried = 0;
        for (String c : subsumedCriteria) {
            if (known.add(CriterionResultEntity.keyOf(c)) && merged.add(c)) carried++;
        }
        if (carried > 0) {
            goalPlannerService.setAcceptanceCriteria(keptGoal.getId(), new ArrayList<>(merged));
        }
        return carried;
    }

    private void carryMergeCount(GoalEntity subsumedGoal, GoalEntity keptGoal) {
        int subsumedMerges = goalPlannerService.getGoalMetadataInt(subsumedGoal, "mergeCount");
        if (subsumedMerges <= 0) return;
        int keptMerges = goalPlannerService.getGoalMetadataInt(keptGoal, "mergeCount");
        keptGoal.getMetadata().put("mergeCount", keptMerges + subsumedMerges);
        goalPlannerService.saveGoalMetadata(keptGoal);
    }

    private static List<String> criteriaOf(GoalEntity g) {
        if (g.getMetadata() == null) return List.of();
        Object raw = g.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> l) || l.isEmpty()) return List.of();
        return l.stream().filter(o -> o instanceof String).map(o -> (String) o).toList();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
