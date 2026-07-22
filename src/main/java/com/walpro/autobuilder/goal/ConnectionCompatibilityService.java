package com.walpro.autobuilder.goal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * T4-CN: node-compatibility model — answers whether two graph nodes can connect, as what,
 * and with which consequences, BEFORE any connection is offered. One model, three consumers:
 * the workbench's live feedback, a headless pre-check endpoint, and eventually the loop's own
 * consolidation machinery (recorded-not-applied first).
 *
 * <p>Slice 1 covers ONE connection kind: {@link ConnectionKind#GOAL_MERGE} (goal↔goal fold).
 * Every rule delegates to the service that already owns it — fold semantics to
 * {@link GoalConsolidationService}, similarity to {@link GoalSimilarityService}, tree lookups
 * to {@link GoalPlannerService} — so the answer can never drift from what the fold would do.
 * Further kinds (subgoal re-bind, attempt attribution, reopen link) are their own slices; the
 * connection-kind table lives in the T4-CN design note.
 *
 * <p>The check answers, it never acts: a refusal is a feedback payload, not an error. Unknown
 * ids answer as "cross-lineage gated" rather than 404 — the in-memory tree is single-lineage
 * by construction (T1-FM ownership gate), so resolving in the current tree IS the slice-1
 * lineage rule.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionCompatibilityService {

    public enum ConnectionKind { GOAL_MERGE }

    /** The check's answer: the sketch shape {@code {kind, allowed, reason, consequences}}. */
    public record ConnectionCheckResult(
            ConnectionKind kind,
            boolean allowed,
            String reason,
            Double similarity,
            ConnectionConsequences consequences) {

        static ConnectionCheckResult refused(String reason) {
            return new ConnectionCheckResult(ConnectionKind.GOAL_MERGE, false, reason, null, null);
        }

        static ConnectionCheckResult refused(String reason, double similarity) {
            return new ConnectionCheckResult(ConnectionKind.GOAL_MERGE, false, reason, similarity, null);
        }
    }

    /**
     * Dry-run of what the fold would actually do — computed with the same code paths the real
     * fold uses, because honest feedback about the merge's effect is the design's point.
     * {@code unmetCriteriaOnAbsorbed} == 0 on a given-up fold means the fold would not
     * unblock root closure (the given-up goal wasn't a T2-DR blocker).
     */
    public record ConnectionConsequences(
            String keptGoalId,
            String absorbedGoalId,
            String foldCase,
            String absorbedStatusAfter,
            int criteriaCarried,
            int criteriaAlreadyCovered,
            int mergeCountAfter,
            int unmetCriteriaOnAbsorbed) {}

    private final GoalPlannerService goalPlannerService;
    private final GoalConsolidationService goalConsolidationService;
    private final GoalSimilarityService goalSimilarityService;

    /**
     * Checks whether the goals behind the two ids can connect as a goal↔goal merge.
     * Rule order mirrors the connection-kind table: identity → resolvability → structural
     * refusals → status-pair table → similarity floor → allowed with dry-run consequences.
     */
    public ConnectionCheckResult checkConnection(String nodeAId, String nodeBId) {
        if (nodeAId != null && nodeAId.equals(nodeBId)) {
            return ConnectionCheckResult.refused("a goal cannot merge with itself");
        }
        GoalEntity a = nodeAId == null ? null : goalPlannerService.getGoalById(nodeAId);
        GoalEntity b = nodeBId == null ? null : goalPlannerService.getGoalById(nodeBId);
        if (a == null || b == null) {
            return ConnectionCheckResult.refused(
                    "node " + (a == null ? nodeAId : nodeBId) + " is not in the current goal tree — "
                    + "cross-lineage/cross-run connections are gated on T4-CJ slice 2");
        }
        if (a.getType() == GoalType.GOAL || b.getType() == GoalType.GOAL) {
            return ConnectionCheckResult.refused(
                    "root goals are never folded — a root absorbs work through its subtree, not by subsumption");
        }
        if (a.getDescription() == null || b.getDescription() == null) {
            return ConnectionCheckResult.refused(
                    "fold equivalence is description-based — an undescribed goal cannot be reviewed");
        }
        if (GoalConsolidationService.isBuildFix(a) || GoalConsolidationService.isBuildFix(b)) {
            return ConnectionCheckResult.refused(
                    "BUILD_FIX subgoals are excluded from folding — their dedup is owned by the injector");
        }
        if (a.getStatus() == GoalStatus.SUBSUMED || b.getStatus() == GoalStatus.SUBSUMED) {
            return ConnectionCheckResult.refused(
                    "already folded into other work — a goal takes part in at most one fold");
        }
        if (goalConsolidationService.related(a, b)) {
            return ConnectionCheckResult.refused(
                    "ancestor and descendant — a decomposition child names a slice of its parent's work, not a duplicate");
        }

        FoldPair pair = classifyStatusPair(a, b);
        if (pair == null) {
            return ConnectionCheckResult.refused(statusPairRefusal(a, b));
        }

        double similarity = goalSimilarityService.similarity(a.getDescription(), b.getDescription());
        double floor = goalConsolidationService.getSimilarityFloor();
        if (similarity < floor) {
            return ConnectionCheckResult.refused(
                    String.format("descriptions are unrelated (similarity %.2f below the %.2f reviewer floor)",
                            similarity, floor),
                    similarity);
        }

        GoalEntity kept = pair.kept();
        GoalEntity absorbed = kept == a ? b : a;
        GoalConsolidationService.CriteriaCarryPlan plan = pair.carryCriteria()
                ? GoalConsolidationService.planCriteriaCarry(absorbed, kept)
                : new GoalConsolidationService.CriteriaCarryPlan(java.util.List.of(), 0, 0);
        int mergeCountAfter = goalPlannerService.getGoalMetadataInt(kept, "mergeCount")
                + goalPlannerService.getGoalMetadataInt(absorbed, "mergeCount");
        int unmetOnAbsorbed = goalPlannerService.countUnmetCriteria(absorbed.getId());

        String grade = similarity >= GoalSimilarityService.SIMILARITY_THRESHOLD
                ? String.format("duplicate-grade similarity %.2f (at or above the loop's own dedup threshold)", similarity)
                : String.format("reviewer-grade similarity %.2f — the loop would confirm this pair with its "
                        + "symmetric equivalence review before folding; operator judgment stands in for that review "
                        + "(the reviewer's known over-match ceiling applies)", similarity);
        return new ConnectionCheckResult(
                ConnectionKind.GOAL_MERGE,
                true,
                pair.foldCase() + " fold; " + grade,
                similarity,
                new ConnectionConsequences(
                        kept.getId(),
                        absorbed.getId(),
                        pair.foldCase(),
                        GoalStatus.SUBSUMED.name(),
                        plan.carried(),
                        plan.alreadyCovered(),
                        mergeCountAfter,
                        unmetOnAbsorbed));
    }

    private record FoldPair(GoalEntity kept, String foldCase, boolean carryCriteria) {}

    /** The status-pair table's allowed rows; {@code null} means the pair has no fold case. */
    private FoldPair classifyStatusPair(GoalEntity a, GoalEntity b) {
        GoalStatus sa = a.getStatus();
        GoalStatus sb = b.getStatus();
        if (sa == GoalStatus.ACTIVE && sb == GoalStatus.ACTIVE) {
            return new FoldPair(goalConsolidationService.chooseGoalToKeep(a, b), "active-duplicate", true);
        }
        if (sa == GoalStatus.GIVEN_UP && sb == GoalStatus.ACTIVE) {
            return new FoldPair(b, "given-up-covered", true);
        }
        if (sb == GoalStatus.GIVEN_UP && sa == GoalStatus.ACTIVE) {
            return new FoldPair(a, "given-up-covered", true);
        }
        if (sa == GoalStatus.GIVEN_UP && sb == GoalStatus.COMPLETED) {
            return new FoldPair(b, "given-up-covered", false);
        }
        if (sb == GoalStatus.GIVEN_UP && sa == GoalStatus.COMPLETED) {
            return new FoldPair(a, "given-up-covered", false);
        }
        return null;
    }

    private static String statusPairRefusal(GoalEntity a, GoalEntity b) {
        GoalStatus sa = a.getStatus();
        GoalStatus sb = b.getStatus();
        if (sa == GoalStatus.COMPLETED && sb == GoalStatus.COMPLETED) {
            return "both goals are COMPLETED — their criteria are banked and there is nothing to fold";
        }
        if (sa == GoalStatus.GIVEN_UP && sb == GoalStatus.GIVEN_UP) {
            return "no side can keep the work — a fold needs an ACTIVE or COMPLETED goal to carry it";
        }
        // The remaining pair is ACTIVE↔COMPLETED, in either order.
        return "no fold case joins ACTIVE and COMPLETED work — an ACTIVE duplicate of completed work "
                + "is handled by injection dedup; folding it would silently drop its unmet criteria";
    }
}
