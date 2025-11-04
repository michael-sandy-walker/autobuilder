package com.walpro.autobuilder.graph.reference;

import com.walpro.autobuilder.agent.merge.model.MergeNode;
import com.walpro.autobuilder.graph.core.AgentEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class Neo4jReferenceStateGraphService implements ReferenceStateGraphService {

    private final Neo4jClient neo4jClient;

    private static final String ROOT_ID = "reference-root";

    @Override
    public void updateReference(MergeNode mergedNode, AgentEntity agent) {
        String mergeId = mergedNode.mergedInCycle() + "::" + mergedNode.path();

        neo4jClient.query("""
        	    MERGE (a:Agent {
        	        id: $id
        	    })
        	    SET a.name = $name,
        	        a.lineagePath = $lineagePath,
        	        a.roleType = $roleType,
        	        a.spawnedInCycle = $cycle
        	    """)
        	.bind(ROOT_ID).to("rootId")
        	.bind(mergeId).to("mergeId")
        	.bindAll(Map.of(
        	    "id", agent.id(),
        	    "name", agent.name(),
        	    "lineagePath", agent.lineagePath(),
        	    "roleType", agent.roleType(),
        	    "cycle", agent.spawnedInCycle()
        	)).run();


        log.debug("🪴 Reference root updated with merge: {}", mergeId);
    }
}
