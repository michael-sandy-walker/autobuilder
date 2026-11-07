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

    /** T4-EC: under {@code meta/inputs/<id>}, what a project brought in protects while it is pending: its own lines and its tests. */
    public static final String INPUT_FILE = "/protected.txt";

    static final String MEMBERS_MARKER = "members:";

    /** T4-EC: the line protecting a file's declarations. */
    public static final String MEMBERS_LINE = MEMBERS_MARKER + " ";

    private final FileService fileService;

    private String cachedText;
    private List<PathMatcher> cachedMatchers = List.of();
    private List<PathMatcher> cachedMemberMatchers = List.of();
    private List<String> cachedMemberGlobs = List.of();
    /** Per member glob (same order as {@link #cachedMemberMatchers}): the members its {@code except} list opens. */
    private List<java.util.Set<String>> cachedMemberExceptions = List.of();

    public ProtectedPaths(FileService fileService) {
        this.fileService = fileService;
    }

    /** True when {@code pathUnderOutput} (relative to {@code output/}) matches a protected glob. */
    public boolean isProtected(String pathUnderOutput) {
        if (pathUnderOutput == null || pathUnderOutput.isBlank()) return false;
        if (isDerivedTest(pathUnderOutput)) return true;
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

    /** True only when the policy explicitly protects the entire Java test tree in every Maven module. */
    public boolean coversMavenTestTrees(Path output) {
        Path root = output.normalize();
        if (!java.nio.file.Files.isRegularFile(root.resolve("pom.xml"))) return false;
        List<String> protectedGlobs = globs();
        return ProjectLayout.moduleRoots(root).stream().allMatch(module -> {
            if (!module.startsWith(root)) return false;
            String prefix = root.equals(module) ? "" : root.relativize(module).toString().replace('\\', '/') + "/";
            return protectedGlobs.contains(prefix + "src/test/**")
                    || protectedGlobs.contains(prefix + "src/test/java/**");
        });
    }

    /** Refused writes not yet told to the model, oldest first; drained by {@link #promptBlock()}. */
    private final java.util.Set<String> unreportedRefusals = new java.util.LinkedHashSet<>();

    /**
     * T6-U: the operator's declared boundary, for a no-change claim ({@link UnchangedClaims.Boundary}): something is
     * declared under {@code scope} when a protecting glob names a path at or below it, or covers it from above.
     */
    public UnchangedClaims.Boundary boundary() {
        return new UnchangedClaims.Boundary() {
            @Override
            public boolean declaresUnder(String scope) {
                String s = normalise(scope).replaceAll("/\\*+$", "").replaceAll("/$", "");
                for (String g : globs()) {
                    String literal = g.replaceAll("[*?\\[{].*$", "").replaceAll("/$", "");
                    if (literal.isEmpty()) return true;
                    if (literal.equals(s) || literal.startsWith(s + "/") || s.startsWith(literal + "/")) return true;
                }
                return false;
            }

            @Override
            public boolean protects(String path) {
                return isProtected(path);
            }
        };
    }

    /**
     * T6-U: true when {@code pathUnderOutput} is a test written from the requirements' wording and accepted
     * ({@link DerivedTestsFile}). Frozen like an operator's test, but the framework's to write: a writer may
     * only propose a new text for it, through {@link #refuse(String, String, String)}.
     */
    public boolean isDerivedTest(String pathUnderOutput) {
        if (pathUnderOutput == null || pathUnderOutput.isBlank()) return false;
        return derivedPaths().contains(normalise(pathUnderOutput));
    }

    /** The accepted derived tests' paths, re-read whenever the file's text changes. */
    private synchronized java.util.Set<String> derivedPaths() {
        String text = DerivedTestsFile.readText(fileService);
        if (text.equals(cachedDerivedText)) return cachedDerivedPaths;
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (DerivedTestsFile.Entry e : DerivedTestsFile.parse(text)) {
            if (e.accepted() && e.path() != null) out.add(normalise(e.path()));
        }
        cachedDerivedText = text;
        cachedDerivedPaths = java.util.Set.copyOf(out);
        return cachedDerivedPaths;
    }

    private String cachedDerivedText;
    private java.util.Set<String> cachedDerivedPaths = java.util.Set.of();

    /** T6-U: the party that judges a proposed text for a derived test; optional, so narrow tests need not wire it. */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DerivedTestAuthority derivedTestAuthority;

    void setDerivedTestAuthority(DerivedTestAuthority authority) {
        this.derivedTestAuthority = authority;
    }

    /**
     * As {@link #refuse(String, String)}, for a writer that has the text it would write. A derived test is the
     * one protected path such a writer may change: its text is put to the {@link DerivedTestAuthority}, judged
     * against the requirement's wording, and the write goes ahead when that text is the accepted one.
     */
    public boolean refuse(String writer, String pathUnderOutput, String content) {
        if (isDerivedTest(pathUnderOutput) && derivedTestAuthority != null && content != null
                && derivedTestAuthority.contest(writer, normalise(pathUnderOutput), content)) {
            return false;
        }
        return refuse(writer, pathUnderOutput);
    }

    /** Logs the refusal in one wording, so a run can be searched for it, and keeps it for the model. */
    public boolean refuse(String writer, String pathUnderOutput) {
        if (isDerivedTest(pathUnderOutput)) {
            // The framework writes these from their accepted text before every test run, so a writer's copy
            // is never needed; what the model is told of its proposals is the authority's to say.
            log.info("T6-U: {} did not write {} -- a test written from the requirements, the framework's to write",
                    writer, pathUnderOutput);
            return true;
        }
        if (!isProtected(pathUnderOutput)) return false;
        log.warn("T4-CQ: {} did not write {} — it is protected by {}", writer, pathUnderOutput, PATH);
        if (grindDetector != null) grindDetector.observe(null, com.walpro.autobuilder.core.stuck.StuckObservation.Source.PROTECTED_WRITE, "write to " + normalise(pathUnderOutput) + " refused: protected");
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
            PreservedMembers.Result r = PreservedMembers.restore(fileService.readString(BASELINE_DIR + "/" + rel), content,
                    openMembers(rel));
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

    /** The members the {@code except} lists of the globs matching {@code rel} leave open to writers. */
    synchronized java.util.Set<String> openMembers(String rel) {
        matchers();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        Path p = Path.of(normalise(rel));
        for (int i = 0; i < cachedMemberMatchers.size(); i++) {
            if (cachedMemberMatchers.get(i).matches(p)) out.addAll(cachedMemberExceptions.get(i));
        }
        return out;
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
        java.util.Set<String> derived = derivedPaths();
        if (globs.isEmpty() && memberGlobs.isEmpty() && derived.isEmpty()) return null;
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
        if (!derived.isEmpty()) {
            sb.append("These tests were written from the requirements' own wording before the code changed, and they"
                    + " are the requirements: make the code pass them. If one checks something its requirement does"
                    + " not say, you may write a corrected version of it; the correction is judged against the"
                    + " requirement's wording only, never against your code:\n");
            for (String d : derived) sb.append("  - ").append(d).append("\n");
        }
        String contests = derivedTestAuthority == null ? null : derivedTestAuthority.drainNotes();
        if (contests != null && !contests.isBlank()) sb.append(contests.strip()).append("\n");
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

    /**
     * Every PENDING import's protected lines; none where no project was brought in. T4-EG: a kept import is the
     * project, and its code is as much the loop's to change as any other -- protecting it went on steering the loop
     * after the keep (field, 2026-11-05: an imported controller's message could not change to meet a test, and the run
     * spent its last half hour on one letter). Read from the ledger, so a rewind to before a keep protects it again.
     */
    private String inputLines() {
        StringBuilder out = new StringBuilder();
        try {
            if (!Boolean.TRUE.equals(fileService.exists("meta/inputs"))) return "";
            com.walpro.autobuilder.goal.purpose.InputLedger ledger =
                    com.walpro.autobuilder.goal.purpose.PurposeService.fileOnly(fileService).ledger();
            for (java.nio.file.Path dir : fileService.listSubfolders("meta/inputs")) {
                if (!ledger.pending(dir.getFileName().toString())) continue;
                String file = "meta/inputs/" + dir.getFileName() + INPUT_FILE;
                if (fileService.exists(file)) out.append('\n').append(fileService.readString(file));
            }
        } catch (Exception e) {
            log.debug("T4-EC: inputs' protected paths unreadable: {}", e.getMessage());
        }
        return out.toString();
    }

    private synchronized List<PathMatcher> matchers() {
        String text;
        try {
            text = fileService.exists(PATH) ? fileService.readString(PATH) : "";
        } catch (Exception e) {
            text = "";
        }
        text += inputLines();
        if (text.equals(cachedText)) return cachedMatchers;
        List<PathMatcher> out = new ArrayList<>();
        List<PathMatcher> members = new ArrayList<>();
        List<String> memberGlobs = new ArrayList<>();
        List<java.util.Set<String>> exceptions = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String g = line.strip();
            if (g.isEmpty() || g.startsWith("#")) continue;
            if (g.startsWith(MEMBERS_MARKER)) {
                String rule = g.substring(MEMBERS_MARKER.length()).strip();
                // "members: <glob> except a, b": a and b may be changed by a writer (an entry point the ticket extends)
                String[] parts = rule.split("\\s+except\\s+", 2);
                String glob = parts[0].strip();
                if (glob.isEmpty()) continue;
                members.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
                memberGlobs.add(rule);
                java.util.Set<String> open = new java.util.LinkedHashSet<>();
                if (parts.length > 1) {
                    for (String n : parts[1].split("[,\\s]+")) if (!n.isBlank()) open.add(n.strip());
                }
                exceptions.add(java.util.Set.copyOf(open));
                continue;
            }
            out.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        }
        cachedText = text;
        cachedMatchers = List.copyOf(out);
        cachedMemberMatchers = List.copyOf(members);
        cachedMemberGlobs = List.copyOf(memberGlobs);
        cachedMemberExceptions = List.copyOf(exceptions);
        return cachedMatchers;
    }
}
