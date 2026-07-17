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

    // ── T4-BM step 6: dependency-context keying ─────────────────────────────

    private static final String POM_ONE_DEP = """
            <project><dependencies>
              <dependency><groupId>g1</groupId><artifactId>a1</artifactId><version>1.0</version></dependency>
            </dependencies></project>
            """;

    private SymbolImportCache freshCacheWithPom() {
        SymbolImportCache c = new SymbolImportCache(fileService);
        ReflectionTestUtils.setField(c, "cachePath", "cache/symbol-imports.json");
        ReflectionTestUtils.setField(c, "pomPath", "output/pom.xml");
        c.load();
        return c;
    }

    private void writePom(String content) throws Exception {
        Path pom = tmp.resolve("output/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, content);
        // mtime granularity on fast filesystems: force a visible change.
        Files.setLastModifiedTime(pom,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + counter++));
    }

    private static long counter = 1;

    @Test
    void dependencyChange_dropsEveryEntry() throws Exception {
        writePom(POM_ONE_DEP);
        SymbolImportCache keyed = freshCacheWithPom();
        keyed.putHit("X", new Resolution("p.q.X", "g", "a", null));
        keyed.putMiss("Y");

        writePom("""
                <project><dependencies>
                  <dependency><groupId>g1</groupId><artifactId>a1</artifactId><version>1.0</version></dependency>
                  <dependency><groupId>g2</groupId><artifactId>a2</artifactId><version>2.0</version></dependency>
                </dependencies></project>
                """);

        assertThat(keyed.get("X")).as("hit resolved under the old dependency set").isNull();
        assertThat(keyed.get("Y")).as("NONE answered under the old dependency set").isNull();
    }

    @Test
    void unrelatedPomEdit_keepsEntries() throws Exception {
        // The T2-DY lesson: raw-digest keying re-fires on every pom touch. Only the declared
        // dependency set is the resolution context — properties/plugins/whitespace are not.
        writePom(POM_ONE_DEP);
        SymbolImportCache keyed = freshCacheWithPom();
        Resolution r = new Resolution("p.q.X", "g", "a", null);
        keyed.putHit("X", r);

        writePom("""
                <project>
              <properties><java.version>21</java.version></properties>
              <dependencies>
              <dependency><groupId>g1</groupId><artifactId>a1</artifactId><version>1.0</version></dependency>
            </dependencies></project>
            """);

        assertThat(keyed.get("X")).contains(r);
    }

    @Test
    void reloadUnderTheSameDependencySet_keepsEntries() throws Exception {
        writePom(POM_ONE_DEP);
        SymbolImportCache keyed = freshCacheWithPom();
        Resolution r = new Resolution("p.q.X", "g", "a", null);
        keyed.putHit("X", r);

        SymbolImportCache reloaded = freshCacheWithPom();
        assertThat(reloaded.get("X")).contains(r);
    }

    @Test
    void reloadUnderAChangedDependencySet_startsEmpty() throws Exception {
        writePom(POM_ONE_DEP);
        SymbolImportCache keyed = freshCacheWithPom();
        keyed.putHit("X", new Resolution("p.q.X", "g", "a", null));

        writePom("<project><dependencies></dependencies></project>");
        SymbolImportCache reloaded = freshCacheWithPom();
        assertThat(reloaded.get("X")).isNull();
    }

    // ── T4-CD slice 3: ecosystem knowledge survives the reset boundary ──────

    @Test
    void loadWithNoPomYet_carriesEntriesDormant_servedOnceTheSamePomAppears() throws Exception {
        // The reset no longer wipes cache/, but boot runs BEFORE bootstrap seeds the pom —
        // an absent pom is an unknown context, not a different one.
        writePom(POM_ONE_DEP);
        SymbolImportCache keyed = freshCacheWithPom();
        Resolution r = new Resolution("p.q.X", "g", "a", null);
        keyed.putHit("X", r);

        Files.delete(tmp.resolve("output/pom.xml")); // the reset wipes output/, not cache/
        SymbolImportCache rebooted = freshCacheWithPom();

        writePom(POM_ONE_DEP); // bootstrap re-seeds the same dependency set
        assertThat(rebooted.get("X")).as("same dependency set — the knowledge carries").contains(r);
    }

    @Test
    void loadWithNoPomYet_dropsEntriesWhenADifferentPomAppears() throws Exception {
        writePom(POM_ONE_DEP);
        SymbolImportCache keyed = freshCacheWithPom();
        keyed.putHit("X", new Resolution("p.q.X", "g", "a", null));

        Files.delete(tmp.resolve("output/pom.xml"));
        SymbolImportCache rebooted = freshCacheWithPom();

        writePom("""
                <project><dependencies>
                  <dependency><groupId>g9</groupId><artifactId>a9</artifactId><version>9.0</version></dependency>
                </dependencies></project>
                """);
        assertThat(rebooted.get("X")).as("different dependency set — recorded context invalid").isNull();
    }

    @Test
    void midRunPomDeletion_retainsEntriesUntilARealFingerprintDiffers() throws Exception {
        writePom(POM_ONE_DEP);
        SymbolImportCache keyed = freshCacheWithPom();
        Resolution r = new Resolution("p.q.X", "g", "a", null);
        keyed.putHit("X", r);

        Files.delete(tmp.resolve("output/pom.xml")); // e.g. a mutation staged mid-write
        assertThat(keyed.get("X")).as("absent pom = unknown context, retained").contains(r);

        writePom(POM_ONE_DEP); // pom restored identical
        assertThat(keyed.get("X")).contains(r);
    }

    @Test
    void legacyPlainMapFile_carriesNoContext_startsEmpty() throws Exception {
        Path cacheFile = tmp.resolve("cache/symbol-imports.json");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, """
                { "JpaRepository": { "fqn": "org.springframework.data.jpa.repository.JpaRepository",
                    "groupId": "org.springframework.data", "artifactId": "spring-data-jpa", "version": "2.7.0" } }
                """);

        SymbolImportCache fresh = freshCacheWithPom();
        assertThat(fresh.get("JpaRepository")).isNull();
        assertThat(fresh.size()).isZero();
    }
}
