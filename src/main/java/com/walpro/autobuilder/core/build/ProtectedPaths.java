package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * T4-CQ: paths inside the target the loop may not write, as the operator declared them.
 *
 * <p>Found on the first adopted project, whose specification says "do not modify anything under
 * src/main/java". That sentence is guidance to the model and nothing enforced it: the loop's own
 * compile-fix injector created an empty class under {@code src/main/java} to satisfy a test's wrong
 * reference. A constraint on what may be touched is a fact about the target, so it lives beside the
 * target's specification as DATA -- one glob per line in {@code bootstrap/protected-paths.txt},
 * relative to {@code output/} -- and every writer asks here before it writes.
 *
 * <p>Absent file, no protection: every existing target behaves exactly as before.
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(
        value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "a cache of bootstrap/protected-paths.txt, re-read whenever its text changes")
public class ProtectedPaths {

    public static final String PATH = "bootstrap/protected-paths.txt";

    private final FileService fileService;

    private String cachedText;
    private List<PathMatcher> cachedMatchers = List.of();

    public ProtectedPaths(FileService fileService) {
        this.fileService = fileService;
    }

    /** True when {@code pathUnderOutput} (relative to {@code output/}) matches a protected glob. */
    public boolean isProtected(String pathUnderOutput) {
        if (pathUnderOutput == null || pathUnderOutput.isBlank()) return false;
        List<PathMatcher> matchers = matchers();
        if (matchers.isEmpty()) return false;
        String rel = pathUnderOutput.replace('\\', '/');
        if (rel.startsWith("output/")) rel = rel.substring("output/".length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        Path p = Path.of(rel);
        for (PathMatcher m : matchers) {
            if (m.matches(p)) return true;
        }
        return false;
    }

    /** Refused writes not yet told to the model, oldest first; drained by {@link #promptBlock()}. */
    private final java.util.Set<String> unreportedRefusals = new java.util.LinkedHashSet<>();

    /** Logs the refusal in one wording, so a run can be searched for it, and keeps it for the model. */
    public boolean refuse(String writer, String pathUnderOutput) {
        if (!isProtected(pathUnderOutput)) return false;
        log.warn("T4-CQ: {} did not write {} — it is protected by {}", writer, pathUnderOutput, PATH);
        if (grindDetector != null) grindDetector.observe(null, "write to " + normalise(pathUnderOutput) + " refused: protected");
        synchronized (unreportedRefusals) {
            unreportedRefusals.add(normalise(pathUnderOutput));
        }
        return true;
    }

    /** T4-CQ: optional, so narrow tests need not wire it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GrindDetector grindDetector;

    /**
     * The block the evolution prompt carries while any path is protected: which paths, and which of
     * the model's writes were refused since the last prompt. Null when nothing is protected.
     *
     * <p>Found on the second adopted project: nineteen refused rewrites of a protected record, none
     * of which the model was ever told about, so it made the same move every cycle.
     */
    public String promptBlock() {
        List<String> globs = globs();
        if (globs.isEmpty()) return null;
        List<String> refused;
        synchronized (unreportedRefusals) {
            refused = List.copyOf(unreportedRefusals);
            unreportedRefusals.clear();
        }
        StringBuilder sb = new StringBuilder("--- PROTECTED PATHS (declared by the operator) ---\n");
        sb.append("These paths are not yours to change; every write to them is refused:\n");
        for (String g : globs) sb.append("  - ").append(g).append("\n");
        if (!refused.isEmpty()) {
            sb.append("Your previous changes to these files were NOT applied, because they are protected:\n");
            for (String r : refused) sb.append("  - ").append(r).append("\n");
            sb.append("Work with them as they are: change the files that use them instead.\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String normalise(String path) {
        String rel = path.replace('\\', '/');
        if (rel.startsWith("output/")) rel = rel.substring("output/".length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        return rel;
    }

    private synchronized List<String> globs() {
        matchers();
        List<String> out = new ArrayList<>();
        if (cachedText == null) return out;
        for (String line : cachedText.split("\\R")) {
            String g = line.strip();
            if (!g.isEmpty() && !g.startsWith("#")) out.add(g);
        }
        return out;
    }

    private synchronized List<PathMatcher> matchers() {
        String text;
        try {
            text = fileService.exists(PATH) ? fileService.readString(PATH) : "";
        } catch (Exception e) {
            text = "";
        }
        if (text.equals(cachedText)) return cachedMatchers;
        List<PathMatcher> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String g = line.strip();
            if (g.isEmpty() || g.startsWith("#")) continue;
            out.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        }
        cachedText = text;
        cachedMatchers = List.copyOf(out);
        return cachedMatchers;
    }
}
