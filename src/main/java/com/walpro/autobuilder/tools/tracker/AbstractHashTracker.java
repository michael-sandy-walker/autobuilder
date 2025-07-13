package com.walpro.autobuilder.tools.tracker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Getter
public abstract class AbstractHashTracker {

    private final File storeFile;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, String> previousHashes = new HashMap<>();
    private final Map<String, String> currentHashes = new HashMap<>();

    protected AbstractHashTracker(String cachePath) {
        this.storeFile = new File(cachePath);
        loadCache();
    }

    private void loadCache() {
        try {
            if (storeFile.exists()) {
                Map<String, String> loaded = mapper.readValue(storeFile, new TypeReference<>() {});
                previousHashes.putAll(loaded);
            }
        } catch (Exception e) {
            System.err.println("⚠️ Failed to load hash cache: " + e.getMessage());
        }
    }

    public void track(String key, String hash) {
        currentHashes.put(key, hash);
    }

    public boolean hasChanged(String key) {
        return !equalsHash(previousHashes.get(key), currentHashes.get(key));
    }

    public void save() {
        try {
            storeFile.getParentFile().mkdirs();
            mapper.writeValue(storeFile, currentHashes);
        } catch (Exception e) {
            System.err.println("⚠️ Failed to save hash cache: " + e.getMessage());
        }
    }

    private boolean equalsHash(String a, String b) {
        return a != null && a.equals(b);
    }
}
