package com.walpro.autobuilder.tools.gena;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.*;

public class GoalOverlayLoader {

    private final Map<String, List<String>> goalMap = new HashMap<>();

    public GoalOverlayLoader() {
        load();
    }

    private void load() {
        File f = new File("gena/overlay/goals.json");
        if (!f.exists()) return;
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, List<String>> loaded = mapper.readValue(f, new TypeReference<>() {});
            goalMap.putAll(loaded);
        } catch (Exception e) {
            System.err.println("Failed to load goals.json: " + e.getMessage());
        }
    }

    public List<String> getGoalsFor(String fullClassName) {
        String simpleName = fullClassName.contains(".")
                ? fullClassName.substring(fullClassName.lastIndexOf('.') + 1)
                : fullClassName;
        return goalMap.getOrDefault(simpleName, List.of());
    }
}
