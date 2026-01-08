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

    @PostConstruct
    public void logNeo4jConfig() {
        log.info("Using Neo4j URI: " + env.getProperty("spring.data.neo4j.uri"));
        log.info("Using Neo4j user: " + env.getProperty("spring.data.neo4j.username"));
    }
    
    @PostConstruct
    public void logDriverAuthDetails() {
        log.info("Neo4j driver config: {}", driver);
    }

    @Override
    public void save(GoalEntity g) {
        // Use one MERGE with ON CREATE/ON MATCH to prevent null-wipes.
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
                END
            """)
                .bindAll(serialize(g))
                .run();

        // Relationship: only touch it when parentId is present; don’t delete otherwise.
        if (g.getParentId() != null && !g.getParentId().isBlank()) {
            neo4jClient.query("""
                MATCH (child:Goal {id: $id})
                OPTIONAL MATCH (child)-[r:SUBGOAL_OF]->(:Goal)
                DELETE r
                WITH child
                MATCH (parent:Goal {id: $parentId})
                MERGE (child)-[:SUBGOAL_OF]->(parent)
                """)
                    .bindAll(serialize(g))
                    .run();
        }
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
                SET g.status = 'COMPLETED'
                """)
                .bind(id).to("id")
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

        try {
            map.put("metadata", objectMapper.writeValueAsString(
                    g.getMetadata() != null ? g.getMetadata() : Map.of()
            ));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata for goal {}: {}", g.getId(), e.getMessage());
            map.put("metadata", "{}");
        }

        if (g.getParentId() != null) {
            map.put("parentId", g.getParentId());
        }
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
    	String parentId = row.get("parentId") != null ? row.get("parentId").toString() : null;

    	GoalType goalType = type != null ? GoalType.valueOf(type) : GoalType.SUBGOAL;
    	GoalStatus goalStatus = status != null ? GoalStatus.valueOf(status) : GoalStatus.ACTIVE;
    	Instant created = createdStr != null ? Instant.parse(createdStr) : Instant.now();

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

        return new GoalEntity(
            id,
            description,
            role,            
            goalType,            
            goalStatus,
            created,
            parentId,
            metadata
        );
    }

}

