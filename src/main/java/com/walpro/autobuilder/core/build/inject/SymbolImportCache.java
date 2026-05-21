package com.walpro.autobuilder.core.build.inject;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.Resolution;
import com.walpro.autobuilder.core.service.FileService;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T4-AZ: persistent on-disk cache for {@link SymbolImportLookupService} resolutions. Stores both
 * positive hits and negative answers (LLM said NONE) so repeated misses don't incur repeated
 * round-trips. The file is human-readable JSON; manual edits clear bad entries.
 *
 * <p>Concurrency: cycles run serially so a {@link ConcurrentHashMap} is sufficient for in-process
 * coherence. Writes are atomic via {@code tmp + rename} so a crash mid-write leaves either the
 * old state or the new state, never garbage.
 *
 * <p>An entry maps a Java simple name to either a {@link Resolution} or {@code null}. The null
 * marker is the canonical "asked the LLM, it returned NONE" signal — distinct from absent (never
 * asked).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SymbolImportCache {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    private static final TypeReference<Map<String, Resolution>> MAP_TYPE = new TypeReference<>() {};

    private final FileService fileService;

    @Value("${autobuilder.build.symbol-import.cache-path:cache/symbol-imports.json}")
    private String cachePath;

    /** In-memory map. Loaded once from disk on construction; persisted on every mutating call. */
    private final Map<String, Optional<Resolution>> entries = new ConcurrentHashMap<>();
    private boolean loaded = false;

    @PostConstruct
    void load() {
        try {
            if (!fileService.exists(cachePath)) {
                loaded = true;
                return;
            }
            String json = fileService.readString(cachePath);
            if (json == null || json.isBlank()) {
                loaded = true;
                return;
            }
            Map<String, Resolution> raw = MAPPER.readValue(json, MAP_TYPE);
            entries.clear();
            raw.forEach((k, v) -> entries.put(k, Optional.ofNullable(v)));
            loaded = true;
            log.info("SymbolImportCache: loaded {} entries from {}", entries.size(), cachePath);
        } catch (IOException | RuntimeException e) {
            log.warn("SymbolImportCache: failed to read {} ({}) — starting empty", cachePath, e.getMessage());
            entries.clear();
            loaded = true;
        }
    }

    /**
     * Three-state return:
     * <ul>
     *   <li>{@code null} — never asked; caller should resolve.</li>
     *   <li>{@code Optional.empty()} — asked, answered NONE; caller should not re-ask.</li>
     *   <li>{@code Optional.of(Resolution)} — cached positive resolution.</li>
     * </ul>
     */
    public Optional<Resolution> get(String simpleName) {
        if (simpleName == null) return null;
        return entries.get(simpleName);
    }

    public boolean contains(String simpleName) {
        return simpleName != null && entries.containsKey(simpleName);
    }

    public void putHit(String simpleName, Resolution resolution) {
        if (simpleName == null || resolution == null) return;
        entries.put(simpleName, Optional.of(resolution));
        persist();
    }

    public void putMiss(String simpleName) {
        if (simpleName == null) return;
        entries.put(simpleName, Optional.empty());
        persist();
    }

    /** Removes any prior entry, hit or miss, so subsequent lookups re-resolve. */
    public void invalidate(String simpleName) {
        if (simpleName == null) return;
        if (entries.remove(simpleName) != null) {
            persist();
        }
    }

    public int size() {
        return entries.size();
    }

    private void persist() {
        try {
            Path target = fileService.resolveSecurePath(cachePath);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Resolution> serialisable = new HashMap<>();
            entries.forEach((k, v) -> serialisable.put(k, v.orElse(null)));
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(serialisable);
            Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("SymbolImportCache: failed to persist {} — {}", cachePath, e.getMessage());
        }
    }
}
