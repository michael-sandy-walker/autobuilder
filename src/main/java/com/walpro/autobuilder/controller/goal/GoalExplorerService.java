package com.walpro.autobuilder.controller.goal;

import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalSimilarityService;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshot;
import com.walpro.autobuilder.graph.core.OrchestrationSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T4-AK: composes {@link GoalPlannerService}, {@link OrchestrationSnapshotService} and
 * {@link GoalSimilarityService} into a read-only query surface for the goal explorer UI.
 *
 * <p>All methods read in-memory state (authoritative during runtime) with Neo4j used only
 * for per-criterion assessment history. The explorer is purely additive — no writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalExplorerService {

    /** Minimum similarity for a criterion pair to be surfaced as a synergy candidate. */
    private static final double SYNERGY_THRESHOLD = 0.5d;

    /** Upper bound on returned synergy candidates — keeps the UI payload bounded. */
    private static final int SYNERGY_LIMIT = 50;

    private final GoalPlannerService goalPlannerService;
    private final GoalSimilarityService goalSimilarityService;

    /** Optional — falls back to empty history when no Neo4j is wired (e.g. pure unit tests). */
    @Autowired(required = false)
    private OrchestrationSnapshotService snapshotService;

    /** Wire or override for tests. */
    public void setSnapshotService(OrchestrationSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public GoalTreeNodeDto getGoalTree() {
        GoalEntity root = goalPlannerService.getRootGoal();
        if (root == null) return null;
        String currentId = currentSubGoalId();
        return buildTreeNode(root, currentId);
    }

    public GoalNodeDetailDto getGoalDetail(String goalId) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return null;
        String currentId = currentSubGoalId();
        List<CriterionStatusDto> criteria = buildCriteria(g);
        List<String> mergedFiles = asStringList(g.getMetadata().get("mergedFiles"));
        List<String> ancestry = buildAncestry(g);
        String planRationale = goalPlannerService.getPlanRationale(g);
        return new GoalNodeDetailDto(
                g.getId(),
                g.getDescription(),
                g.getType() != null ? g.getType().name() : null,
                g.getStatus() != null ? g.getStatus().name() : null,
                g.getRole(),
                g.getParentId(),
                g.getCreated(),
                g.getCompleted(),
                g.getGivenUp(),
                g.getResolution(),
                g.getMergeCount(),
                goalPlannerService.getGoalMetadataInt(g, "cumulativeStagnatingCycles"),
                readInt(g.getMetadata().get("planningRunId")),
                Objects.equals(g.getId(), currentId),
                criteria,
                mergedFiles,
                ancestry,
                planRationale
        );
    }

    public List<CriterionStatusDto> getCriteria(String goalId) {
        GoalEntity g = goalPlannerService.getGoalById(goalId);
        if (g == null) return List.of();
        return buildCriteria(g);
    }

    /**
     * Finds criteria on other subgoals that are semantically similar to the given goal's
     * criteria. The source goal itself is excluded. Results are sorted by similarity desc
     * and capped at {@value #SYNERGY_LIMIT}.
     */
    public List<SynergyCandidateDto> findSynergies(String sourceGoalId) {
        GoalEntity source = goalPlannerService.getGoalById(sourceGoalId);
        if (source == null) return List.of();
        List<String> sourceCriteria = asStringList(source.getMetadata().get("acceptanceCriteria"));
        if (sourceCriteria.isEmpty()) return List.of();

        List<SynergyCandidateDto> out = new ArrayList<>();
        for (GoalEntity other : goalPlannerService.getAllGoals()) {
            if (Objects.equals(other.getId(), sourceGoalId)) continue;
            List<String> otherCriteria = asStringList(other.getMetadata().get("acceptanceCriteria"));
            if (otherCriteria.isEmpty()) continue;
            for (int i = 0; i < sourceCriteria.size(); i++) {
                String src = sourceCriteria.get(i);
                if (src == null || src.isBlank()) continue;
                for (int j = 0; j < otherCriteria.size(); j++) {
                    String dst = otherCriteria.get(j);
                    if (dst == null || dst.isBlank()) continue;
                    float sim = goalSimilarityService.similarity(src, dst);
                    if (sim < SYNERGY_THRESHOLD) continue;
                    out.add(new SynergyCandidateDto(
                            sourceGoalId, i + 1, src,
                            other.getId(), other.getDescription(),
                            other.getStatus() != null ? other.getStatus().name() : null,
                            j + 1, dst,
                            sim
                    ));
                }
            }
        }
        out.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return out.size() > SYNERGY_LIMIT ? out.subList(0, SYNERGY_LIMIT) : out;
    }

    /**
     * Cross-goal file overlay: inverted index of merged filename → list of subgoal IDs
     * that recorded that file. Only files touched by two or more subgoals are returned —
     * these are the candidates for redundant or conflicting evolutions.
     */
    public List<FileOverlayDto> getFileOverlay() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        for (GoalEntity g : goalPlannerService.getAllGoals()) {
            List<String> files = asStringList(g.getMetadata().get("mergedFiles"));
            for (String f : files) {
                if (f == null || f.isBlank()) continue;
                index.computeIfAbsent(f, k -> new ArrayList<>()).add(g.getId());
            }
        }
        List<FileOverlayDto> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : index.entrySet()) {
            if (e.getValue().size() < 2) continue;
            result.add(new FileOverlayDto(e.getKey(), List.copyOf(e.getValue())));
        }
        return result;
    }

    // -----------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------

    private GoalTreeNodeDto buildTreeNode(GoalEntity g, String currentId) {
        List<GoalEntity> children = goalPlannerService.getSubGoals(g.getId());
        List<GoalTreeNodeDto> childDtos = new ArrayList<>(children.size());
        for (GoalEntity c : children) {
            childDtos.add(buildTreeNode(c, currentId));
        }
        List<String> criteria = asStringList(g.getMetadata().get("acceptanceCriteria"));
        Map<Integer, Boolean> passStatus = goalPlannerService.getCriteriaPassStatus(g);
        int met = 0;
        for (int i = 1; i <= criteria.size(); i++) {
            if (Boolean.TRUE.equals(passStatus.get(i))) met++;
        }
        return new GoalTreeNodeDto(
                g.getId(),
                g.getDescription(),
                g.getType() != null ? g.getType().name() : null,
                g.getStatus() != null ? g.getStatus().name() : null,
                g.getRole(),
                g.getCreated(),
                g.getCompleted(),
                g.getGivenUp(),
                g.getMergeCount(),
                goalPlannerService.getGoalMetadataInt(g, "cumulativeStagnatingCycles"),
                readInt(g.getMetadata().get("planningRunId")),
                criteria.size(),
                met,
                Objects.equals(g.getId(), currentId),
                childDtos
        );
    }

    private List<CriterionStatusDto> buildCriteria(GoalEntity g) {
        List<String> criteria = asStringList(g.getMetadata().get("acceptanceCriteria"));
        if (criteria.isEmpty()) return List.of();

        Map<Integer, Boolean> passStatus = goalPlannerService.getCriteriaPassStatus(g);
        Map<Integer, Integer> failStreaks = goalPlannerService.getCriteriaFailStreak(g);
        Map<Integer, OrchestrationSnapshot.CriterionProgress> progressByIndex = fetchProgress(g.getId());

        List<CriterionStatusDto> out = new ArrayList<>(criteria.size());
        for (int i = 0; i < criteria.size(); i++) {
            int idx = i + 1;
            String text = criteria.get(i);
            boolean cumulativePassed = Boolean.TRUE.equals(passStatus.get(idx));
            int failStreak = failStreaks.getOrDefault(idx, 0);
            OrchestrationSnapshot.CriterionProgress progress = progressByIndex.get(idx);
            boolean neverAssessed = progress == null || progress.neverAssessed();
            Boolean lastPassed = progress == null ? null : progress.lastPassed();
            boolean everPassed = progress != null && progress.everPassed();
            int consecutiveFailCount = progress == null ? 0 : progress.consecutiveFailCount();
            out.add(new CriterionStatusDto(
                    idx, text,
                    cumulativePassed,
                    failStreak,
                    neverAssessed,
                    lastPassed,
                    everPassed,
                    consecutiveFailCount
            ));
        }
        return out;
    }

    private Map<Integer, OrchestrationSnapshot.CriterionProgress> fetchProgress(String goalId) {
        if (snapshotService == null) return Collections.emptyMap();
        try {
            List<OrchestrationSnapshot.CriterionProgress> rows = snapshotService.fetchCriteriaProgress(goalId);
            Map<Integer, OrchestrationSnapshot.CriterionProgress> map = new LinkedHashMap<>();
            for (OrchestrationSnapshot.CriterionProgress p : rows) {
                map.put(p.criterionIndex(), p);
            }
            return map;
        } catch (Exception e) {
            log.debug("fetchCriteriaProgress failed for goal {}: {}", goalId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<String> buildAncestry(GoalEntity g) {
        List<String> chain = new ArrayList<>();
        String cursor = g.getParentId();
        int guard = 0;
        while (cursor != null && guard++ < 32) {
            chain.add(cursor);
            GoalEntity parent = goalPlannerService.getGoalById(cursor);
            if (parent == null) break;
            cursor = parent.getParentId();
        }
        Collections.reverse(chain);
        return chain;
    }

    private String currentSubGoalId() {
        GoalEntity curr = goalPlannerService.getCurrentSubGoal();
        return curr != null ? curr.getId() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object raw) {
        if (!(raw instanceof List<?> l) || l.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o == null) continue;
            out.add(o.toString());
        }
        return out;
    }

    private static Integer readInt(Object raw) {
        return raw instanceof Number n ? n.intValue() : null;
    }
}
