//package com.walpro.autobuilder.goal.graph;
//
//import com.walpro.autobuilder.goal.GoalEntity;
//import com.walpro.autobuilder.goal.GoalStatus;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.util.*;
//
//@Transactional("transactionManager")
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class Neo4jGoalGraphService implements GoalGraphService {
//
//    private final GoalRepository goalRepository;
//
////    @Override
////    public void save(GoalEntity g) {
////        GoalNode node = new GoalNode(g);
////        if (g.getParentId() != null) {
////            goalRepository.findById(g.getParentId()).ifPresent(node::setParent);
////        }
////        goalRepository.save(node);
////    }
//    @Override
//    public void save(GoalEntity g) {
//        goalRepository.findById(g.getId()).ifPresentOrElse(existing -> {
//            existing.setDescription(g.getDescription());
//            existing.setRole(g.getRole());
//            existing.setType(g.getType());
//            existing.setStatus(g.getStatus());
//            existing.setMetadata(g.getMetadata());
//            existing.setCreated(g.getCreated());
//
//            // Only update parent if not already set
//            if (g.getParentId() != null && (existing.getParent() == null ||
//                    !g.getParentId().equals(existing.getParent().getId()))) {
//                goalRepository.findById(g.getParentId()).ifPresent(existing::setParent);
//            }
//
//            goalRepository.save(existing);
//        }, () -> {
//            GoalNode node = new GoalNode(g);
//            if (g.getParentId() != null) {
//                goalRepository.findById(g.getParentId()).ifPresent(node::setParent);
//            }
//            goalRepository.save(node);
//        });
//    }
//
//
//    @Override
//    public Optional<GoalEntity> findById(String id) {
//        return goalRepository.findById(id).map(GoalNode::toEntity);
//    }
//
//    @Override
//    public List<GoalEntity> findSubgoals(String parentId) {
//        return goalRepository.findById(parentId)
//                .map(GoalNode::getSubgoals)
//                .stream()
//                .flatMap(Set::stream)
//                .map(GoalNode::toEntity)
//                .toList();
//    }
//
//    @Override
//    public List<GoalEntity> findAll() {
//        return goalRepository.findAll().stream().map(GoalNode::toEntity).toList();
//    }
//
//    @Override
//    public void markCompleted(String id) {
//        goalRepository.findById(id).ifPresent(n -> {
//            n.setStatus(GoalStatus.COMPLETED);
//            goalRepository.save(n);
//        });
//    }
//
//    @Override
//    public void promote(String id) {
//        // Define your promotion logic here.
//        // For now, just log.
//        log.info("Promotion logic not yet implemented for goal: {}", id);
//    }
//}

package com.walpro.autobuilder.goal.graph;

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
        System.out.println("Using Neo4j URI: " + env.getProperty("spring.data.neo4j.uri"));
        System.out.println("Using Neo4j user: " + env.getProperty("spring.data.neo4j.username"));
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
    
    @SuppressWarnings("unchecked")
    private GoalEntity mapRowToGoalEntity(Map<String, Object> row) {
//        Map<String, Object> g = (Map<String, Object>) row.get("g");
//
//        String id = (String) g.get("id");
//        String description = (String) g.get("description");
//        String role = (String) g.get("role");
//        String type = (String) g.get("type");
//        String status = (String) g.get("status");
//        String parentId = (String) row.get("parentId");
//        String createdStr = (String) g.get("created");
//        String metadataJson = (String) g.get("metadata");
//        Map<String, Object> metadata = new HashMap<>();
//        if (metadataJson != null) {
//            try {
//                metadata = objectMapper.readValue(metadataJson, new TypeReference<>() {});
//            } catch (JsonProcessingException e) {
//                log.warn("Failed to deserialize metadata for goal {}: {}", id, e.getMessage());
//            }
//        }
//        
//        GoalType goalType = type != null ? GoalType.valueOf(type) : GoalType.SUBGOAL;
//        GoalStatus goalStatus = status != null ? GoalStatus.valueOf(status) : GoalStatus.ACTIVE;
//        Instant created = createdStr != null ? Instant.parse(createdStr) : Instant.now();
    	
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

        GoalEntity goal = new GoalEntity(
            id,
            description,
            role,            
            GoalType.valueOf(type),            
            goalStatus,
            created,
            parentId,
            metadata
        );

        return goal;
    }

}

