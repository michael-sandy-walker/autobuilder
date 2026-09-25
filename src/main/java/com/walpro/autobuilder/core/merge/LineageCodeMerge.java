package com.walpro.autobuilder.core.merge;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.walpro.autobuilder.core.build.PreservedMembers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * T4-CS, second slice: merge the LIBRARY code of two lineages that grew one adopted project in
 * different directions, against the tree they both started from -- a three-way merge whose unit is a
 * declaration, not a line.
 *
 * <p><b>Why a declaration.</b> Two lineages that each add a method to the same class conflict on
 * every line-based merge (both appended at the end of one file) and on none of their meaning. Taken by
 * declaration against the common ancestor: one untouched by both stays; one only one parent changed
 * takes that change; one only one parent added is added; one both added or changed identically is
 * taken once. What is left is a real conflict -- the same declaration made two different ways -- and it
 * is NOT decided here: the first parent's is kept and the conflict reported, so the child's test suite
 * (both parents' tests together) is what says whether the merge holds.
 *
 * <p>A file only one parent changed is taken from that parent; a file either parent cannot parse is
 * taken from the first and reported. Imports are unioned.
 */
public final class LineageCodeMerge {

    private LineageCodeMerge() {
    }

    /** Where each declaration of the child came from, and what could not be merged. */
    public record Result(Map<String, String> files, List<LineageTestMerge.Origin> provenance, List<String> conflicts) {
    }

    public static Result merge(Map<String, String> base, Map<String, String> first, Map<String, String> second,
                               String firstLabel, String secondLabel) {
        Map<String, String> out = new TreeMap<>();
        List<LineageTestMerge.Origin> provenance = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>(new TreeMap<>(first).keySet());
        paths.addAll(new TreeMap<>(second).keySet());
        for (String path : paths) {
            String o = base.get(path);
            String a = first.get(path);
            String b = second.get(path);
            if (a != null && a.equals(b)) {
                out.put(path, a);
                continue;
            }
            if (b == null || b.equals(o)) {           // only the first parent has it, or only it changed it
                if (a != null) out.put(path, a);
                if (a != null && !a.equals(o)) provenance.add(new LineageTestMerge.Origin(path, "file", firstLabel));
                continue;
            }
            if (a == null || a.equals(o)) {
                out.put(path, b);
                provenance.add(new LineageTestMerge.Origin(path, "file", secondLabel));
                continue;
            }
            out.put(path, mergeFile(path, o, a, b, firstLabel, secondLabel, provenance, conflicts));
        }
        return new Result(out, provenance, conflicts);
    }

    private static String mergeFile(String path, String o, String a, String b, String la, String lb,
                                    List<LineageTestMerge.Origin> provenance, List<String> conflicts) {
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        ParseResult<CompilationUnit> po = o == null ? null : parser.parse(o);
        ParseResult<CompilationUnit> pa = parser.parse(a);
        ParseResult<CompilationUnit> pb = parser.parse(b);
        if (!pa.isSuccessful() || !pb.isSuccessful() || (po != null && !po.isSuccessful())) {
            conflicts.add(path + ": could not be parsed in every version; " + la + "'s kept whole");
            return a;
        }
        CompilationUnit ca = pa.getResult().orElseThrow();
        CompilationUnit cb = pb.getResult().orElseThrow();
        CompilationUnit co = po == null ? null : po.getResult().orElseThrow();
        List<int[]> edits = new ArrayList<>();          // [start, end] replaced by texts.get(i)
        List<String> texts = new ArrayList<>();
        for (TypeDeclaration<?> ta : ca.getTypes()) {
            Optional<TypeDeclaration<?>> tb = cb.getTypes().stream().filter(t -> t.getNameAsString().equals(ta.getNameAsString())).findFirst();
            Optional<TypeDeclaration<?>> to = co == null ? Optional.empty()
                    : co.getTypes().stream().filter(t -> t.getNameAsString().equals(ta.getNameAsString())).findFirst();
            if (tb.isEmpty()) continue;
            mergeType(path, ta, tb.get(), to.orElse(null), a, b, la, lb, edits, texts, provenance, conflicts, ta.getNameAsString());
        }
        // Imports the second parent needs that the first does not have go after the first's last import.
        Set<String> have = new LinkedHashSet<>();
        for (ImportDeclaration i : ca.getImports()) have.add(i.toString().strip());
        StringBuilder imports = new StringBuilder();
        for (ImportDeclaration i : cb.getImports()) {
            if (!have.contains(i.toString().strip())) imports.append(i.toString().strip()).append("\n");
        }
        if (!imports.isEmpty()) {
            int at = ca.getImports().isEmpty()
                    ? ca.getPackageDeclaration().flatMap(p -> span(p, a)).map(s -> s[1]).orElse(0)
                    : span(ca.getImports().get(ca.getImports().size() - 1), a).map(s -> s[1]).orElse(0);
            edits.add(new int[]{at, at});
            texts.add("\n" + imports.toString().stripTrailing());
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt((Integer i) -> edits.get(i)[0]).reversed());
        StringBuilder out = new StringBuilder(a);
        for (int i : order) out.replace(edits.get(i)[0], edits.get(i)[1], texts.get(i));
        return out.toString();
    }

    private static void mergeType(String path, TypeDeclaration<?> ta, TypeDeclaration<?> tb, TypeDeclaration<?> to,
                                  String a, String b, String la, String lb, List<int[]> edits, List<String> texts,
                                  List<LineageTestMerge.Origin> provenance, List<String> conflicts, String owner) {
        Map<String, BodyDeclaration<?>> ma = members(ta);
        Map<String, BodyDeclaration<?>> mb = members(tb);
        Map<String, BodyDeclaration<?>> mo = to == null ? Map.of() : members(to);
        StringBuilder added = new StringBuilder();
        for (Map.Entry<String, BodyDeclaration<?>> e : mb.entrySet()) {
            String k = e.getKey();
            BodyDeclaration<?> vb = e.getValue();
            BodyDeclaration<?> va = ma.get(k);
            BodyDeclaration<?> vo = mo.get(k);
            if (va instanceof TypeDeclaration<?> na && vb instanceof TypeDeclaration<?> nb) {
                mergeType(path, na, nb, vo instanceof TypeDeclaration<?> no ? no : null, a, b, la, lb,
                        edits, texts, provenance, conflicts, owner + "." + na.getNameAsString());
                continue;
            }
            boolean bChanged = vo == null || !same(vo, vb);
            if (!bChanged) continue;                                   // the second parent left it as it was
            if (va == null && vo == null) {                            // added by the second parent only
                text(vb, b).ifPresent(t -> added.append("\n    ").append(t).append("\n"));
                provenance.add(new LineageTestMerge.Origin(path, owner + "." + k, lb));
                continue;
            }
            if (va != null && same(va, vb)) continue;                  // both made it the same way
            boolean aChanged = va == null || vo == null || !same(vo, va);
            if (va != null && !aChanged) {                             // only the second parent changed it
                Optional<int[]> at = span(va, a);
                Optional<String> t = text(vb, b);
                if (at.isPresent() && t.isPresent()) {
                    edits.add(at.get());
                    texts.add(t.get());
                    provenance.add(new LineageTestMerge.Origin(path, owner + "." + k, lb));
                }
                continue;
            }
            conflicts.add(path + ": " + owner + "." + k + " was made differently by both parents; " + la + "'s kept");
        }
        for (String k : ma.keySet()) {
            if (!mo.containsKey(k) && !mb.containsKey(k)) provenance.add(new LineageTestMerge.Origin(path, owner + "." + k, la));
        }
        if (!added.isEmpty()) {
            int at = span(ta, a).map(s -> a.lastIndexOf('}', s[1] - 1)).orElse(-1);
            if (at >= 0) {
                edits.add(new int[]{at, at});
                texts.add(added.toString());
            }
        }
    }

    private static Map<String, BodyDeclaration<?>> members(TypeDeclaration<?> t) {
        Map<String, BodyDeclaration<?>> out = new LinkedHashMap<>();
        for (BodyDeclaration<?> m : t.getMembers()) PreservedMembers.key(m).ifPresent(k -> out.putIfAbsent(k, m));
        return out;
    }

    private static final DefaultPrettyPrinter NO_COMMENTS = new DefaultPrettyPrinter(new DefaultPrinterConfiguration()
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_COMMENTS))
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_JAVADOC)));

    private static boolean same(Node x, Node y) {
        return NO_COMMENTS.print(x).replaceAll("\\s+", " ").strip().equals(NO_COMMENTS.print(y).replaceAll("\\s+", " ").strip());
    }

    private static Optional<String> text(Node n, String src) {
        return span(n, src).map(s -> src.substring(s[0], s[1]));
    }

    private static Optional<int[]> span(Node n, String src) {
        Optional<Range> r = n.getRange();
        if (r.isEmpty()) return Optional.empty();
        int start = offset(src, r.get().begin.line, r.get().begin.column);
        int end = offset(src, r.get().end.line, r.get().end.column) + 1;
        return start < 0 || end <= start || end > src.length() ? Optional.empty() : Optional.of(new int[]{start, end});
    }

    private static int offset(String src, int line, int column) {
        int pos = 0;
        for (int l = 1; l < line; l++) {
            int nl = src.indexOf('\n', pos);
            if (nl < 0) return -1;
            pos = nl + 1;
        }
        return pos + column - 1;
    }

    /**
     * {@code <base src/main> <first src/main> <second src/main> <child root> <first label> <second label>}:
     * writes the merged library under {@code <child root>/src/main} and appends the provenance and the
     * conflicts to {@code MERGE-PROVENANCE.tsv} and {@code MERGE-CONFLICTS.txt} there. Exit 0 whenever it ran.
     */
    public static void main(String[] args) throws java.io.IOException {
        if (args.length != 6) {
            System.err.println("usage: LineageCodeMerge <baseMain> <firstMain> <secondMain> <childRoot> <firstLabel> <secondLabel>");
            System.exit(2);
        }
        java.nio.file.Path child = java.nio.file.Path.of(args[3]);
        Result r = merge(read(java.nio.file.Path.of(args[0])), read(java.nio.file.Path.of(args[1])),
                read(java.nio.file.Path.of(args[2])), args[4], args[5]);
        for (Map.Entry<String, String> e : r.files().entrySet()) {
            java.nio.file.Path p = child.resolve("src/main").resolve(e.getKey());
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, e.getValue());
        }
        StringBuilder prov = new StringBuilder();
        for (LineageTestMerge.Origin o : r.provenance()) prov.append("main/").append(o.file()).append('\t').append(o.member()).append('\t').append(o.from()).append('\n');
        java.nio.file.Files.writeString(child.resolve("MERGE-PROVENANCE.tsv"), prov.toString(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        java.nio.file.Files.writeString(child.resolve("MERGE-CONFLICTS.txt"),
                r.conflicts().isEmpty() ? "" : String.join("\n", r.conflicts()) + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        System.out.println("merged library: " + r.files().size() + " files, " + r.provenance().size() + " declarations from one parent, "
                + r.conflicts().size() + " conflict(s)");
    }

    private static Map<String, String> read(java.nio.file.Path root) throws java.io.IOException {
        Map<String, String> out = new TreeMap<>();
        if (!java.nio.file.Files.isDirectory(root)) return out;
        try (var walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk.filter(java.nio.file.Files::isRegularFile)::iterator) {
                out.put(root.relativize(p).toString().replace('\\', '/'), java.nio.file.Files.readString(p));
            }
        }
        return out;
    }
}
