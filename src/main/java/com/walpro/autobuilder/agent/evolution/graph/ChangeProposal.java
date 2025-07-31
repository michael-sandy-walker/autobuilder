package com.walpro.autobuilder.agent.evolution.graph;

public record ChangeProposal( // TODO: this is just a template which needs to be expanded - and used for graph changes
    String id,
    String targetNodeId,
    String changeType,      // e.g., "addEdge", "removeNode", "modifyContent"
    String contentSummary   // minimal delta summary (optional full diff elsewhere)
) {}
