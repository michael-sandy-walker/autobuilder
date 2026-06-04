package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A deterministic fix a {@link CompileFixResolver} proposes when classification produced a
 * known build-error kind. Pairs the concrete on-disk change ({@link FileMutation}s) with the
 * provenance metadata ({@link InjectorFixRecord}) that explains where the change came from.
 *
 * <p>The two halves move together: {@code mutations} is what gets written; {@code record} is
 * what gets persisted/logged/surfaced to LLM agents.
 */
public record FixProposal(
        List<FileMutation> mutations,
        InjectorFixRecord record
) {
    public FixProposal {
        if (mutations == null || mutations.isEmpty())
            throw new IllegalArgumentException("at least one mutation required");
        if (record == null) throw new IllegalArgumentException("provenance record required");
        mutations = List.copyOf(mutations);
    }

    public Kind kind() {
        return record.kind();
    }

    /**
     * T2-DY: stable identifier for a proposal — SHA-256 of {@code rule + sorted touched paths +
     * sorted edit content}, truncated to 16 hex chars. Two byte-identical proposals against the
     * same paths produce identical hashes; the {@link FixAttemptLedger} keys idempotency decisions
     * on this so a known-failing fix doesn't re-fire every cycle (run evidence:
     * {@code symbol-import:spring-web} fired 9× identical in 2026-06-03_01-42-56).
     */
    public String proposalHash() {
        return computeProposalHash(record.rule(), mutations);
    }

    static String computeProposalHash(String rule, List<FileMutation> mutations) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((rule == null ? "" : rule).getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);

            List<String> sortedPaths = new ArrayList<>();
            for (FileMutation m : mutations) {
                sortedPaths.add(m == null || m.path() == null ? "" : m.path());
            }
            Collections.sort(sortedPaths);
            for (String p : sortedPaths) {
                sha.update(p.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }

            List<String> sortedContent = new ArrayList<>();
            for (FileMutation m : mutations) {
                sortedContent.add(m == null || m.content() == null ? "" : m.content());
            }
            Collections.sort(sortedContent);
            for (String c : sortedContent) {
                sha.update(c.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }

            byte[] digest = sha.digest();
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JVM; if it isn't, fall through to a
            // weaker stable hash rather than crashing — the ledger's correctness then degrades
            // to deduping by rule alone, which is still better than no deduping at all.
            return "no-sha-" + Integer.toHexString((rule == null ? 0 : rule.hashCode()));
        }
    }
}
