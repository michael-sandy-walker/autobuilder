package com.walpro.autobuilder.testsupport;

import com.walpro.autobuilder.core.service.FileService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T5-AF: reusable builder for a known {@code output/} artifact used by the fault-injection
 * validation harness. The completion-edge and rollback safety gates only fire when the LLM
 * misbehaves — which is nondeterministic and un-summonable — so their FAILURE paths stay
 * field-unvalidated. This builder lets a test stage a <em>known</em> artifact (hollow or real) on
 * demand so the gate mechanism can be driven and asserted deterministically.
 *
 * <p>Two surfaces, used by the two harness layers:
 * <ul>
 *   <li>{@link #asMap()} — the in-memory {@code path → content} map fed to
 *       {@code CycleContextData.previousOutput()} (what the completion verifier reads). Layer 1
 *       (gate plumbing) and layer 2 (verifier precision) both consume this.</li>
 *   <li>{@link #writeTo(FileService, String)} — seeds the same files onto a temp {@code output/}
 *       on disk for the rollback / ledger gates, which read existence and content from disk.</li>
 * </ul>
 *
 * <p>Stateless and content-only — it bakes no target-project knowledge into production code; the
 * named artifacts live in {@link HollowVsRealFixtures}. Fluent so a test can compose its own.
 */
public final class OutputFixtureBuilder {

    private final Map<String, String> files = new LinkedHashMap<>();

    public static OutputFixtureBuilder builder() {
        return new OutputFixtureBuilder();
    }

    /** Adds (or overwrites) one file, keyed by its path relative to {@code output/}. */
    public OutputFixtureBuilder file(String relPath, String content) {
        files.put(relPath, content);
        return this;
    }

    /** Adds all of {@code more}, keyed by path relative to {@code output/}. */
    public OutputFixtureBuilder files(Map<String, String> more) {
        if (more != null) files.putAll(more);
        return this;
    }

    /** The in-memory {@code path → content} map — feed to {@code CycleContextData.previousOutput()}. */
    public Map<String, String> asMap() {
        return new LinkedHashMap<>(files);
    }

    /**
     * Writes every staged file under {@code <outputPath>/} via {@code fileService} and returns the
     * same map {@link #asMap()} would, so a test can seed disk and the context in one call.
     */
    public Map<String, String> writeTo(FileService fileService, String outputPath) throws IOException {
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path resolved = fileService.resolveSecurePath(outputPath + "/" + e.getKey());
            fileService.writeFileResolved(resolved, e.getValue());
        }
        return asMap();
    }
}
