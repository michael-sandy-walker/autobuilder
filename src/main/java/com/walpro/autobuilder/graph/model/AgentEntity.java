package com.walpro.autobuilder.graph.model;

public record AgentEntity(
	    String id,
	    String name,
	    String lineagePath, // e.g. "MetaAgent.MetaAgent2.ScoreOptimizer"
	    String roleType,    // e.g. "evaluation", "evolution", "planner"
	    String spawnedInCycle,
	    String status // "active", "inactive", "proposed"
	) {}

