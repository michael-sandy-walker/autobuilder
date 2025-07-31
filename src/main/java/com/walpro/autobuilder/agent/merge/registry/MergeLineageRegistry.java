package com.walpro.autobuilder.agent.merge.registry;

import com.walpro.autobuilder.agent.merge.model.MergeNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class MergeLineageRegistry {

    private final Map<String, List<MergeNode>> lineageMap = new HashMap<>();

    public void register(MergeNode node) {
        lineageMap.computeIfAbsent(node.path(), k -> new ArrayList<>()).add(node);
        log.debug("🔗 Registered merge node for {}: {}", node.path(), node);
    }

    public List<MergeNode> getLineageForPath(String path) {
        return lineageMap.getOrDefault(path, List.of());
    }

    public void clear() {
        lineageMap.clear();
    }

    public Map<String, List<MergeNode>> snapshot() {
        return Map.copyOf(lineageMap);
    }
}
