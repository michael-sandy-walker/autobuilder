package com.walpro.autobuilder.config;

import com.walpro.memory.graph.GraphStore;
import com.walpro.memory.graph.InMemoryGraphStore;
import com.walpro.memory.graph.neo4j.Neo4jGraphStore;
import com.walpro.memory.ingest.GraphWriter;
import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class SemanticMemoryConfig {

    /**
     * Default: in-memory graph store (fast, deterministic, test-friendly)
     */
    @Bean
    @Profile({"default", "memory", "test", "dev-local"})
    public GraphStore inMemoryGraphStore() {
        return new InMemoryGraphStore();
    }

    /**
     * Neo4j-backed graph store
     */
    @Bean
    @Profile({"neo4j", "dev-auto"})
    public GraphStore neo4jGraphStore(Driver driver) {
        return new Neo4jGraphStore(driver);
    }

    @Bean
    public GraphWriter graphWriter(GraphStore store) {
        return new GraphWriter(store);
    }
}
