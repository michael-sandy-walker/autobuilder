package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * T4-EG slice 4: a kept import's goals join the project's ONE tree -- its own root is not copied (the user filed it:
 * "the graph is just patch-work, connected by an import node; root nodes are now duplicate").
 *
 * <p>Each of the import's goals below its root is matched against the project's goals, structure first: the same text,
 * or criteria that largely agree; the model only for a pair structure leaves open, and only against the few goals whose
 * words overlap most. A match gains the import's criteria it lacks that the merged code already meets (what it does not
 * meet is work on the intent goal already); no node is added. A goal that matches nothing is added under its parent's
 * counterpart, or under the intent goal, COMPLETED when the merged code meets it. A goal the import had given up or
 * subsumed is not brought. Every goal added or unified is linked to the input in the graph, as provenance only.
 */
@Slf4j
final class GoalUnifier {

    /** What the unification did, for the log and the report. */
    record Outcome(int unified, int added, int skipped, int askedTheModel) {
    }

    private static final double CRITERIA_AGREE = 0.5;
    private static final double WORDS_CANDIDATE = 0.3;
    private static final int MODEL_CANDIDATES = 3;

    private final GoalPlannerService goals;
    private final InputComparison comparison;
    private final String specification;
    private final String inputId;
    private int unified, added, skipped, asked;

    GoalUnifier(GoalPlannerService goals, InputComparison comparison, String specification, String inputId) {
        this.goals = goals;
        this.comparison = comparison;
        this.specification = specification == null ? "" : specification;
        this.inputId = inputId;
    }

    /** Unifies the goals below {@code importRoot} into the project, the unmatched top ones under {@code intentGoalId}. */
    Outcome unify(JsonNode importRoot, String intentGoalId) {
        if (importRoot != null) {
            // The import's root is not copied: what it asked of its own project goes onto the intent goal -- its unmet
            // criteria are there as work already, its met ones are banked there.
            List<String> criteria = criteriaOf(importRoot);
            Map<Integer, Boolean> pass = passOf(importRoot);
            List<String> met = new ArrayList<>();
            for (int i = 0; i < criteria.size(); i++) if (Boolean.TRUE.equals(pass.get(i + 1))) met.add(criteria.get(i));
            goals.addMetCriteria(intentGoalId, met);
            for (JsonNode child : importRoot.path("subGoals")) visit(child, intentGoalId);
        }
        log.info("T4-EG: import {} unified into the project's tree -- {} goal(s) unified, {} added, {} not brought, {} pair(s) asked of the model",
                inputId, unified, added, skipped, asked);
        return new Outcome(unified, added, skipped, asked);
    }

    private void visit(JsonNode node, String parentCounterpart) {
        String status = node.path("status").asText("");
        if (GoalStatus.SUBSUMED.name().equals(status) || GoalStatus.GIVEN_UP.name().equals(status)) {
            skipped += 1 + count(node);
            return;
        }
        String description = node.path("description").asText("").strip();
        List<String> criteria = criteriaOf(node);
        Map<Integer, Boolean> pass = passOf(node);
        String counterpart;
        Match m = match(description, criteria);
        if (m != null) {
            List<String> met = new ArrayList<>();
            for (int i = 0; i < criteria.size(); i++) {
                if (Boolean.TRUE.equals(pass.get(i + 1))) met.add(criteria.get(i));
            }
            goals.addMetCriteria(m.goal().getId(), met);
            goals.purpose().linkBrought(inputId, m.goal().getId(), m.how());
            unified++;
            counterpart = m.goal().getId();
        } else {
            boolean meets = GoalStatus.COMPLETED.name().equals(status)
                    && (criteria.isEmpty() || pass.values().stream().allMatch(Boolean::booleanValue) && pass.size() >= criteria.size());
            counterpart = goals.addLandedGoal(parentCounterpart, description, criteria, pass, meets, inputId,
                    node.path("id").asText(null));
            goals.purpose().linkBrought(inputId, counterpart, "added");
            added++;
        }
        for (JsonNode child : node.path("subGoals")) visit(child, counterpart);
    }

    private record Match(GoalEntity goal, String how) {
    }

    /** The project's goal this one is, or null: text, then criteria, then the model on the closest few. */
    private Match match(String description, List<String> criteria) {
        List<GoalEntity> ours = goals.getAllGoals().stream()
                .filter(g -> g.getParentId() != null)
                .filter(g -> g.getStatus() != GoalStatus.SUBSUMED && g.getStatus() != GoalStatus.GIVEN_UP)
                .filter(g -> !inputId.equals(g.getMetadata() == null ? null : g.getMetadata().get("inputId")))
                .toList();
        String text = normalise(description);
        for (GoalEntity g : ours) {
            if (normalise(g.getDescription()).equals(text)) return new Match(g, "text");
        }
        if (!criteria.isEmpty()) {
            Set<String> theirs = new HashSet<>();
            criteria.forEach(c -> theirs.add(normalise(c)));
            for (GoalEntity g : ours) {
                long shared = criteriaOf(g).stream().map(GoalUnifier::normalise).filter(theirs::contains).count();
                if ((double) shared / theirs.size() >= CRITERIA_AGREE) return new Match(g, "criteria");
            }
        }
        Map<GoalEntity, Double> close = new LinkedHashMap<>();
        Set<String> words = words(description);
        for (GoalEntity g : ours) {
            double j = jaccard(words, words(g.getDescription()));
            if (j >= WORDS_CANDIDATE) close.put(g, j);
        }
        List<GoalEntity> candidates = close.entrySet().stream()
                .sorted(Map.Entry.<GoalEntity, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(MODEL_CANDIDATES).map(Map.Entry::getKey).toList();
        String theirsText = withCriteria(description, criteria);
        for (GoalEntity g : candidates) {
            asked++;
            if (comparison.sameGoal(specification, withCriteria(g.getDescription(), criteriaOf(g)), theirsText)) {
                return new Match(g, "model");
            }
        }
        return null;
    }

    private static String withCriteria(String description, List<String> criteria) {
        StringBuilder out = new StringBuilder(description);
        for (String c : criteria) out.append("\n  - ").append(c);
        return out.toString();
    }

    private static int count(JsonNode node) {
        int n = 0;
        for (JsonNode c : node.path("subGoals")) n += 1 + count(c);
        return n;
    }

    private static List<String> criteriaOf(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : node.path("metadata").path("acceptanceCriteria")) out.add(c.asText());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> criteriaOf(GoalEntity g) {
        Object raw = g.getMetadata() == null ? null : g.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> list)) return List.of();
        return ((List<Object>) list).stream().map(String::valueOf).toList();
    }

    private static Map<Integer, Boolean> passOf(JsonNode node) {
        Map<Integer, Boolean> out = new HashMap<>();
        node.path("metadata").path("criteriaPassStatus").fields().forEachRemaining(e -> {
            try {
                if (e.getValue().isBoolean()) out.put(Integer.parseInt(e.getKey()), e.getValue().asBoolean());
            } catch (NumberFormatException ignored) {
                // not an index
            }
        });
        return out;
    }

    static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9{}/ ]", " ").replaceAll("\\s+", " ").strip();
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>(List.of(normalise(s).split(" ")));
        out.removeIf(w -> w.length() < 3);
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> both = new HashSet<>(a);
        both.retainAll(b);
        Set<String> either = new HashSet<>(a);
        either.addAll(b);
        return (double) both.size() / either.size();
    }
}
