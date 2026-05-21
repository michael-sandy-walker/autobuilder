package com.walpro.autobuilder.core.build.inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.Resolution;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolImportCacheTest {

    @TempDir Path tmp;

    private FileService fileService;
    private SymbolImportCache cache;

    @BeforeEach
    void setUp() {
        fileService = new FileService(tmp.toString(), new ObjectMapper());
        cache = new SymbolImportCache(fileService);
        ReflectionTestUtils.setField(cache, "cachePath", "cache/symbol-imports.json");
        cache.load();
    }

    @Test
    void getOnUnknownSymbol_returnsNull() {
        assertThat(cache.get("Nothing")).isNull();
        assertThat(cache.contains("Nothing")).isFalse();
    }

    @Test
    void putHit_persistsAndReloads() throws Exception {
        Resolution r = new Resolution("p.q.X", "g", "a", null);
        cache.putHit("X", r);

        assertThat(cache.get("X")).contains(r);
        assertThat(cache.contains("X")).isTrue();

        SymbolImportCache fresh = new SymbolImportCache(fileService);
        ReflectionTestUtils.setField(fresh, "cachePath", "cache/symbol-imports.json");
        fresh.load();
        assertThat(fresh.get("X")).contains(r);
    }

    @Test
    void putMiss_returnsEmptyOptional_distinctFromAbsent() {
        cache.putMiss("Mystery");
        Optional<Resolution> entry = cache.get("Mystery");
        assertThat(entry).isNotNull();
        assertThat(entry).isEmpty();
        assertThat(cache.contains("Mystery")).isTrue();
    }

    @Test
    void invalidate_removesEntry() {
        cache.putHit("X", new Resolution("p.q.X", "g", "a", null));
        cache.invalidate("X");
        assertThat(cache.get("X")).isNull();
    }

    @Test
    void load_corruptCacheFile_recoversEmpty() throws Exception {
        Path cachePath = tmp.resolve("cache/symbol-imports.json");
        Files.createDirectories(cachePath.getParent());
        Files.writeString(cachePath, "{ not valid json at all");

        SymbolImportCache fresh = new SymbolImportCache(fileService);
        ReflectionTestUtils.setField(fresh, "cachePath", "cache/symbol-imports.json");
        fresh.load();

        assertThat(fresh.size()).isZero();
        assertThat(fresh.get("anything")).isNull();
    }

    @Test
    void load_emptyFile_recoversEmpty() throws Exception {
        Path cachePath = tmp.resolve("cache/symbol-imports.json");
        Files.createDirectories(cachePath.getParent());
        Files.writeString(cachePath, "");

        SymbolImportCache fresh = new SymbolImportCache(fileService);
        ReflectionTestUtils.setField(fresh, "cachePath", "cache/symbol-imports.json");
        fresh.load();

        assertThat(fresh.size()).isZero();
    }

    @Test
    void putHitThenInvalidate_persistsRemoval() throws Exception {
        cache.putHit("X", new Resolution("p.q.X", null, null, null));
        cache.invalidate("X");

        SymbolImportCache fresh = new SymbolImportCache(fileService);
        ReflectionTestUtils.setField(fresh, "cachePath", "cache/symbol-imports.json");
        fresh.load();
        assertThat(fresh.get("X")).isNull();
    }
}
