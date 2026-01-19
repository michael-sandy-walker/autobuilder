package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.util.HashingUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class FingerprintService {

    /**
     * Backwards-compatible method signature used across the codebase today.
     * It now returns a stable sha256 fingerprint over (path + normalized content hash).
     */
    protected String computeFingerprint(List<FileMutation> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }

        List<String> tokens = files.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(FileMutation::path, Comparator.nullsFirst(String::compareTo)))
                .map(f -> {
                    String path = safe(f.path());
                    String content = normalize(safe(f.content()));
                    // store hashed content to avoid gigantic fingerprints while staying stable
                    return "C|" + path + "|" + HashingUtils.hashSha256(content);
                })
                .toList();

        return HashingUtils.hashSha256(String.join("\n", tokens));
    }

    /**
     * New: fingerprint the full evolution proposal, including deletions.
     * This is the preferred API for repetition detection.
     */
    public String computeEvolutionFingerprint(EvolutionOutcome outcome) {
        if (outcome == null) {
            return null;
        }

        List<String> tokens = new ArrayList<>();

        // Added/changed: path + normalized content hash
        List<FileMutation> changed = outcome.addedOrChangedFiles() == null ? List.of() : outcome.addedOrChangedFiles();
        tokens.add("CHANGED=" + computeFingerprint(changed));

        // Deleted: include paths (sorted)
        List<String> deleted = outcome.deletedFiles() == null ? List.of() : outcome.deletedFiles();
        String deletedToken = deleted.stream()
                .filter(Objects::nonNull)
                .sorted()
                .map(p -> "D|" + p)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        tokens.add("DELETED=" + HashingUtils.hashSha256(deletedToken));

        return HashingUtils.hashSha256(String.join("\n", tokens));
    }

    /**
     * Normalize content to avoid whitespace-only churn.
     * Keep this intentionally minimal/stable.
     */
    public String normalize(String content) {
        if (content == null) return "";
        return content
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim()
                .replaceAll("[\\t\\n\\f\\r ]+", ""); // keep behaviour close to legacy
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
