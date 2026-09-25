package com.walpro.autobuilder.core.merge;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * T4-CS, first slice: merge the TEST layers of two lineages whose library code is identical -- an
 * adopted project whose {@code src/main} both runs were forbidden to touch, evolved under different
 * seeds. Every assertion either suite makes was checked against the same code, so the parents cannot
 * disagree about behaviour; they can only collide STRUCTURALLY, by writing the same test class twice.
 *
 * <p><b>Why a merge buys something here and did not before.</b> Two lineages of ONE seed redo the same
 * work: participation's run 1 and run 2 unioned cover exactly what the better one covers (12 of 12).
 * Two lineages of DIFFERENT seeds on the same module do not: participation (12 entry points) and
 * participation-b (10, two shared) unioned cover 20 of 20 where the better parent covers 16
 * ({@code scripts/code_lineage_overlap.py}).
 *
 * <p><b>How.</b> A file only one parent has is taken whole; a file both have identically is taken once.
 * A file both have DIFFERENTLY is merged by member, on the parsed tree: imports are unioned; a member
 * of the second parent that the first also has, with the same text, is dropped as shared; one with the
 * same signature and different text is kept under a new name, and every unscoped call or name reference
 * to it inside the second parent's members is renamed with it, so each parent's tests still call their
 * own helpers and read their own fields. Anything else that differs -- constructors, initialisers,
 * nested types -- is NOT guessed at: the first parent's is kept and the conflict is reported.
 *
 * <p>Deterministic, no model call. What the merge claims is checked by running the child's suite; the
 * caller does that ({@code scripts/merge_lineages.sh}).
 */
public final class LineageTestMerge {

    private LineageTestMerge() {
    }

    /** One member of the child's test layer, and which parent(s) it came from. */
    public record Origin(String file, String member, String from) {
    }

    /** The merged files (path relative to the test root -> source), provenance, and what was not merged. */
    public record Result(Map<String, String> files, List<Origin> provenance, List<String> conflicts) {
    }

    public static Result merge(Map<String, String> first, Map<String, String> second, String firstLabel, String secondLabel) {
        Map<String, String> out = new TreeMap<>();
        List<Origin> provenance = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        Set<String> paths = new LinkedHashSet<>(new TreeMap<>(first).keySet());
        paths.addAll(new TreeMap<>(second).keySet());
        for (String path : paths) {
            String a = first.get(path);
            String b = second.get(path);
            if (a == null || b == null || a.equals(b)) {
                String src = a != null ? a : b;
                String from = a != null && b != null ? firstLabel + "+" + secondLabel : a != null ? firstLabel : secondLabel;
                out.put(path, src);
                members(parser, src).forEach(m -> provenance.add(new Origin(path, m, from)));
                continue;
            }
            Optional<CompilationUnit> ca = parser.parse(a).getResult();
            Optional<CompilationUnit> cb = parser.parse(b).getResult();
            if (ca.isEmpty() || cb.isEmpty() || ca.get().getTypes().isEmpty() || cb.get().getTypes().isEmpty()) {
                out.put(path, a);
                conflicts.add(path + ": could not be parsed in both parents; " + firstLabel + "'s copy kept whole");
                members(parser, a).forEach(m -> provenance.add(new Origin(path, m, firstLabel)));
                continue;
            }
            out.put(path, mergeUnit(path, ca.get(), cb.get(), firstLabel, secondLabel, provenance, conflicts));
        }
        return new Result(out, provenance, conflicts);
    }

    private static String mergeUnit(String path, CompilationUnit a, CompilationUnit b, String la, String lb,
                                    List<Origin> provenance, List<String> conflicts) {
        Set<String> imports = a.getImports().stream().map(ImportDeclaration::toString).collect(Collectors.toSet());
        for (ImportDeclaration i : b.getImports()) {
            if (!imports.contains(i.toString())) a.addImport(i.clone());
        }
        TypeDeclaration<?> ta = a.getType(0);
        TypeDeclaration<?> tb = b.getType(0);
        if (!ta.getNameAsString().equals(tb.getNameAsString())) {
            conflicts.add(path + ": the parents declare different types; " + la + "'s kept");
            return a.toString();
        }
        Map<String, BodyDeclaration<?>> ofA = new LinkedHashMap<>();
        for (BodyDeclaration<?> m : ta.getMembers()) keys(m).forEach(k -> ofA.put(k, m));
        for (BodyDeclaration<?> m : ta.getMembers()) provenance.add(new Origin(path, label(m), la));

        // The renames first: a differing member of the second parent is renamed together with every
        // reference to it among that parent's own members.
        String suffix = "_" + lb.replaceAll("[^A-Za-z0-9]", "");
        Map<String, String> renameMethods = new LinkedHashMap<>();
        Map<String, String> renameFields = new LinkedHashMap<>();
        for (BodyDeclaration<?> m : tb.getMembers()) {
            for (String k : keys(m)) {
                BodyDeclaration<?> same = ofA.get(k);
                if (same == null || same.toString().equals(m.toString())) continue;
                if (m instanceof MethodDeclaration md) renameMethods.put(md.getNameAsString(), md.getNameAsString() + suffix);
                if (m instanceof FieldDeclaration fd) {
                    for (VariableDeclarator v : fd.getVariables()) renameFields.put(v.getNameAsString(), v.getNameAsString() + suffix);
                }
            }
        }
        for (BodyDeclaration<?> m : tb.getMembers()) {
            List<String> ks = keys(m);
            boolean shared = !ks.isEmpty() && ks.stream().allMatch(k -> ofA.containsKey(k) && ofA.get(k).toString().equals(m.toString()));
            if (shared) {
                provenance.replaceAll(o -> o.file().equals(path) && o.member().equals(label(m)) ? new Origin(path, o.member(), la + "+" + lb) : o);
                continue;
            }
            boolean mergeable = m instanceof MethodDeclaration || m instanceof FieldDeclaration;
            if (!mergeable) {
                if (ks.stream().anyMatch(ofA::containsKey) || ks.isEmpty()) {
                    conflicts.add(path + ": " + label(m) + " differs between the parents; " + la + "'s kept, " + lb + "'s dropped");
                    continue;
                }
            }
            BodyDeclaration<?> copy = m.clone();
            rename(copy, renameMethods, renameFields);
            ta.addMember(copy);
            provenance.add(new Origin(path, label(copy), lb));
        }
        return a.toString();
    }

    private static void rename(BodyDeclaration<?> m, Map<String, String> methods, Map<String, String> fields) {
        if (m instanceof MethodDeclaration md && methods.containsKey(md.getNameAsString())) md.setName(methods.get(md.getNameAsString()));
        if (m instanceof FieldDeclaration fd) {
            for (VariableDeclarator v : fd.getVariables()) {
                if (fields.containsKey(v.getNameAsString())) v.setName(fields.get(v.getNameAsString()));
            }
        }
        for (MethodCallExpr c : m.findAll(MethodCallExpr.class)) {
            boolean own = c.getScope().isEmpty() || c.getScope().get().isThisExpr();
            if (own && methods.containsKey(c.getNameAsString())) c.setName(methods.get(c.getNameAsString()));
        }
        for (NameExpr n : m.findAll(NameExpr.class)) {
            if (fields.containsKey(n.getNameAsString()) && !declaredLocally(n)) n.setName(fields.get(n.getNameAsString()));
        }
        for (FieldAccessExpr f : m.findAll(FieldAccessExpr.class)) {
            if (f.getScope().isThisExpr() && fields.containsKey(f.getNameAsString())) f.setName(fields.get(f.getNameAsString()));
        }
    }

    /** A name a local variable or parameter of the enclosing method shadows is not the field. */
    private static boolean declaredLocally(NameExpr n) {
        return n.findAncestor(MethodDeclaration.class).map(md ->
                md.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(n.getNameAsString()))
                        || md.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(n.getNameAsString()))
        ).orElse(false);
    }

    /** How a member is matched across the parents: a method by signature, a field by each variable's name. */
    private static List<String> keys(BodyDeclaration<?> m) {
        if (m instanceof MethodDeclaration md) {
            return List.of("method " + md.getNameAsString() + md.getParameters().stream()
                    .map(p -> p.getType().asString()).collect(Collectors.joining(",", "(", ")")));
        }
        if (m instanceof FieldDeclaration fd) {
            return fd.getVariables().stream().map(v -> "field " + v.getNameAsString()).toList();
        }
        if (m instanceof com.github.javaparser.ast.body.ConstructorDeclaration cd) {
            return List.of("constructor" + cd.getParameters().stream().map(p -> p.getType().asString())
                    .collect(Collectors.joining(",", "(", ")")));
        }
        if (m instanceof TypeDeclaration<?> td) return List.of("type " + td.getNameAsString());
        return List.of();
    }

    private static String label(BodyDeclaration<?> m) {
        List<String> k = keys(m);
        return k.isEmpty() ? m.getClass().getSimpleName() : String.join(" ", k);
    }

    private static List<String> members(JavaParser parser, String src) {
        return parser.parse(src).getResult().filter(cu -> !cu.getTypes().isEmpty())
                .map(cu -> cu.getType(0).getMembers().stream().map(LineageTestMerge::label).toList())
                .orElse(List.of());
    }

    /**
     * {@code <first test root> <second test root> <child root> <first label> <second label>}: writes the
     * merged sources under {@code <child root>/src/test}, and {@code MERGE-PROVENANCE.tsv} (file, member,
     * parent) and {@code MERGE-CONFLICTS.txt} at the child root. Exit 0 whenever the merge ran.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 5) {
            System.err.println("usage: LineageTestMerge <firstTestRoot> <secondTestRoot> <childRoot> <firstLabel> <secondLabel>");
            System.exit(2);
        }
        Path child = Path.of(args[2]);
        Result r = merge(read(Path.of(args[0])), read(Path.of(args[1])), args[3], args[4]);
        for (Map.Entry<String, String> e : r.files().entrySet()) {
            Path p = child.resolve("src/test").resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
        Files.writeString(child.resolve("MERGE-PROVENANCE.tsv"), r.provenance().stream()
                .map(o -> o.file() + "\t" + o.member() + "\t" + o.from()).collect(Collectors.joining("\n", "file\tmember\tfrom\n", "\n")));
        Files.writeString(child.resolve("MERGE-CONFLICTS.txt"), String.join("\n", r.conflicts()) + (r.conflicts().isEmpty() ? "" : "\n"));
        System.out.println("merged " + r.files().size() + " files, " + r.provenance().size() + " members, "
                + r.conflicts().size() + " conflict(s)");
    }

    private static Map<String, String> read(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(root)) return out;
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                out.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        return out;
    }
}
