package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.core.build.ProtectedPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * T4-EC slice 4: what one project brings to the project receiving it, worked out without touching either. Pure: the
 * receiving tree and what was adopted come in, what must change comes out; {@link ProjectInputService} applies it.
 *
 * <p>With a common base (both adopted the same text) the code merges three-way, declaration by declaration. Without
 * one it is placed beside the receiving project's, which stays where it is: moving a project into a module would pull
 * every path out from under its goals and criteria, and under a running loop. A file both have is a conflict, the
 * receiving one's kept; the build gains the input's dependencies.
 *
 * <p>The input is a project directory ({@code output/}, {@code meta/GoalStack.json}, {@code bootstrap/}) or a bare tree
 * with no such record, as a third party's is: then only its code and its tests come.
 */
public final class ProjectInput {

    /**
     * @param files         every file to write under {@code output/}, new or changed
     * @param placed        the files the input brought, for its manifest entry and its undo
     * @param conflicts     what could not be joined, in words
     * @param conflictedFiles the files both projects have with different text, the receiving one kept
     * @param notes         what else was done
     * @param tree          the input's goal tree restamped for the receiving project, or null with none
     * @param criteria      its criteria and obligations with their origins
     * @param obligations   what it verified, as obligation lines (a line its test gate answers keeps its marker)
     * @param protectedLines its protected paths and its own test files
     * @param sourceLineage its lineage, or null
     * @param treeDigest    a digest of the tree it arrived with
     */
    public record Landing(Map<String, String> files, List<String> placed, List<String> conflicts,
                          List<String> conflictedFiles, List<String> notes,
                          ObjectNode tree, List<ProjectMerge.Criterion> criteria, List<String> obligations,
                          List<String> protectedLines, String sourceLineage, String treeDigest) {
    }

    private ProjectInput() {
    }

    /** {@code input}, landing on the receiving {@code tree} adopted as {@code adopted}. */
    public static Landing land(Map<String, String> tree, Map<String, String> adopted, ProjectMerge.Parent input)
            throws IOException {
        Path dir = input.dir();
        boolean project = Files.isDirectory(dir.resolve("output"));
        Map<String, String> theirs = ProjectMerge.files(project ? dir.resolve("output") : dir);
        Map<String, String> theirAdopted = ProjectMerge.files(dir.resolve(ProtectedPaths.BASELINE_DIR));
        List<String> conflicts = new ArrayList<>(), notes = new ArrayList<>(), provenance = new ArrayList<>();
        Map<String, String> merged = sharesBase(adopted, theirAdopted)
                ? threeWay(tree, adopted, theirAdopted, theirs, input.label(), conflicts, provenance)
                : placed(tree, theirs, input.label(), conflicts, provenance, notes);

        Map<String, String> changed = new LinkedHashMap<>();
        merged.forEach((p, t) -> {
            if (!t.equals(tree.get(p))) changed.put(p, t);
        });
        List<String> brought = new ArrayList<>();
        for (String row : provenance) brought.add(row.substring(0, row.indexOf('\t')));

        Map<String, ProjectMerge.Criterion> criteria = new LinkedHashMap<>();
        ObjectNode goals = null;
        List<String> obligations = new ArrayList<>();
        Set<String> protect = new LinkedHashSet<>();
        if (Files.isRegularFile(dir.resolve("meta/GoalStack.json"))) goals = ProjectMerge.parentTree(input, criteria);
        String seed = ProjectMerge.read(dir.resolve("bootstrap/seed.txt"));
        if (seed != null) {
            obligations.addAll(ProjectMerge.parentObligations(seed, ProjectMerge.read(dir.resolve("bootstrap/seed.obligations.txt"))));
        }
        String theirProtected = ProjectMerge.read(dir.resolve("bootstrap/protected-paths.txt"));
        if (theirProtected != null) {
            theirProtected.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(protect::add);
        }
        for (String p : brought) {
            if (p.startsWith("src/test/")) protect.add(p);
        }
        List<String> conflictedFiles = conflicts.stream().filter(c -> c.contains(": both projects have it"))
                .map(c -> c.substring(0, c.indexOf(": both projects have it"))).toList();
        return new Landing(changed, brought, conflicts, conflictedFiles, notes, goals, List.copyOf(criteria.values()), obligations,
                List.copyOf(protect), ProjectMerge.lineageOf(input), digest(theirs));
    }

    /** Both record what they adopted, and every file both recorded is the same text. */
    private static boolean sharesBase(Map<String, String> adopted, Map<String, String> theirAdopted) {
        if (adopted.isEmpty() || theirAdopted.isEmpty()) return false;
        boolean shared = false;
        for (Map.Entry<String, String> e : theirAdopted.entrySet()) {
            String ours = adopted.get(e.getKey());
            if (ours == null) continue;
            if (!ours.equals(e.getValue())) return false;
            shared = true;
        }
        return shared;
    }

    private static Map<String, String> threeWay(Map<String, String> tree, Map<String, String> adopted,
                                                Map<String, String> theirAdopted, Map<String, String> theirs, String label,
                                                List<String> conflicts, List<String> provenance) {
        Map<String, String> base = new TreeMap<>(adopted);
        theirAdopted.forEach(base::putIfAbsent);
        Map<String, String> out = new TreeMap<>(tree);
        LineageCodeMerge.Result main = LineageCodeMerge.merge(ProjectMerge.under(base, "src/main/"),
                ProjectMerge.under(tree, "src/main/"), ProjectMerge.under(theirs, "src/main/"), "receiving", label);
        main.files().forEach((p, t) -> out.put("src/main/" + p, t));
        LineageTestMerge.Result tests = LineageTestMerge.merge(ProjectMerge.javaOnly(ProjectMerge.under(tree, "src/test/")),
                ProjectMerge.javaOnly(ProjectMerge.under(theirs, "src/test/")), "receiving", label);
        tests.files().forEach((p, t) -> out.put("src/test/" + p, t));
        conflicts.addAll(main.conflicts());
        conflicts.addAll(tests.conflicts());
        theirs.forEach((p, t) -> {
            boolean code = p.startsWith("src/main/") || (p.startsWith("src/test/") && p.endsWith(".java"));
            if (!code && !p.equals("pom.xml") && !p.equals("README.md")) {
                String had = out.putIfAbsent(p, t);
                if (had != null && !had.equals(t)) conflicts.add(p + ": both projects have it; the receiving one's kept");
            }
        });
        for (String p : theirs.keySet()) {
            if (!tree.containsKey(p) && out.containsKey(p)) provenance.add(p + "\tfile\t" + label);
        }
        return out;
    }

    private static Map<String, String> placed(Map<String, String> tree, Map<String, String> theirs, String label,
                                              List<String> conflicts, List<String> provenance, List<String> notes) {
        Map<String, String> out = new TreeMap<>(tree);
        ProjectMerge.place(out, theirs, "", label, provenance, conflicts, p -> !p.equals("pom.xml") && !p.equals("README.md"));
        if (tree.containsKey("pom.xml") && theirs.containsKey("pom.xml")) {
            ProjectMerge.Pom ours = ProjectMerge.Pom.parse(tree.get("pom.xml"));
            List<String> added = ours.addDependenciesOf(ProjectMerge.Pom.parse(theirs.get("pom.xml")));
            if (!added.isEmpty()) {
                out.put("pom.xml", ours.render());
                notes.add("the build gains " + label + "'s dependencies: " + String.join(", ", added));
            }
        }
        return out;
    }

    private static String digest(Map<String, String> tree) {
        StringBuilder all = new StringBuilder();
        new TreeMap<>(tree).forEach((p, t) -> all.append(p).append('\u0000').append(ProjectMerge.sha256(t)).append('\n'));
        return ProjectMerge.sha256(all.toString());
    }
}
