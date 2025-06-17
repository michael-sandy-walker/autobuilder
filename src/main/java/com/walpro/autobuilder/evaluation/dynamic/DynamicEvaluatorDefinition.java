package com.walpro.autobuilder.evaluation.dynamic;

public record DynamicEvaluatorDefinition(
	    String id,
	    String role,
	    String description,
	    String behavior, // e.g. textual DSL, function definition, LLM-parsable rule set
	    boolean active
	) {}