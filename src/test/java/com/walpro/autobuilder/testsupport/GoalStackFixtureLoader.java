package com.walpro.autobuilder.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads a persisted {@code GoalStack.json} snapshot (the derived goal-tree file real runs write
 * under {@code meta/}) into a harness {@link GoalPlannerService}, so scenarios can start the
 * control loop from a REAL run's goal state instead of hand-built two-node trees.
 *
 * <p>Committed fixtures under {@code src/test/resources/goalstack-fixtures/} are trimmed
 * captures of preserved field runs — each one the terminal state of a run that exposed a
 * control-loop defect (the {@code /tmp} corpora are volatile; these are the durable copies):
 * <ul>
 *   <li>{@code midgame-broken-tests-run17.json} — run 17 (2026-07-06): broken self-authored
 *       tests walled packaging; false give-up on a 16/16 runtime-correct artifact.</li>
 *   <li>{@code endgame-closed-but-unmet-run18.json} — run 18 (2026-07-06): #NoDiff promotion
 *       raced its criteria check; COMPLETED-with-unmet blocker; root given up on working code.</li>
 *   <li>{@code endgame-authored-blocker-run19.json} — run 19 (2026-07-07): planner minted a
 *       spec-contradicting requirement from a compile diagnostic; its give-up killed the root.</li>
 * </ul>
 *
 * <p>Snapshots are TERMINAL states (root already GIVEN_UP). Scenarios that need the moment
 * just before the fatal edge call {@link #reactivateRoot} to rewind the final transition —
 * an explicit, visible rewind rather than a doctored fixture file.
 */
public final class GoalStackFixtureLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoalStackFixtureLoader() {}

    /** Loads the named fixture into the harness planner; returns the goal map for assertions. */
    public static Map<String, GoalEntity> load(ControlLoopScenarioHarness harness, String fixtureName) {
        JsonNode stack = readFixture(fixtureName);
        JsonNode tree = stack.get("goalTree");
        if (tree == null || tree.isNull()) {
            throw new IllegalArgumentException("fixture has no goalTree: " + fixtureName);
        }
        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        walk(tree, null, goalMap);

        GoalPlannerService planner = harness.goalPlannerService;
        ReflectionTestUtils.setField(planner, "goalMap", goalMap);
        ReflectionTestUtils.setField(planner, "rootGoalId", tree.get("id").asText());
        JsonNode currentSub = stack.get("currentSubGoalId");
        ReflectionTestUtils.setField(planner, "currentSubGoalId",
                currentSub != null && !currentSub.isNull() ? currentSub.asText() : null);
        return goalMap;
    }

    /** Rewinds a terminal snapshot's final transition: root back to ACTIVE. */
    public static void reactivateRoot(Map<String, GoalEntity> goalMap, String rootId) {
        GoalEntity root = goalMap.get(rootId);
        root.setStatus(GoalStatus.ACTIVE);
        root.setCompleted(null);
        root.setGivenUp(null);
    }

    private static void walk(JsonNode node, String parentId, Map<String, GoalEntity> out) {
        GoalEntity g = new GoalEntity(
                node.get("id").asText(),
                node.path("description").asText(""),
                node.path("role").asText("planner"),
                GoalType.valueOf(node.path("type").asText("SUBGOAL")),
                parentId);
        g.setStatus(GoalStatus.valueOf(node.path("status").asText("ACTIVE")));
        JsonNode metadata = node.get("metadata");
        if (metadata != null && metadata.isObject()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> md = MAPPER.convertValue(metadata, Map.class);
            g.getMetadata().putAll(md);
        }
        out.put(g.getId(), g);
        JsonNode children = node.get("subGoals");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                walk(child, g.getId(), out);
            }
        }
    }

    private static JsonNode readFixture(String fixtureName) {
        String path = "/goalstack-fixtures/" + fixtureName + ".json";
        try (InputStream in = GoalStackFixtureLoader.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("no such fixture: " + path);
            return MAPPER.readTree(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed reading fixture " + path, e);
        }
    }
}
