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
        return new StateFingerprint(treeDigest("output"), pointerKey(), goalDigests(),
                holderDigests(capture, canonicalIds));
    }

    /**
     * The current subgoal, named the way the goal digests are — by position and description rather
     * than by its UUID, so it survives a replay that re-creates the same work under a fresh id.
     *
     * <p>Its own component because the first field divergence was a pointer mismatch hiding inside
     * the coarse goals digest, and because it had TWO sources: the checkpoint recorded the cycle
     * context's pointer while the fingerprint digested the planner's live one. One fact, one
     * source — the planner's, which is the authoritative goal state at checkpoint time; the
     * context's is a copy taken earlier in the cycle.
     */
    private String pointerKey() {
        String current = goalPlannerService.getCurrentSubGoalId();
        if (current == null) return "-";
        GoalEntity g = goalPlannerService.getGoalById(current);
        return g == null ? "-" : goalKey(g, index());
    }

    private Map<String, GoalEntity> index() {
        Map<String, GoalEntity> byId = new HashMap<>();
        goalPlannerService.getAllGoals().forEach(g -> byId.put(g.getId(), g));
        return byId;
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
     * One digest per goal — status, type and parent — keyed by what the goal SAYS rather than by
     * its UUID, so a divergence names the goal a reader can find rather than reporting that
     * something, somewhere in the tree, moved.
     *
     * <p>The key deliberately excludes the description while the VALUE includes it: a goal whose
     * text was re-grounded mid-run is then reported as that goal changing, not as one goal
     * vanishing and another appearing.
     */
    private Map<String, String> goalDigests() {
        Map<String, GoalEntity> byId = index();
        Map<String, String> digests = new TreeMap<>();
        for (GoalEntity g : goalPlannerService.getAllGoals()) {
            GoalEntity parent = g.getParentId() == null ? null : byId.get(g.getParentId());
            digests.put(goalKey(g, byId), sha256(g.getType()
                    + "|" + g.getStatus()
                    + "|" + (parent == null ? "-" : goalKey(parent, byId))
                    + "|" + normalise(g.getDescription())));
        }
        return digests;
    }

    /** A goal's identity for comparison: its depth in the tree plus a short form of its text. */
    private static String goalKey(GoalEntity g, Map<String, GoalEntity> byId) {
        String text = normalise(g.getDescription());
        return depth(g, byId) + "|" + text.substring(0, Math.min(48, text.length()));
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
