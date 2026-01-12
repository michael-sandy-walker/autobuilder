package com.walpro.autobuilder.tools.tracker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Getter
@RequiredArgsConstructor
public abstract class AbstractHashTracker {

    private final ObjectMapper mapper;
    private final FileService fileService;

    private final Map<String, String> previousHashes = new HashMap<>();
    private final Map<String, String> currentHashes = new HashMap<>();

    private File storeFile;

    protected void loadCache(String cachePath) {
        try {
            this.storeFile = resolveStoreFile(cachePath);

            if (storeFile.exists()) {
                Map<String, String> loaded = mapper.readValue(storeFile, new TypeReference<>() {});
                previousHashes.putAll(loaded);
            }
        } catch (Exception e) {
            log.warn("Failed to load hash cache: {}", e.getMessage());
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
            if (storeFile == null) {
                log.warn("Cannot save: cache path not set.");
                return;
            }

            File parent = storeFile.getParentFile();
            if (parent != null) parent.mkdirs();

            mapper.writeValue(storeFile, currentHashes);
        } catch (Exception e) {
            log.warn("Failed to save hash cache: {}", e.getMessage());
        }
    }

    public void setCachePath(String cachePath) {
        try {
            this.storeFile = resolveStoreFile(cachePath);
        } catch (Exception e) {
            log.warn("Failed to resolve cache path: {}", e.getMessage());
        }
    }

    private File resolveStoreFile(String cachePath) throws Exception {
        if (fileService != null) {
            return fileService.resolveSecurePath(cachePath).toFile();
        } else {
            return new File(cachePath);
        }
    }

    private boolean equalsHash(String a, String b) {
        return Objects.equals(a, b);
    }
}
