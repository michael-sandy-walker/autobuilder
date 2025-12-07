package com.walpro.autobuilder.protocol.meta;

import java.util.Optional;

public class MetaResponseParser {

    public record AgentSpawnProposal(
    	    String name,
    	    String roleType,
    	    Optional<String> goalId//,
    	    //String action
    ) {}

    public AgentSpawnProposal parse(String content) {
        String name = extract(content, "name");
        String roleType = extract(content, "roleType");
        String goalId = extract(content, "goalId");
//        String action = keyExists(content, "action") ? extract(content, "action").toLowerCase() : "replace";

        return new AgentSpawnProposal(
            name,
            roleType,
            goalId.isBlank() ? Optional.empty() : Optional.of(goalId)//,
            //action
        );
    }

    private String extract(String content, String key) {
        return content.lines()
            .filter(line -> line.trim().toLowerCase().startsWith(key.toLowerCase() + ":"))
            .map(line -> line.split(":", 2))
            .filter(parts -> parts.length == 2)
            .map(parts -> parts[1].trim())
            .findFirst()
            .orElse("");
    }
}
