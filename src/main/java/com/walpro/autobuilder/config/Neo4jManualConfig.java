package com.walpro.autobuilder.config;

import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class Neo4jManualConfig {

    @Bean
    public Driver driver(Environment env) {
        String uri = env.getProperty("spring.data.neo4j.uri");
        String username = env.getProperty("spring.data.neo4j.username");
        String password = env.getProperty("spring.data.neo4j.password");

        AuthToken authToken = AuthTokens.basic(username, password);
        return GraphDatabase.driver(uri, authToken);
    }
}