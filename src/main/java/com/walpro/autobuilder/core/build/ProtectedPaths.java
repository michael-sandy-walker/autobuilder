package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *
 * <p><b>{@code members: <glob>}</b> protects less and more at once: the file may be written, but the
 * declarations it had when the project was ADOPTED are kept exactly as they were, whatever a writer
 * puts there ({@link PreservedMembers}); new declarations beside them are the writer's to add. The
 * adopted text is recorded once, at adoption, under {@value #BASELINE_DIR}.
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(
        value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "a cache of bootstrap/protected-paths.txt, re-read whenever its text changes")
public class ProtectedPaths {

    public static final String PATH = "bootstrap/protected-paths.txt";

    /** Where the adopted text of member-protected files is kept, relative to the target. */
    public static final String BASELINE_DIR = "meta/adoption-baseline";

    static final String MEMBERS_MARKER = "members:";

    private final FileService fileService;

    private String cachedText;
    private List<PathMatcher> cachedMatchers = List.of();
    private List<PathMatcher> cachedMemberMatchers = List.of();
    private List<String> cachedMemberGlobs = List.of();

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

    /** True when {@code pathUnderOutput} is declared with {@code members:}. */
    public boolean preservesMembers(String pathUnderOutput) {
        if (pathUnderOutput == null || pathUnderOutput.isBlank()) return false;
        matchers();
        Path p = Path.of(normalise(pathUnderOutput));
        for (PathMatcher m : cachedMemberMatchers) {
            if (m.matches(p)) return true;
        }
        return false;
    }

    /**
     * Record the adopted text of every member-protected file under {@link #BASELINE_DIR}, once: a
     * baseline already there is the adoption's and is never replaced. Called when a run ADOPTS a tree.
     */
    public void recordAdoptionBaseline() {
        matchers();
        if (cachedMemberMatchers.isEmpty()) return;
        try {
            if (fileService.exists(BASELINE_DIR)) return;
            Path out = fileService.resolveSecurePath("output");
            if (!java.nio.file.Files.isDirectory(out)) return;
            int n = 0;
            try (var walk = java.nio.file.Files.walk(out)) {
                for (Path f : (Iterable<Path>) walk.filter(java.nio.file.Files::isRegularFile)::iterator) {
                    String rel = out.relativize(f).toString().replace('\\', '/');
                    if (!preservesMembers(rel)) continue;
                    fileService.writeFile(BASELINE_DIR + "/" + rel, java.nio.file.Files.readString(f));
                    n++;
                }
            }
            log.warn("T4-CQ: recorded the adopted text of {} member-protected file(s) under {}", n, BASELINE_DIR);
        } catch (Exception e) {
            log.warn("T4-CQ: could not record the adoption baseline: {}", e.getMessage());
        }
    }

    /**
     * {@code content} as it may be written to {@code pathUnderOutput}: for a member-protected file with
     * an adopted text, every declaration it had at adoption restored as it was, new ones kept. Any other
     * path, or a file new since adoption, is returned unchanged.
     */
    public String preserve(String writer, String pathUnderOutput, String content) {
        if (content == null || !preservesMembers(pathUnderOutput)) return content;
        String rel = normalise(pathUnderOutput);
        try {
            if (!fileService.exists(BASELINE_DIR + "/" + rel)) return content;
            PreservedMembers.Result r = PreservedMembers.restore(fileService.readString(BASELINE_DIR + "/" + rel), content);
            if (r.restored().isEmpty()) return content;
            log.warn("T4-CQ: {} rewrote {} existing declaration(s) of {} -- restored as adopted: {}",
                    writer, r.restored().size(), rel, String.join(", ", r.restored()));
            synchronized (unreportedRestorations) {
                unreportedRestorations.computeIfAbsent(rel, k -> new java.util.LinkedHashSet<>()).addAll(r.restored());
            }
            return r.content();
        } catch (Exception e) {
            log.warn("T4-CQ: could not check {} against its adopted text: {}", rel, e.getMessage());
            return content;
        }
    }

    /** True for a member-protected file that existed at adoption: deleting it would remove every declaration. */
    public boolean refuseDelete(String writer, String pathUnderOutput) {
        if (!preservesMembers(pathUnderOutput)) return false;
        try {
            if (!fileService.exists(BASELINE_DIR + "/" + normalise(pathUnderOutput))) return false;
        } catch (Exception e) {
            return false;
        }
        log.warn("T4-CQ: {} did not delete {} -- its declarations are protected by {}", writer, pathUnderOutput, PATH);
        synchronized (unreportedRefusals) {
            unreportedRefusals.add(normalise(pathUnderOutput));
        }
        return true;
    }

    /** Restorations not yet told to the model, per file; drained by {@link #promptBlock()}. */
    private final Map<String, java.util.Set<String>> unreportedRestorations = new java.util.LinkedHashMap<>();

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
        List<String> memberGlobs;
        synchronized (this) {
            memberGlobs = cachedMemberGlobs;
        }
        if (globs.isEmpty() && memberGlobs.isEmpty()) return null;
        List<String> refused;
        synchronized (unreportedRefusals) {
            refused = List.copyOf(unreportedRefusals);
            unreportedRefusals.clear();
        }
        Map<String, java.util.Set<String>> restored;
        synchronized (unreportedRestorations) {
            restored = new java.util.LinkedHashMap<>(unreportedRestorations);
            unreportedRestorations.clear();
        }
        StringBuilder sb = new StringBuilder("--- PROTECTED PATHS (declared by the operator) ---\n");
        if (!globs.isEmpty()) {
            sb.append("These paths are not yours to change; every write to them is refused:\n");
            for (String g : globs) sb.append("  - ").append(g).append("\n");
        }
        if (!memberGlobs.isEmpty()) {
            sb.append("In these files, every declaration that already exists stays exactly as it is; add new ones"
                    + " beside them. A change to an existing declaration is undone:\n");
            for (String g : memberGlobs) sb.append("  - ").append(g).append("\n");
        }
        if (!refused.isEmpty()) {
            sb.append("Your previous changes to these files were NOT applied, because they are protected:\n");
            for (String r : refused) sb.append("  - ").append(r).append("\n");
            sb.append("Work with them as they are: change the files that use them instead.\n");
        }
        if (!restored.isEmpty()) {
            sb.append("Your previous change rewrote existing declarations; they were put back as they were:\n");
            restored.forEach((file, members) -> sb.append("  - ").append(file).append(": ")
                    .append(String.join(", ", members)).append("\n"));
            sb.append("Write only the new declarations, and leave every existing one exactly as it is.\n");
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
            if (!g.isEmpty() && !g.startsWith("#") && !g.startsWith(MEMBERS_MARKER)) out.add(g);
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
        List<PathMatcher> members = new ArrayList<>();
        List<String> memberGlobs = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String g = line.strip();
            if (g.isEmpty() || g.startsWith("#")) continue;
            if (g.startsWith(MEMBERS_MARKER)) {
                String glob = g.substring(MEMBERS_MARKER.length()).strip();
                if (glob.isEmpty()) continue;
                members.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
                memberGlobs.add(glob);
                continue;
            }
            out.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        }
        cachedText = text;
        cachedMatchers = List.copyOf(out);
        cachedMemberMatchers = List.copyOf(members);
        cachedMemberGlobs = List.copyOf(memberGlobs);
        return cachedMatchers;
    }
}
