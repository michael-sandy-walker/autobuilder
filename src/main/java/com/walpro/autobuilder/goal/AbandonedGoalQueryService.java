package com.walpro.autobuilder.goal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.walpro.autobuilder.graph.goal.GoalGraphService;

/**
 * T2-DL: surfaces related GIVEN_UP clusters (and orphaned merged files) so analysis
 * and planner agents see prior work before proposing new subgoals.
 *
 * <p>A "cluster" is the subtree rooted at a GIVEN_UP goal whose description is
 * textually similar to the supplied candidate. The cluster carries the live islands
 * (still-ACTIVE descendants spared by T1-BF), every merge-bearing descendant, and
 * a flat list of merged files produced anywhere in the subtree — these files often
 * remain on disk under {@code output/} without any live goal owning them.</p>
 */
@Service
public class AbandonedGoalQueryService {

    public static final float DEFAULT_SIMILARITY_THRESHOLD = 0.60f;
    public static final int DEFAULT_MAX_CLUSTERS = 5;

    private final GoalGraphService graphService;
    private final GoalSimilarityService similarityService;

    public AbandonedGoalQueryService(GoalGraphService graphService, GoalSimilarityService similarityService) {
        this.graphService = graphService;
        this.similarityService = similarityService;
    }

    public List<AbandonedGoalCluster> findRelatedAbandonedGoals(String candidate) {
        return findRelatedAbandonedGoals(candidate, DEFAULT_SIMILARITY_THRESHOLD, DEFAULT_MAX_CLUSTERS);
    }

    /**
     * Returns up to {@code maxClusters} GIVEN_UP clusters whose root description matches
     * {@code candidate} at similarity ≥ {@code threshold}. Each cluster is the subtree
     * rooted at the GIVEN_UP root (so nested GIVEN_UP descendants are not enumerated
     * as their own clusters — they roll up into the nearest GIVEN_UP ancestor).
     */
    public List<AbandonedGoalCluster> findRelatedAbandonedGoals(String candidate, float threshold, int maxClusters) {
        if (candidate == null || candidate.isBlank()) return List.of();

        List<GoalEntity> allGoals = graphService.findAll();
        if (allGoals == null || allGoals.isEmpty()) return List.of();

        Map<String, GoalEntity> byId = new LinkedHashMap<>();
        Map<String, List<GoalEntity>> byParent = new LinkedHashMap<>();
        for (GoalEntity g : allGoals) {
            if (g == null || g.getId() == null) continue;
            byId.put(g.getId(), g);
            if (g.getParentId() != null) {
                byParent.computeIfAbsent(g.getParentId(), k -> new ArrayList<>()).add(g);
            }
        }

        List<GoalEntity> givenUpRoots = new ArrayList<>();
        for (GoalEntity g : allGoals) {
            if (g.getStatus() != GoalStatus.GIVEN_UP) continue;
            if (hasGivenUpAncestor(g, byId)) continue;
            if (similarityService.similarity(candidate, g.getDescription()) < threshold) continue;
            givenUpRoots.add(g);
        }

        givenUpRoots.sort((a, b) -> Float.compare(
                similarityService.similarity(candidate, b.getDescription()),
                similarityService.similarity(candidate, a.getDescription())
        ));

        List<AbandonedGoalCluster> out = new ArrayList<>();
        for (GoalEntity root : givenUpRoots) {
            if (out.size() >= maxClusters) break;
            out.add(buildCluster(root, byParent));
        }
        return out;
    }

    private boolean hasGivenUpAncestor(GoalEntity g, Map<String, GoalEntity> byId) {
        String parentId = g.getParentId();
        while (parentId != null) {
            GoalEntity parent = byId.get(parentId);
            if (parent == null) return false;
            if (parent.getStatus() == GoalStatus.GIVEN_UP) return true;
            parentId = parent.getParentId();
        }
        return false;
    }

    private AbandonedGoalCluster buildCluster(GoalEntity root, Map<String, List<GoalEntity>> byParent) {
        List<GoalEntity> subtree = walkSubtree(root, byParent);

        List<GoalEntity> active = new ArrayList<>();
        List<GoalEntity> mergeBearing = new ArrayList<>();
        Set<String> mergedFiles = new LinkedHashSet<>();

        addMergedFiles(mergedFiles, root);
        if (root.getMergeCount() > 0) mergeBearing.add(root);

        for (GoalEntity g : subtree) {
            if (g == root) continue;
            if (g.getStatus() == GoalStatus.ACTIVE) active.add(g);
            if (g.getMergeCount() > 0) mergeBearing.add(g);
            addMergedFiles(mergedFiles, g);
        }

        Map<Integer, Boolean> criteriaStatus = readCriteriaStatus(root);

        return new AbandonedGoalCluster(
                root,
                root.getGivenUp(),
                root.getResolution(),
                List.copyOf(active),
                List.copyOf(mergeBearing),
                List.copyOf(mergedFiles),
                criteriaStatus
        );
    }

    private List<GoalEntity> walkSubtree(GoalEntity root, Map<String, List<GoalEntity>> byParent) {
        List<GoalEntity> out = new ArrayList<>();
        out.add(root);
        int idx = 0;
        while (idx < out.size()) {
            GoalEntity current = out.get(idx++);
            List<GoalEntity> children = byParent.get(current.getId());
            if (children == null) continue;
            out.addAll(children);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void addMergedFiles(Collection<String> out, GoalEntity g) {
        if (g.getMetadata() == null) return;
        Object raw = g.getMetadata().get("mergedFiles");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) out.add(o.toString());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Boolean> readCriteriaStatus(GoalEntity g) {
        if (g.getMetadata() == null) return Map.of();
        Object raw = g.getMetadata().get("criteriaPassStatus");
        if (raw instanceof Map<?, ?> m) {
            Map<Integer, Boolean> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Integer idx = toInt(e.getKey());
                if (idx == null) continue;
                Object v = e.getValue();
                if (v instanceof Boolean b) {
                    out.put(idx, b);
                }
            }
            return out;
        }
        return Map.of();
    }

    private static Integer toInt(Object key) {
        if (key instanceof Number n) return n.intValue();
        if (key instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
