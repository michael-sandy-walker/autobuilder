package com.walpro.autobuilder.graph.goal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.springframework.core.env.Environment;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Transactional("transactionManager")
@Slf4j
@Service
@RequiredArgsConstructor
public class Neo4jGoalGraphService implements GoalGraphService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Neo4jClient neo4jClient;
    private final Environment env;
    private final Driver driver;
    private final ObjectMapper objectMapper;
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentity;

    @PostConstruct
    public void logNeo4jConfig() {
        log.info("Using Neo4j URI: " + env.getProperty("spring.data.neo4j.uri"));
        log.info("Using Neo4j user: " + env.getProperty("spring.data.neo4j.username"));
    }
    
    @PostConstruct
    public void logDriverAuthDetails() {
        log.info("Neo4j driver config: {}", driver);
    }

    /**
     * Properties-only upsert. Does NOT touch relationships.
     */
    @Override
    public void save(GoalEntity g) {
        neo4jClient.query("""
            MERGE (n:Goal {id: $id})
            ON CREATE SET
                n.description = $description,
                n.role = $role,
                n.type = $type,
                n.status = $status,
                n.created = $created,
                n.metadata = $metadata
            ON MATCH SET
                n.description = CASE
                    WHEN $description IS NULL OR trim(toString($description)) = '' THEN n.description
                    ELSE $description
                END,
                n.role = CASE
                    WHEN $role IS NULL OR trim(toString($role)) = '' THEN n.role
                    ELSE $role
                END,
                n.type = CASE
                    WHEN $type IS NULL OR trim(toString($type)) = '' THEN n.type
                    ELSE $type
                END,
                n.status = CASE
                    WHEN $status IS NULL OR trim(toString($status)) = '' THEN n.status
                    ELSE $status
                END,
                n.created = CASE
                    WHEN $created IS NULL OR trim(toString($created)) = '' THEN n.created
                    ELSE $created
                END,
                n.metadata = CASE
                    WHEN $metadata IS NULL OR trim(toString($metadata)) = '' THEN n.metadata
                    ELSE $metadata
                END,
                n.givenUp = CASE
                    WHEN $givenUp IS NULL OR trim(toString($givenUp)) = '' THEN n.givenUp
                    ELSE $givenUp
                END,
                n.resolution = CASE
                    WHEN $resolution IS NULL OR trim(toString($resolution)) = '' THEN n.resolution
                    ELSE $resolution
                END
            SET n.lineageId = $lineageId,
                n.runId = $runId
            """)
                .bindAll(serialize(g))
                .run();
    }

    /**
     * Explicit topology operation.
     */
    @Override
    public void setParent(String childId, String parentId) {
        if (childId == null || childId.isBlank()) {
            throw new IllegalArgumentException("childId must not be null/blank");
        }
        if (parentId == null || parentId.isBlank()) {
            throw new IllegalArgumentException("parentId must not be null/blank");
        }

        neo4jClient.query("""
            MATCH (child:Goal {id: $childId})
            MATCH (parent:Goal {id: $parentId})
            OPTIONAL MATCH (child)-[r:SUBGOAL_OF]->(:Goal)
            DELETE r
            MERGE (child)-[:SUBGOAL_OF]->(parent)
            """)
                .bind(childId).to("childId")
                .bind(parentId).to("parentId")
                .run();
    }

    @Override
    public Optional<GoalEntity> findById(String id) {
        return neo4jClient.query("""
                MATCH (g:Goal {id: $id})
                OPTIONAL MATCH (g)-[:SUBGOAL_OF]->(parent:Goal)
                RETURN g, parent.id AS parentId
                """)
            .bind(id).to("id")
            .fetch()
            .all()
            .stream()
            .findFirst()
            .map(this::mapRowToGoalEntity);
    }


    @Override
    public List<GoalEntity> findAll() {
        return neo4jClient.query("""
                MATCH (g:Goal)
                OPTIONAL MATCH (g)-[:SUBGOAL_OF]->(parent:Goal)
                RETURN g, parent.id AS parentId
                """)
        		.fetch()
        		.all()
        		.stream()
        		.map(this::mapRowToGoalEntity)
        		.toList();
    }



    @Override
    public List<GoalEntity> findSubgoals(String parentId) {
        return neo4jClient.query("""
                MATCH (parent:Goal {id: $parentId})<-[:SUBGOAL_OF]-(g:Goal)
                RETURN g, parent.id AS parentId
                """)
            .bind(parentId).to("parentId")
    		.fetch()
    		.all()
    		.stream()
    		.map(this::mapRowToGoalEntity)
    		.toList();
    }



    @Override
    public void markCompleted(String id) {
        neo4jClient.query("""
                MATCH (g:Goal {id: $id})
                SET g.status = 'COMPLETED', g.completed = $completed
                """)
                .bind(id).to("id")
                .bind(Instant.now().toString()).to("completed")
                .run();
    }

    @Override
    public void markCompleted(String id, String resolution) {
        neo4jClient.query("""
                MATCH (g:Goal {id: $id})
                SET g.status = 'COMPLETED', g.completed = $completed, g.resolution = $resolution
                """)
                .bind(id).to("id")
                .bind(Instant.now().toString()).to("completed")
                .bind(resolution).to("resolution")
                .run();
    }

    @Override
    public void promote(String id) {
        log.info("Promotion logic not yet implemented for goal: {}", id);
    }

    // Helper to serialize GoalEntity to parameter map
    private Map<String, Object> serialize(GoalEntity g) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", g.getId());
        map.put("description", g.getDescription());
        map.put("role", g.getRole());
        map.put("type", g.getType() != null ? g.getType().name() : null);
        map.put("status", g.getStatus() != null ? g.getStatus().name() : null);

        // Avoid null created turning into NPE elsewhere and avoid writing junk.
        Instant created = g.getCreated();
        map.put("created", created != null ? created.toString() : null);

        Instant givenUp = g.getGivenUp();
        map.put("givenUp", givenUp != null ? givenUp.toString() : null);
        map.put("resolution", g.getResolution());

        try {
            map.put("metadata", objectMapper.writeValueAsString(
                    g.getMetadata() != null ? g.getMetadata() : Map.of()
            ));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata for goal {}: {}", g.getId(), e.getMessage());
            map.put("metadata", "{}");
        }

        // T4-CJ: run/lineage identity stamps (write-only in slice 1).
        map.put("lineageId", runIdentity.lineageId());
        map.put("runId", runIdentity.runId());

        return map;
    }
    
    private GoalEntity mapRowToGoalEntity(Map<String, Object> row) {
    	Node g = (Node) row.get("g");

    	String id = g.get("id").asString();
    	String description = g.get("description").asString(null);
    	String role = g.get("role").asString(null);
    	String type = g.get("type").asString(null);
    	String status = g.get("status").asString(null);
    	String createdStr = g.get("created").asString(null);
    	String completedStr = g.get("completed").asString(null);
    	String givenUpStr = g.containsKey("givenUp") ? g.get("givenUp").asString(null) : null;
    	String parentId = row.get("parentId") != null ? row.get("parentId").toString() : null;

    	GoalType goalType = type != null ? GoalType.valueOf(type) : GoalType.SUBGOAL;
    	GoalStatus goalStatus = status != null ? GoalStatus.valueOf(status) : GoalStatus.ACTIVE;
    	Instant created = createdStr != null ? Instant.parse(createdStr) : Instant.now();
    	Instant completed = completedStr != null ? Instant.parse(completedStr) : null;
    	Instant givenUp = givenUpStr != null ? Instant.parse(givenUpStr) : null;

        Map<String, Object> metadata = Collections.emptyMap();
        if (g.containsKey("metadata")) {
            Value mv = g.get("metadata");
            if (mv != null && !mv.isNull()) {
                // stored as JSON string
                if (mv.type().name().equals("STRING")) {
                    String json = mv.asString("");
                    if (!json.isBlank() && !json.equals("{}")) {
                        try {
                            metadata = objectMapper.readValue(json, MAP_TYPE);
                        } catch (Exception e) {
                            log.warn("Failed to parse metadata JSON for goal {}: {}", id, e.getMessage());
                            metadata = Collections.emptyMap();
                        }
                    }
                } else {
                    // fallback if someday it is stored as a Neo4j map
                    try {
                        metadata = mv.asMap();
                    } catch (Exception ignored) {
                        metadata = Collections.emptyMap();
                    }
                }
            }
        }

        String resolution = g.containsKey("resolution") ? g.get("resolution").asString(null) : null;

        GoalEntity entity = new GoalEntity(
            id,
            description,
            role,
            goalType,
            goalStatus,
            created,
            parentId,
            metadata
        );
        entity.setCompleted(completed);
        entity.setGivenUp(givenUp);
        entity.setResolution(resolution);
        return entity;
    }

    @Override
    public Optional<GoalGraphState> getState() {
        return neo4jClient.query("""
        MATCH (s:SystemState {id: 'autobuilder'})
        RETURN s.rootGoalId AS rootGoalId, s.currentSubGoalId AS currentSubGoalId
        """)
                .fetch()
                .one()
                .map(row -> new GoalGraphState(
                        (String) row.get("rootGoalId"),
                        (String) row.get("currentSubGoalId")
                ));
    }

    @Override
    public void setState(String rootGoalId, String currentSubGoalId) {
        if (rootGoalId == null || rootGoalId.isBlank()) {
            throw new IllegalArgumentException("rootGoalId must not be null/blank");
        }

        neo4jClient.query("""
        MERGE (s:SystemState {id: 'autobuilder'})
        SET s.rootGoalId = $rootGoalId,
            s.currentSubGoalId = $currentSubGoalId,
            s.updated = $updated,
            s.lineageId = $lineageId,
            s.runId = $runId
        """)
                .bind(rootGoalId).to("rootGoalId")
                .bind(currentSubGoalId).to("currentSubGoalId") // may be null
                .bind(Instant.now().toString()).to("updated")
                .bind(runIdentity.lineageId()).to("lineageId")
                .bind(runIdentity.runId()).to("runId")
                .run();
    }

    @Override
    public void purgeAll() {
//        // Remove pointer/state first, then goals. DETACH DELETE ensures relationships are removed.
//        neo4jClient.query("""
//                MATCH (s:SystemState)
//                DETACH DELETE s
//                """).run();
//
//        neo4jClient.query("""
//                MATCH (g:Goal)
//                DETACH DELETE g
//                """).run();
//
//        log.warn("Purged Neo4j goal graph: deleted all (Goal) and (SystemState) nodes.");
        // TODO: use above code instead of the below one to not wipe all data from the database, but only the actual goals instead
        neo4jClient.query("""
                MATCH (n)
                DETACH DELETE n
                """).run();

        log.warn("Purged Neo4j graph: deleted all nodes.");
    }

    @Override
    public List<String> findAllDescriptions(int limit) {
        int capped = Math.max(1, limit);

        return neo4jClient.query("""
                MATCH (g:Goal)
                WHERE g.description IS NOT NULL AND g.status <> 'GIVEN_UP'
                RETURN g.description AS d
                LIMIT $limit
                """)
                .bind(capped).to("limit")
                .fetch()
                .all()
                .stream()
                .map(row -> (String) row.get("d"))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<String> findDescriptionsByStatus(String status, int limit) {
        int capped = Math.max(1, limit);

        return neo4jClient.query("""
                MATCH (g:Goal)
                WHERE g.description IS NOT NULL AND g.status = $status
                RETURN g.description AS d
                LIMIT $limit
                """)
                .bind(status).to("status")
                .bind(capped).to("limit")
                .fetch()
                .all()
                .stream()
                .map(row -> (String) row.get("d"))
                .filter(Objects::nonNull)
                .toList();
    }
}

