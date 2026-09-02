package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

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
 *
 * <p><b>T4-BM step 6: entries are keyed to the pom's dependency context.</b> An LLM answer (hit
 * or NONE) is only as good as the dependency set it was asked under — adding, removing, or
 * re-versioning a dependency changes what the right answer is, and a cache that outlives its
 * context re-serves stale worldviews (field: an SB2-era {@code JpaRepository} pin surviving
 * across runs). The persisted file carries the {@link PomDependencyEditor#dependencyFingerprint}
 * it was written under; a mismatch on load or on any access after a pom change clears the cache.
 * Unrelated pom edits (properties, plugins, whitespace) leave the fingerprint — and the cache —
 * untouched.
 *
 * <p><b>T4-CD slice 3: an ABSENT pom is an unknown context, not a different one.</b> After a
 * reset (which no longer wipes {@code cache/}) the boot sees no {@code output/pom.xml} yet — the
 * bootstrap seeds it moments later. Resolutions are ecosystem facts valid FOR THEIR RECORDED
 * dependency context, so the load keeps them dormant under that context and the first real
 * fingerprint decides: equal → the knowledge carries across the run boundary; different → dropped
 * exactly as before. The pollution classes that motivated the old wipe-on-reset are each closed
 * by later mechanisms (member-token gate at write, BOM-managed version omission at the pom-write
 * home, discovery-first ordering) — see the T4-CD slice-3 precondition analysis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StateHolder(value = StateBucket.ALREADY_PERSISTED,
        note = "its own store file under cache/, keyed by dependency fingerprint")
public class SymbolImportCache {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    private static final TypeReference<Map<String, Resolution>> MAP_TYPE = new TypeReference<>() {};

    private final FileService fileService;

    @Value("${autobuilder.build.symbol-import.cache-path:cache/symbol-imports.json}")
    private String cachePath;

    @Value("${autobuilder.build.symbol-import.pom-path:output/pom.xml}")
    private String pomPath;

    /** In-memory map. Loaded once from disk on construction; persisted on every mutating call. */
    private final Map<String, Optional<Resolution>> entries = new ConcurrentHashMap<>();
    private boolean loaded = false;

    /** The dependency fingerprint the current entries were resolved under. */
    private volatile String dependencyContext = "";
    /** Pom mtime at the last fingerprint computation — recompute only when the file moved. */
    private volatile long pomMtimeMs = Long.MIN_VALUE;

    /** Persisted shape: the entries plus the dependency context they were written under. */
    record PersistedCache(String dependencyContext, Map<String, Resolution> entries) {}

    @PostConstruct
    void load() {
        dependencyContext = currentDependencyFingerprint();
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
            PersistedCache persisted = readPersisted(json);
            entries.clear();
            if (persisted == null) {
                log.info("SymbolImportCache: {} has no dependency context (legacy format) — starting empty", cachePath);
            } else if (!pomPresent() && persisted.dependencyContext() != null
                    && !persisted.dependencyContext().isEmpty()) {
                // T4-CD slice 3: no pom FILE on disk yet (fresh reset, bootstrap not seeded) —
                // adopt the entries dormant under their recorded context; ensureContextFresh()
                // drops them if the pom that appears carries a different dependency set. A pom
                // that EXISTS with zero dependencies is a real (different) context, not unknown.
                dependencyContext = persisted.dependencyContext();
                if (persisted.entries() != null) {
                    persisted.entries().forEach((k, v) -> entries.put(k, Optional.ofNullable(v)));
                }
                log.info("SymbolImportCache: no pom yet — carrying {} entries dormant under their recorded dependency context", entries.size());
            } else if (!dependencyContext.equals(persisted.dependencyContext())) {
                log.info("SymbolImportCache: dependency context changed since {} was written — dropping {} stale entries",
                        cachePath, persisted.entries() == null ? 0 : persisted.entries().size());
            } else if (persisted.entries() != null) {
                persisted.entries().forEach((k, v) -> entries.put(k, Optional.ofNullable(v)));
                log.info("SymbolImportCache: loaded {} entries from {}", entries.size(), cachePath);
            }
            loaded = true;
        } catch (IOException | RuntimeException e) {
            log.warn("SymbolImportCache: failed to read {} ({}) — starting empty", cachePath, e.getMessage());
            entries.clear();
            loaded = true;
        }
    }

    /** New format first; a legacy plain-map file (no context to trust) yields {@code null}. */
    private PersistedCache readPersisted(String json) {
        try {
            var node = MAPPER.readTree(json);
            if (node.has("entries")) {
                Map<String, Resolution> raw = MAPPER.convertValue(node.get("entries"), MAP_TYPE);
                String context = node.path("dependencyContext").asText("");
                return new PersistedCache(context, raw);
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Recomputes the pom fingerprint when the pom file changed; a moved fingerprint clears the
     * cache (every cached answer was given under a dependency set that no longer exists).
     */
    private void ensureContextFresh() {
        long mtime = pomMtime();
        if (mtime == pomMtimeMs) return;
        pomMtimeMs = mtime;
        // T4-CD slice 3: pom FILE absent = unknown context (mid-write, or pre-bootstrap after
        // a reset) — retain the entries under their recorded context; only a pom that exists
        // and fingerprints differently invalidates them (an existing pom with zero declared
        // dependencies is such a different context).
        if (!pomPresent()) return;
        String fingerprint = currentDependencyFingerprint();
        if (!fingerprint.equals(dependencyContext)) {
            int dropped = entries.size();
            dependencyContext = fingerprint;
            if (dropped > 0) {
                entries.clear();
                persist();
                log.info("SymbolImportCache: dependency set changed — dropped {} entries resolved under the old context", dropped);
            }
        }
    }

    private boolean pomPresent() {
        if (pomPath == null || pomPath.isBlank()) return false;
        try {
            return fileService.exists(pomPath);
        } catch (IOException e) {
            return false;
        }
    }

    private long pomMtime() {
        if (pomPath == null || pomPath.isBlank()) return 0L;
        try {
            Path pom = fileService.resolveSecurePath(pomPath);
            return Files.exists(pom) ? Files.getLastModifiedTime(pom).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private String currentDependencyFingerprint() {
        if (pomPath == null || pomPath.isBlank()) return "";
        try {
            if (!fileService.exists(pomPath)) return "";
            return PomDependencyEditor.dependencyFingerprint(fileService.readString(pomPath));
        } catch (IOException e) {
            return "";
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
        ensureContextFresh();
        return entries.get(simpleName);
    }

    public boolean contains(String simpleName) {
        if (simpleName == null) return false;
        ensureContextFresh();
        return entries.containsKey(simpleName);
    }

    public void putHit(String simpleName, Resolution resolution) {
        if (simpleName == null || resolution == null) return;
        ensureContextFresh();
        entries.put(simpleName, Optional.of(resolution));
        persist();
    }

    public void putMiss(String simpleName) {
        if (simpleName == null) return;
        ensureContextFresh();
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
            String json = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new PersistedCache(dependencyContext, serialisable));
            Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("SymbolImportCache: failed to persist {} — {}", cachePath, e.getMessage());
        }
    }
}
