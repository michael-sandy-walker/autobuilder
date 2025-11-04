package com.walpro.autobuilder.graph.reference;

import com.walpro.autobuilder.agent.merge.model.MergeNode;
import com.walpro.autobuilder.graph.core.AgentEntity;

public interface ReferenceStateGraphService {
    void updateReference(MergeNode mergedNode, AgentEntity agent);
}
