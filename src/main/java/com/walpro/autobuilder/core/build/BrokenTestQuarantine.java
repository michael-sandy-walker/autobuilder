package com.walpro.autobuilder.core.build;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-BN-3 (deterministic slice 2): actuator for the completion edge's standing resolution that,
 * on a goal whose specification never asked for automated tests, self-authored test sources that
 * cannot be made to compile may be REMOVED rather than repaired.
 *
 * <p>That resolution has existed since 2026-07-03 only as prose in the injected work item — and
 * in every field occurrence of the trap (runs 2, 16 and 17 of the July batch) the model neither
 * repaired the tests nor removed them: the closure gates kept refusing on test-source compile
 * errors, the planner re-decomposed the same broken file, and the run ended in a give-up on an
 * artifact whose MAIN sources were complete (run 17: spec-correct at runtime, 16/16 on the
 * behaviour smoke). A diagnosis without an actuator ([[project-completion-edge-gates]]).
 *
 * <p>Discipline: the first blocked closure visit only records itself — the injected work item
 * gets one full repair round (repair stays preferred; a fixed test is worth more than a removed
 * one). From the second consecutive test-confined blocked visit on, the files named in the
 * failure output are MOVED — never deleted — to {@code <base-path>/quarantine/<cycleId>/},
 * preserving their source-tree relative path for inspection. The caller re-verifies the gate
 * afterwards; each visit quarantines the files that visit's failure names, so a tail-capped
 * error report converges over successive visits (the set of test files is finite and shrinking).
 *
 * <p>Hard scope limits: only files under {@code src/test/} inside the build working dir are ever
 * moved (path-resolved, not string-trusted), and the caller must gate on the spec not requiring
 * tests ({@code TestRunGate.specRequiresTests}) — on a test-requiring goal absence is a blocker,
 * so removal is never offered there. Test sources that compile but FAIL are out of scope by
 * construction (this reads compile-error paths): a running-but-red test is an execution observation
 * (the only gate that saw the 2026-07-03 run-6 unbootable artifact); a non-compiling one is
 * an observation of nothing.
 */
@Slf4j
@Component
public class BrokenTestQuarantine {

    /** A source path under src/test as it appears in build diagnostics (absolute or relative). */
    private static final Pattern TEST_SOURCE_PATH = Pattern.compile("(\\S*?src[/\\\\]test[/\\\\]\\S+?\\.java)");

    private final boolean enabled;
    private final BuildExecutor buildExecutor;

    @Value("${file.base-path}")
    @Setter
    private String fileBasePath;

    /** Consecutive closure visits blocked by test-confined compile errors, per root goal. */
    private final Map<String, Integer> blockedVisitsByRoot = new ConcurrentHashMap<>();

    @Autowired
    public BrokenTestQuarantine(
            @Value("${autobuilder.build.quarantine-broken-tests:false}") boolean enabled,
            BuildExecutor buildExecutor) {
        this.enabled = enabled;
        this.buildExecutor = buildExecutor;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Records a closure visit blocked by test-confined compile errors and, from the second
     * consecutive visit on, quarantines the test sources named in {@code failureText}.
     * Returns the working-dir-relative paths of the files actually moved (empty on the
     * repair-round visit, when disabled, or when nothing named in the failure still exists).
     */
    public List<String> recordBlockedVisit(String rootId, String failureText, String cycleId) {
        if (!enabled || rootId == null) {
            return List.of();
        }
        int visits = blockedVisitsByRoot.merge(rootId, 1, Integer::sum);
        if (visits < 2) {
            log.info("T4-BN-3: closure blocked by test-source compile errors (visit {}) — "
                    + "leaving the injected work item one repair round before quarantining", visits);
            return List.of();
        }
        Path workingDir = Path.of(buildExecutor.resolveWorkingDir(fileBasePath)).toAbsolutePath().normalize();
        List<String> moved = new ArrayList<>();
        for (String candidate : testSourcePathsIn(failureText)) {
            Path source = resolveInsideWorkingDir(workingDir, candidate);
            if (source == null || !Files.isRegularFile(source)) {
                continue;
            }
            String relative = workingDir.relativize(source).toString();
            Path target = Path.of(fileBasePath, "quarantine", cycleId == null ? "closure" : cycleId)
                    .resolve(relative);
            try {
                Files.createDirectories(target.getParent());
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                moved.add(relative);
            } catch (IOException e) {
                log.warn("T4-BN-3: could not quarantine {} ({})", relative, e.getMessage());
            }
        }
        if (!moved.isEmpty()) {
            log.warn("T4-BN-3: quarantined {} non-compiling test source(s) the goal specification "
                    + "never asked for (visit {}): {} — preserved under quarantine/, not deleted",
                    moved.size(), visits, moved);
        }
        return moved;
    }

    /** Clears the visit count — call when a closure visit passes the gate. */
    public void reset(String rootId) {
        if (rootId != null) {
            blockedVisitsByRoot.remove(rootId);
        }
    }

    /** Distinct src/test source paths named in the failure output, in order of appearance. */
    static Set<String> testSourcePathsIn(String failureText) {
        Set<String> paths = new LinkedHashSet<>();
        if (failureText == null || failureText.isBlank()) {
            return paths;
        }
        Matcher m = TEST_SOURCE_PATH.matcher(failureText);
        while (m.find()) {
            paths.add(m.group(1));
        }
        return paths;
    }

    /**
     * Resolves a diagnostic path (absolute or working-dir-relative) and admits it only when it
     * is a real path inside the working dir AND under a {@code src/test} tree — build output is
     * data, so nothing it names is trusted until re-anchored to the tree we own.
     */
    private static Path resolveInsideWorkingDir(Path workingDir, String candidate) {
        Path p = Path.of(candidate);
        Path resolved = (p.isAbsolute() ? p : workingDir.resolve(p)).toAbsolutePath().normalize();
        if (!resolved.startsWith(workingDir)) {
            return null;
        }
        String normalized = resolved.toString().replace('\\', '/');
        return normalized.contains("/src/test/") ? resolved : null;
    }
}
