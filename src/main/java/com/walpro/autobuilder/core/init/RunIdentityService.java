package com.walpro.autobuilder.core.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * T4-CJ slice 1: run/lineage identity. Two ids, matching the T4-CD vocabulary:
 *
 * <ul>
 *   <li>{@code lineageId} — stable across resume boots. Minted on RESET (a reset severs the
 *       lineage) and persisted in {@code meta/run-identity.json}, which a reset wipes with the
 *       rest of {@code meta/} and a T4-CD resume keeps — so inheritance and severing both fall
 *       out of the existing {@code meta/} lifecycle rather than being decided here.</li>
 *   <li>{@code runId} — minted every boot; distinguishes a parent run's writes from a resumed
 *       child's within one lineage.</li>
 * </ul>
 *
 * <p>Both ids are stamped as properties at every graph write chokepoint. Slice 1 is stamp-only:
 * no read path consults the ids (a single-run graph is trivially scoped); they exist so that
 * graph dumps accumulated from now on are already distinguishable when the purge flip (slice 2)
 * comes.
 *
 * <p>Never throws: a failed persist keeps the minted id in memory for this JVM (the graph
 * stamps stay self-consistent) and only costs lineage stability across a future resume.
 */
@Slf4j
@Service
public class RunIdentityService {

    public static final String IDENTITY_FILE = "meta/run-identity.json";

    private final FileService fileService;
    private final ObjectMapper objectMapper;
    private final String runId;
    private volatile String lineageId;

    public RunIdentityService(FileService fileService, ObjectMapper objectMapper) {
        this.fileService = fileService;
        this.objectMapper = objectMapper;
        this.runId = "run-" + UUID.randomUUID();
    }

    /** This boot's id — fixed for the JVM lifetime. */
    public String runId() {
        return runId;
    }

    /**
     * The lineage id: inherited from {@code meta/run-identity.json} when present (resume, or a
     * fresh boot over an existing tree), minted + persisted otherwise. Loaded lazily so boot
     * ordering is safe — the first graph write happens after resume-or-reset has settled
     * {@code meta/}.
     */
    public String lineageId() {
        String cached = lineageId;
        if (cached != null) return cached;
        synchronized (this) {
            if (lineageId == null) {
                lineageId = loadOrMint();
            }
            return lineageId;
        }
    }

    /**
     * T1-FM: the lineage id already persisted in {@code meta/run-identity.json}, WITHOUT
     * minting one when absent — the resume gate's ownership check must observe, not create.
     * Also returns the in-memory id when one was already minted this boot.
     */
    public java.util.Optional<String> persistedLineageId() {
        String cached = lineageId;
        if (cached != null) return java.util.Optional.of(cached);
        try {
            if (fileService.exists(IDENTITY_FILE)) {
                Identity stored = objectMapper.readValue(fileService.readFile(IDENTITY_FILE), Identity.class);
                if (stored != null && stored.lineageId() != null && !stored.lineageId().isBlank()) {
                    return java.util.Optional.of(stored.lineageId());
                }
            }
        } catch (Exception e) {
            log.debug("T1-FM: could not read {} for the ownership check: {}", IDENTITY_FILE, e.getMessage());
        }
        return java.util.Optional.empty();
    }

    /**
     * Severs the lineage: mints and persists a fresh id. Called by
     * {@link SystemResetConfig#resetSystemForce()} after {@code meta/} is recreated and before
     * the root goal is re-seeded, so every write of the new run — including the seed goals —
     * carries the new lineage.
     */
    public synchronized String renewLineage() {
        lineageId = mintAndPersist();
        log.info("T4-CJ: lineage severed by reset — new lineageId={}", lineageId);
        return lineageId;
    }

    private String loadOrMint() {
        try {
            if (fileService.exists(IDENTITY_FILE)) {
                Identity stored = objectMapper.readValue(fileService.readFile(IDENTITY_FILE), Identity.class);
                if (stored != null && stored.lineageId() != null && !stored.lineageId().isBlank()) {
                    log.info("T4-CJ: inherited lineageId={} (runId={})", stored.lineageId(), runId);
                    return stored.lineageId();
                }
            }
        } catch (Exception e) {
            log.warn("T4-CJ: could not read {} — minting a fresh lineage: {}", IDENTITY_FILE, e.getMessage());
        }
        return mintAndPersist();
    }

    private String mintAndPersist() {
        String minted = "lineage-" + UUID.randomUUID();
        try {
            fileService.ensureFolderExists("meta");
            fileService.writeFile(IDENTITY_FILE, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new Identity(minted, Instant.now().toString())));
        } catch (Exception e) {
            log.warn("T4-CJ: could not persist {} — lineageId={} lives in-memory only this run: {}",
                    IDENTITY_FILE, minted, e.getMessage());
        }
        return minted;
    }

    public record Identity(String lineageId, String mintedAt) {
    }
}
