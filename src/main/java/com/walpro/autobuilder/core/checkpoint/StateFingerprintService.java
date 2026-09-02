package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * T4-DQ decision 4: computes {@link StateFingerprint}s, so completeness is a property that can be
 * falsified rather than an inventory somebody hopes is complete.
 *
 * <p>Used two ways, and the first is why it earns its place in production rather than in a test
 * harness. Every checkpoint records the fingerprint of the state it captured; every rewind
 * recomputes it after restoring and compares. Uncaptured state that MATTERS shows up there, on
 * the spot, in the run that is relying on the restore — rather than at the end of an investigation
 * into why a rewound run behaved oddly.
 *
 * <p><b>Identity canonicalisation.</b> Goal ids are minted UUIDs. Comparing them would make every
 * replay diverge for a reason that is not a defect, so before digesting, each goal id is replaced
 * by its position in a canonical ordering of the tree (parent chain, then description). Two states
 * that differ only in which UUIDs happened to be minted therefore compare equal, while two that
 * differ in what the tree SAYS do not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StateFingerprintService {

    private final FileService fileService;
    private final ObjectMapper objectMapper;
    private final GoalPlannerService goalPlannerService;
    private final StateManifest stateManifest;

    /** The current state of the loop, digested per component. */
    public StateFingerprint current() {
        return of(stateManifest.capture());
    }

    /**
     * The fingerprint of a capture the caller has already taken. The checkpoint uses this so the
     * fingerprint describes THE state that was written rather than a second capture taken a moment
     * later — two captures of a live loop are not guaranteed to be the same capture, and a
     * fingerprint of the wrong one would report a divergence that never happened.
     */
    public StateFingerprint of(StateManifest.Capture capture) {
        Map<String, String> canonicalIds = canonicalGoalIds();
        return new StateFingerprint(treeDigest("output"), goalsDigest(canonicalIds),
                holderDigests(capture, canonicalIds));
    }

    /** The digest of an arbitrary tree — used to compare a snapshot against what was restored. */
    public String treeDigest(String relativeDir) {
        try {
            if (!fileService.exists(relativeDir)) return sha256("<absent>");
            Path root = fileService.resolveSecurePath(relativeDir);
            List<String> lines = new ArrayList<>();
            try (var walk = fileService.walkResolved(root)) {
                for (Path p : walk.filter(java.nio.file.Files::isRegularFile).toList()) {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    // The snapshot's own state stamp describes the snapshot, not the project.
                    if (rel.equals(com.walpro.autobuilder.core.persistence.MutationSnapshotService.SNAPSHOT_STATE_FILE)) {
                        continue;
                    }
                    lines.add(rel + ":" + sha256(fileService.readStringResolved(p)));
                }
            }
            lines.sort(Comparator.naturalOrder());
            return sha256(String.join("\n", lines));
        } catch (IOException | RuntimeException e) {
            log.debug("T4-DQ: tree digest for {} failed: {}", relativeDir, e.getMessage());
            return sha256("<unreadable>");
        }
    }

    /**
     * The goal tree as it reads, not as it is keyed: description, type, status and parent position
     * per goal, ordered canonically.
     */
    private String goalsDigest(Map<String, String> canonicalIds) {
        List<String> lines = new ArrayList<>();
        for (GoalEntity g : goalPlannerService.getAllGoals()) {
            lines.add(canonicalIds.getOrDefault(g.getId(), g.getId())
                    + "|" + g.getType()
                    + "|" + g.getStatus()
                    + "|" + (g.getParentId() == null ? "-" : canonicalIds.getOrDefault(g.getParentId(), g.getParentId()))
                    + "|" + normalise(g.getDescription()));
        }
        lines.sort(Comparator.naturalOrder());
        String pointer = canonicalIds.getOrDefault(goalPlannerService.getCurrentSubGoalId(), "-");
        return sha256(String.join("\n", lines) + "\ncurrent=" + pointer);
    }

    /** One digest per registered holder, with goal ids canonicalised inside the captured state. */
    private Map<String, String> holderDigests(StateManifest.Capture capture, Map<String, String> canonicalIds) {
        Map<String, String> digests = new TreeMap<>();
        capture.state().forEach((key, value) -> {
            try {
                digests.put(key, sha256(canonicaliseIds(objectMapper.writeValueAsString(value), canonicalIds)));
            } catch (Exception e) {
                digests.put(key, sha256("<uncapturable>"));
            }
        });
        for (String failed : capture.failures()) {
            String key = failed.contains(":") ? failed.substring(0, failed.indexOf(':')) : failed;
            digests.put(key, sha256("<capture-failed>"));
        }
        return digests;
    }

    /**
     * Goal id to canonical position. The ordering is by parent chain then description, so it is a
     * property of what the tree says rather than of when the ids were minted.
     */
    private Map<String, String> canonicalGoalIds() {
        List<GoalEntity> goals = new ArrayList<>(goalPlannerService.getAllGoals());
        Map<String, GoalEntity> byId = new HashMap<>();
        goals.forEach(g -> byId.put(g.getId(), g));
        goals.sort(Comparator
                .comparing((GoalEntity g) -> depth(g, byId))
                .thenComparing(g -> normalise(g.getDescription()))
                .thenComparing(g -> String.valueOf(g.getType())));
        Map<String, String> canonical = new HashMap<>();
        for (int i = 0; i < goals.size(); i++) {
            canonical.put(goals.get(i).getId(), "goal#" + i);
        }
        return canonical;
    }

    private static int depth(GoalEntity g, Map<String, GoalEntity> byId) {
        int depth = 0;
        GoalEntity cursor = g;
        while (cursor != null && cursor.getParentId() != null && depth < 64) {
            cursor = byId.get(cursor.getParentId());
            depth++;
        }
        return depth;
    }

    /** Replace every known goal id inside a serialised holder state with its canonical position. */
    private static String canonicaliseIds(String json, Map<String, String> canonicalIds) {
        String out = json;
        for (Map.Entry<String, String> e : canonicalIds.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
