package com.walpro.autobuilder.graph.goal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.neo4j.driver.Driver;
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

    private final Neo4jClient neo4jClient;
    private final Environment env;
    private final Driver driver;
    private final ObjectMapper objectMapper;
    
    @PostConstruct
    public void logNeo4jConfig() {
//        System.out.println("Using Neo4j config: " + System.getProperty("spring.data.neo4j.uri"));
        log.info("Using Neo4j URI: " + env.getProperty("spring.data.neo4j.uri"));
        log.info("Using Neo4j user: " + env.getProperty("spring.data.neo4j.username"));
    }
    
    @PostConstruct
    public void logDriverAuthDetails() {
        log.info("Neo4j driver config: {}", driver);
    }

    @Override
    public void save(GoalEntity g) {
        Optional<String> existingId = neo4jClient
                .query("MATCH (g:Goal {id: $id}) RETURN g.id AS id")
                .bindAll(Map.of("id", g.getId()))
                .fetchAs(String.class)
                .one();

        if (existingId.isPresent()) {
            // update existing
            neo4jClient.query("""
                MATCH (g:Goal {id: $id})
                SET g.description = $description,
                    g.role = $role,
                    g.type = $type,
                    g.status = $status,
                    g.created = datetime($created),
                    g.metadata = $metadata
                """)
                .bindAll(serialize(g))
                .run();

            if (g.getParentId() != null) {
                // Optional: match parent and rebind relationship if needed
                neo4jClient.query("""
                    MATCH (g:Goal {id: $id})
                    OPTIONAL MATCH (g)-[r:SUBGOAL_OF]->(:Goal)
                    DELETE r
                    WITH g
                    MATCH (parent:Goal {id: $parentId})
                    MERGE (g)-[:SUBGOAL_OF]->(parent)
                    """)
                    .bindAll(serialize(g))
                    .run();
            }

        } else {
            // create new
            neo4jClient.query("""
                CREATE (g:Goal {
                    id: $id,
                    description: $description,
                    role: $role,
                    type: $type,
                    status: $status,
                    created: datetime($created),
                    metadata: $metadata
                })
                """)
                .bindAll(serialize(g))
                .run();

            if (g.getParentId() != null) {
                neo4jClient.query("""
                    MATCH (g:Goal {id: $id})
                    MATCH (parent:Goal {id: $parentId})
                    MERGE (g)-[:SUBGOAL_OF]->(parent)
                    """)
                    .bindAll(serialize(g))
                    .run();
            }
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
        map.put("type", g.getType().name());
        map.put("status", g.getStatus().name());
        map.put("created", g.getCreated().toString());
        try {
            map.put("metadata", objectMapper.writeValueAsString(g.getMetadata()));
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

    	Map<String, Object> metadata = g.containsKey("metadata")
    			? g.get("metadata").asMap()
    					: Collections.emptyMap();

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

