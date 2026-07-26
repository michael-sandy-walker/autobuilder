package com.walpro.autobuilder.graph.merge;

import com.walpro.autobuilder.agent.merge.model.MergeNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentity;

    @Override
    public void save(MergeNode node) {
        String mergeId = node.mergedInCycle() + "::" + node.path();

        // Create merge node
        neo4jClient.query("""
                MERGE (m:Merge { id: $id })
                SET m.path = $path,
                    m.mergedInCycle = $mergedInCycle,
                    m.contentHash = $contentHash,
                    m.lineageId = $lineageId,
                    m.runId = $runId
            """)
        .bindAll(Map.of(
                "id", mergeId,
                "path", node.path(),
                "mergedInCycle", node.mergedInCycle(),
                "contentHash", node.contentHash(),
                "lineageId", runIdentity.lineageId(),
                "runId", runIdentity.runId()
        ))
        .run();

        // Link to contributing cycles
        for (String cycleId : node.contributingCycles()) {
            neo4jClient.query("""
                MERGE (c:Cycle {id: $cycleId})
                    ON CREATE SET c.lineageId = $lineageId, c.runId = $runId
                WITH c
                MATCH (m:Merge {id: $mergeId})
                MERGE (c)-[:MERGED_INTO]->(m)
                """)
            .bind(cycleId).to("cycleId")
            .bind(mergeId).to("mergeId")
            .bind(runIdentity.lineageId()).to("lineageId")
            .bind(runIdentity.runId()).to("runId")
            .run();
        }

        log.debug("Saved MergeNode '{}' to Neo4j with {} contributors", mergeId, node.contributingCycles().size());
    }
}
