package com.walpro.autobuilder.tools.gena;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class FileHashTracker {

    private static final String CACHE_PATH = "gena/cache/filehashes.json";
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, String> previousHashes = new HashMap<>();
    private final Map<String, String> currentHashes = new HashMap<>();

    public FileHashTracker() {
        loadCache();
    }

    public void track(String filePath, String sha1) {
        currentHashes.put(filePath, sha1);
    }

    public boolean hasChanged(String filePath) {
        return !sha1Equals(previousHashes.get(filePath), currentHashes.get(filePath));
    }

    public void save() {
        try {
            File f = new File(CACHE_PATH);
            f.getParentFile().mkdirs();
            mapper.writeValue(f, currentHashes);
        } catch (Exception e) {
            System.err.println("Failed to save hash cache: " + e.getMessage());
        }
    }

    private void loadCache() {
        try {
            File f = new File(CACHE_PATH);
            if (f.exists()) {
                Map<String, String> loaded = mapper.readValue(f, new TypeReference<>() {});
                previousHashes.putAll(loaded);
            }
        } catch (Exception e) {
            System.err.println("Failed to load hash cache: " + e.getMessage());
        }
    }

    private boolean sha1Equals(String a, String b) {
        return a != null && a.equals(b);
    }
}
