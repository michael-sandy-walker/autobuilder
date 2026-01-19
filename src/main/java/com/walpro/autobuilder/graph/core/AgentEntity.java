package com.walpro.autobuilder.graph.core;

public record AgentEntity(
	    String id,
	    String name,
	    String lineagePath, // e.g. "MetaAgent.MetaAgent2.ScoreOptimizer" // TODO: MetaAgent layer removed for now
	    String roleType,    // e.g. "evaluation", "evolution", "planner"
	    String spawnedInCycle,
	    String status // "active", "inactive", "proposed"
	) {}

