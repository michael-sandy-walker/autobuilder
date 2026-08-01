package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T2-DY: in-memory ledger of {@link FixProposal} attempts keyed by
 * {@link FixProposal#proposalHash()}. Prevents the resolver chain from re-firing a byte-identical
 * proposal that already failed re-probe, until the working tree changes under at least one of the
 * proposal's touched paths.
 *
 * <p>Observed run (`autobuilder-2026-06-03_01-42-56.log`): the same
 * {@code symbol-import:spring-web} proposal touching {@code LoanController.java + pom.xml} fired
 * 9 byte-identical times over 4 minutes; every re-probe COMPILE_ERROR. The deferred-mutation
 * ledger (T4-BE) does not cover this — it tracks LLM-authored mutations, not injector-authored
 * deterministic fixes. This ledger is the injector-side analogue.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #shouldSkip(FixProposal, String, String)} is consulted before the injector applies
 *       a proposal. Returns true when an entry exists for this proposal hash with
 *       {@code blacklistedUntilFileMutation = true} and no target file's content has changed since
 *       the last attempt. Emits {@code fix_proposal_skipped_idempotent}.</li>
 *   <li>{@link #recordAttempt(FixProposal, BuildResult, String, String)} is called after the
 *       re-probe completes. Updates attempt count + {@code lastReProbeResult}; sets
 *       {@code blacklistedUntilFileMutation = true} on the second consecutive COMPILE_ERROR.</li>
 *   <li>A subsequent merge or external edit that changes the <em>content</em> of any touched path
 *       clears the blacklist on next lookup — fresh state means the resolver gets another shot.
 *       Invalidation keys off a content digest rather than {@code mtime}: the failed-re-probe
 *       rollback rewrites the touched file (bumping its mtime) while restoring its original bytes,
 *       so an mtime-keyed blacklist would self-clear on the very rollback the resolver provoked.</li>
 * </ol>
 *
 * <p>State is process-local. Restarts begin with an empty ledger; the goal is to short-circuit
 * within-run hot loops, not provide cross-run learning. A future T4-J / graph-backed tuning
 * store could persist entries if cross-run blacklisting becomes valuable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FixAttemptLedger {

    /** Number of consecutive COMPILE_ERROR re-probes before a proposal is blacklisted. */
    static final int BLACKLIST_AFTER_FAILURES = 2;

    /**
     * Hard per-session cap on total attempts per proposal, regardless of digest invalidation.
     * Observed 2026-06-10: a fix touching {@code pom.xml} (whose content churns nearly every
     * cycle as other fixes land) had its blacklist re-cleared by the digest rule each cycle and
     * re-fired 4x. Content-change invalidation is right for cold targets and structurally
     * ineffective for hot ones; the cap bounds the damage either way.
     */
    static final int MAX_TOTAL_ATTEMPTS = 4;

    /**
     * T2-EN: run-level re-fire cap per (rule, missingToken) — distinct cycles in which the same
     * fix was applied while the RAW re-probe stayed red. Decoupled from everything above: the
     * per-proposal key includes generated content (a skeleton derived from fresh usage sites is
     * a "new" proposal every cycle), and the progress-aware assessment records a creation rule
     * as success forever (creating X always removes "cannot find symbol X"). Observed 2026-07-16:
     * skeleton-repository re-derived the same BookRepository/MemberRepository pair across 12+
     * cycles, every raw re-probe COMPILE_ERROR, zero ledger engagements. Must exceed
     * {@link #MAX_TOTAL_ATTEMPTS} so the per-proposal budget gets first say; a raw COMPILE_OK
     * resets the tally (the fix genuinely worked).
     */
    static final int MAX_RUN_LEVEL_FIRES = 6;

    private final Map<String, RunLevelEntry> runLevelFires = new ConcurrentHashMap<>();

    private final FileService fileService;

    @Setter
    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    private final Map<String, Entry> ledger = new ConcurrentHashMap<>();
    private final AtomicInteger cycleCounter = new AtomicInteger(0);

    /**
     * Returns true when the proposal has been tried before, failed re-probe at least
     * {@value #BLACKLIST_AFTER_FAILURES} times, and no target file's content has changed since.
     * Emits {@code fix_proposal_skipped_idempotent} when it returns true.
     */
    public boolean shouldSkip(FixProposal proposal, String outputPath, String cycleId) {
        if (proposal == null) return false;

        // T2-EN: run-level re-fire cap — checked BEFORE the per-proposal entry, because a fresh
        // content hash bypasses everything below (exactly how the 12-cycle skeleton recurrence
        // escaped the per-proposal blacklist).
        if (runLevelCapped(proposal)) {
            emitRunLevelSkip(proposal, cycleId);
            return true;
        }

        String key = proposal.proposalHash();
        Entry entry = ledger.get(key);
        if (entry == null) return false;

        // T1-FC: an infrastructure-bootstrap fix (e.g. bootstrap-pom) re-enables the instant its
        // target file is absent on disk — its whole job is to create that file, and neither the hard
        // cap nor content-digest invalidation rescues it (a deleted file keeps the same ABSENT digest,
        // so digestsChanged sees no change, and the hard-cap branch below returns before any disk read).
        // Without this a pom rolled back after being blacklisted could never be re-bootstrapped and the
        // run wedges on "no POM". Drop the entry so the next attempt starts with a fresh budget — the
        // build literally cannot run without this file, so re-creating it is always the right move.
        if (proposal.isInfrastructureBootstrap() && anyTargetAbsent(proposal, outputPath)) {
            ledger.remove(key);
            log.info("T1-FC: re-enabling infrastructure-bootstrap fix rule={} — target file absent on disk",
                    proposal.record().rule());
            return false;
        }

        // Hard cap: past MAX_TOTAL_ATTEMPTS the proposal is done for this session — no digest
        // invalidation can resurrect it.
        if (entry.attempts >= MAX_TOTAL_ATTEMPTS && entry.lastReProbeResult == BuildResult.COMPILE_ERROR) {
            emitSkip(proposal, entry, cycleId);
            return true;
        }
        if (!entry.blacklistedUntilFileMutation) return false;

        Map<String, String> currentDigests = readContentDigests(proposal, outputPath);
        if (digestsChanged(entry.lastSeenDigests, currentDigests)) {
            // A target file's content has changed since the last failed attempt — fresh state.
            // Clear the blacklist; next attempt is allowed (and will be recorded as attempt N+1).
            entry.blacklistedUntilFileMutation = false;
            entry.lastSeenDigests = currentDigests;
            log.debug("T2-DY: ledger key={} blacklist cleared — target content changed", key);
            return false;
        }

        emitSkip(proposal, entry, cycleId);
        return true;
    }

    private void emitSkip(FixProposal proposal, Entry entry, String cycleId) {
        int currentCycle = cycleCounter.incrementAndGet();
        int cyclesSinceFirst = Math.max(1, currentCycle - entry.firstAttemptCycle);
        log.info("T2-DY: skipping idempotent fix proposal rule={} symbol={} touched={} priorAttempts={}",
                proposal.record().rule(), proposal.record().missingToken(),
                proposal.record().touchedPaths(), entry.attempts);
        if (behaviourLogger != null) {
            try {
                behaviourLogger.fixProposalSkippedIdempotent(
                        cycleId,
                        proposal.record().rule(),
                        proposal.record().missingToken(),
                        proposal.record().touchedPaths(),
                        entry.attempts,
                        cyclesSinceFirst);
            } catch (Exception e) {
                log.debug("T2-DY: fixProposalSkippedIdempotent emit failed: {}", e.getMessage());
            }
        }
    }

    /**
     * T2-EN: records one application of the proposal against the RAW re-probe result (before the
     * progress-aware translation in {@code CompileFixInjector.recordReProbeOutcome}). Counts at
     * most one fire per (rule, token, cycle); a raw COMPILE_OK drops the tally. Returns true
     * exactly when this call crosses {@link #MAX_RUN_LEVEL_FIRES} — the caller escalates the
     * recurrence to the T2-EF redirect channel then.
     */
    public boolean recordRunLevelFire(FixProposal proposal, BuildResult rawReProbeResult, String cycleId) {
        String key = runLevelKey(proposal);
        if (key == null || rawReProbeResult == null) return false;
        // T1-FC discipline: the infrastructure bootstrap must never be capped — the build
        // literally cannot run without its file, so re-creating it is always the right move.
        if (proposal.isInfrastructureBootstrap()) return false;
        if (rawReProbeResult == BuildResult.COMPILE_OK) {
            runLevelFires.remove(key);
            return false;
        }
        if (rawReProbeResult != BuildResult.COMPILE_ERROR) return false;
        RunLevelEntry entry = runLevelFires.computeIfAbsent(key, k -> new RunLevelEntry());
        synchronized (entry) {
            if (cycleId != null && cycleId.equals(entry.lastCycleId)) {
                return false; // several diagnostics in one cycle are one fire, not several
            }
            entry.lastCycleId = cycleId;
            entry.fires++;
            if (entry.fires == MAX_RUN_LEVEL_FIRES) {
                log.warn("T2-EN: run-level re-fire cap reached for {} — {} distinct cycles without landing; "
                        + "further derivations skipped, recurrence escalated", key, entry.fires);
                return true;
            }
            return false;
        }
    }

    /**
     * T2-EZ: what the injector produced this run and how much of it the tree got to keep.
     *
     * @param created     injector-created files written during a probe whose build stayed red
     * @param preserved   how many of those survived the chain-stopped rollback
     * @param rederived   distinct (rule, token) fixes derived in more than one cycle — the
     *                    exposure the preservation is meant to shrink, kept separate from
     *                    {@code preserved} so the benefit is never inferred from the effort
     * @param worstFires  the highest per-(rule, token) cycle count reached, against the T2-EN cap
     */
    public record Readout(int created, int preserved, int rederived, int worstFires) {
        public boolean isEmpty() {
            return created == 0 && preserved == 0 && rederived == 0;
        }
    }

    private final AtomicInteger injectorFilesCreated = new AtomicInteger(0);
    private final AtomicInteger injectorFilesPreserved = new AtomicInteger(0);

    /** T2-EZ: called once per chain-stopped rollback with both sides of the trade. */
    public void noteInjectorFileOutcome(int created, int preserved) {
        if (created > 0) injectorFilesCreated.addAndGet(created);
        if (preserved > 0) injectorFilesPreserved.addAndGet(preserved);
    }

    /** T2-EZ: run-level readout — the ticket's measurement half. */
    public Readout readout() {
        int rederived = 0;
        int worst = 0;
        for (RunLevelEntry e : runLevelFires.values()) {
            synchronized (e) {
                if (e.fires > 1) rederived++;
                if (e.fires > worst) worst = e.fires;
            }
        }
        return new Readout(injectorFilesCreated.get(), injectorFilesPreserved.get(), rederived, worst);
    }

    /** Test/diagnostic accessor — run-level fire count for the proposal's (rule, token), 0 if unseen. */
    public int runLevelFires(FixProposal proposal) {
        String key = runLevelKey(proposal);
        RunLevelEntry e = key != null ? runLevelFires.get(key) : null;
        return e == null ? 0 : e.fires;
    }

    private boolean runLevelCapped(FixProposal proposal) {
        String key = runLevelKey(proposal);
        if (key == null || proposal.isInfrastructureBootstrap()) return false;
        RunLevelEntry e = runLevelFires.get(key);
        return e != null && e.fires >= MAX_RUN_LEVEL_FIRES;
    }

    private void emitRunLevelSkip(FixProposal proposal, String cycleId) {
        RunLevelEntry e = runLevelFires.get(runLevelKey(proposal));
        int fires = e == null ? MAX_RUN_LEVEL_FIRES : e.fires;
        log.info("T2-EN: skipping run-level-capped fix proposal rule={} symbol={} fires={}",
                proposal.record().rule(), proposal.record().missingToken(), fires);
        if (behaviourLogger != null) {
            try {
                // Each fire is one distinct cycle, so the fire count doubles as the cycle span.
                behaviourLogger.fixProposalSkippedIdempotent(cycleId, proposal.record().rule(),
                        proposal.record().missingToken(), proposal.record().touchedPaths(), fires, fires);
            } catch (Exception ex) {
                log.debug("T2-EN: fixProposalSkippedIdempotent emit failed: {}", ex.getMessage());
            }
        }
    }

    private static String runLevelKey(FixProposal proposal) {
        if (proposal == null || proposal.record() == null) return null;
        String rule = proposal.record().rule();
        String token = proposal.record().missingToken();
        if (rule == null || token == null || token.isBlank()) return null;
        return rule + "|" + token;
    }

    private static final class RunLevelEntry {
        int fires;
        String lastCycleId;
    }

    /**
     * Records that the proposal was applied and the re-probe produced {@code reProbeResult}.
     * Sets {@code blacklistedUntilFileMutation = true} when {@code reProbeResult == COMPILE_ERROR}
     * and the attempt counter reaches {@value #BLACKLIST_AFTER_FAILURES}. A COMPILE_OK clears the
     * blacklist outright — the proposal worked, no further skipping needed.
     */
    public void recordAttempt(FixProposal proposal, BuildResult reProbeResult, String outputPath, String cycleId) {
        if (proposal == null || reProbeResult == null) return;
        String key = proposal.proposalHash();
        Map<String, String> digests = readContentDigests(proposal, outputPath);
        int currentCycle = cycleCounter.incrementAndGet();

        ledger.compute(key, (k, existing) -> {
            if (existing == null) {
                Entry fresh = new Entry();
                fresh.firstAttemptCycle = currentCycle;
                fresh.lastAttemptCycle = currentCycle;
                fresh.attempts = 1;
                fresh.lastReProbeResult = reProbeResult;
                fresh.lastSeenDigests = digests;
                fresh.blacklistedUntilFileMutation = false;
                return fresh;
            }
            existing.lastAttemptCycle = currentCycle;
            existing.attempts++;
            existing.lastReProbeResult = reProbeResult;
            existing.lastSeenDigests = digests;
            if (reProbeResult == BuildResult.COMPILE_OK) {
                existing.blacklistedUntilFileMutation = false;
            } else if (reProbeResult == BuildResult.COMPILE_ERROR && existing.attempts >= BLACKLIST_AFTER_FAILURES) {
                existing.blacklistedUntilFileMutation = true;
                log.info("T2-DY: ledger key={} rule={} blacklisted after {} attempts (last result={})",
                        k, proposal.record().rule(), existing.attempts, reProbeResult);
            }
            return existing;
        });
    }

    /** Test/diagnostic accessor — current attempt count for the proposal, 0 if unseen. */
    public int attempts(FixProposal proposal) {
        if (proposal == null) return 0;
        Entry e = ledger.get(proposal.proposalHash());
        return e == null ? 0 : e.attempts;
    }

    /** Test/diagnostic accessor — whether the proposal is currently blacklisted. */
    public boolean isBlacklisted(FixProposal proposal) {
        if (proposal == null) return false;
        Entry e = ledger.get(proposal.proposalHash());
        return e != null && e.blacklistedUntilFileMutation;
    }

    /** Drops every ledger entry. Intended for tests; not used by production code. */
    public void clear() {
        ledger.clear();
        runLevelFires.clear();
    }

    /**
     * T1-FC: true when any of the proposal's touched paths is missing on disk. Used to re-enable an
     * infrastructure-bootstrap fix whose target file vanished (a rolled-back pom). Reads existence
     * directly rather than via the digest map so it works on the hard-cap path where no digests are
     * sampled. Unresolvable paths count as present, so a path-resolution failure never resurrects a fix.
     */
    private boolean anyTargetAbsent(FixProposal proposal, String outputPath) {
        if (proposal == null || outputPath == null) return false;
        for (FileMutation m : proposal.mutations()) {
            if (m == null || m.path() == null) continue;
            try {
                Path resolved = fileService.resolveSecurePath(outputPath + "/" + m.path());
                if (!Files.exists(resolved)) return true;
            } catch (IOException | RuntimeException e) {
                log.debug("T1-FC: anyTargetAbsent could not resolve {}: {}", m.path(), e.getMessage());
            }
        }
        return false;
    }

    /** Sentinel digest for a touched path that is not on disk. */
    private static final String ABSENT = "\0absent";
    /** Sentinel digest for a touched path that could not be read. */
    private static final String UNREADABLE = "\0unreadable";

    /**
     * Reads a content digest for each touched path. Keying invalidation off content (rather than
     * mtime) is deliberate: the failed-re-probe rollback rewrites the file with its original bytes,
     * bumping mtime while leaving content identical — so an mtime key would self-clear the blacklist.
     */
    private Map<String, String> readContentDigests(FixProposal proposal, String outputPath) {
        Map<String, String> out = new LinkedHashMap<>();
        if (proposal == null || outputPath == null) return out;
        for (FileMutation m : proposal.mutations()) {
            if (m == null || m.path() == null) continue;
            String relPath = outputPath + "/" + m.path();
            try {
                Path resolved = fileService.resolveSecurePath(relPath);
                if (Files.exists(resolved)) {
                    out.put(m.path(), digest(Files.readAllBytes(resolved)));
                } else {
                    out.put(m.path(), ABSENT);
                }
            } catch (IOException e) {
                out.put(m.path(), UNREADABLE);
            }
        }
        return out;
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a required algorithm on every JVM; fall back to a length+hashCode tag.
            return bytes.length + ":" + Arrays.hashCode(bytes);
        }
    }

    private static boolean digestsChanged(Map<String, String> before, Map<String, String> now) {
        if (before == null || now == null) return true;
        for (Map.Entry<String, String> entry : now.entrySet()) {
            String prior = before.get(entry.getKey());
            if (prior == null) return true;
            if (!prior.equals(entry.getValue())) return true;
        }
        return false;
    }

    static final class Entry {
        int firstAttemptCycle;
        int lastAttemptCycle;
        int attempts;
        BuildResult lastReProbeResult;
        Map<String, String> lastSeenDigests = Map.of();
        boolean blacklistedUntilFileMutation;
    }
}
