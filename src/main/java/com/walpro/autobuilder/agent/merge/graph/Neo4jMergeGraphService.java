package com.walpro.autobuilder.agent.merge.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.merge.model.MergeNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.springframework.core.env.Environment;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class Neo4jMergeGraphService implements MergeGraphService {

    private final Neo4jClient neo4jClient;
    private final Environment env;
    private final Driver driver;
    private final ObjectMapper objectMapper;

    @Override
    public void save(MergeNode node) {
        String mergeId = node.mergedInCycle() + "::" + node.path();

        // Create merge node
        neo4jClient.query("""
            MERGE (m:Merge {
                id: $id,
                path: $path,
                mergedInCycle: $mergedInCycle,
                contentHash: $contentHash
            })
            """)
        .bindAll(Map.of(
                "id", mergeId,
                "path", node.path(),
                "mergedInCycle", node.mergedInCycle(),
                "contentHash", node.contentHash()
        ))
        .run();

        // Link to contributing cycles
        for (String cycleId : node.contributingCycles()) {
            neo4jClient.query("""
                MERGE (c:Cycle {id: $cycleId})
                WITH c
                MATCH (m:Merge {id: $mergeId})
                MERGE (c)-[:MERGED_INTO]->(m)
                """)
            .bind(cycleId).to("cycleId")
            .bind(mergeId).to("mergeId")
            .run();
        }

        log.debug("📡 Saved MergeNode '{}' to Neo4j with {} contributors", mergeId, node.contributingCycles().size());
    }
}
