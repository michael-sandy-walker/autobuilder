package com.walpro.autobuilder.core.build;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * T4-CQ: keep what a file declared when the project was ADOPTED, whatever a writer puts there; the
 * writer's NEW declarations are kept beside it.
 *
 * <p><b>The harm, from the field.</b> participation-roles run 2026-09-25_19-57-35 was asked to ADD two
 * methods to a class and to keep its existing public methods as they are. The model wrote the whole
 * class back from memory: the two methods were right, and eight existing members were not -- a
 * re-grant that had merged two windows now replaced one, {@code grant(null)} threw, the empty value was
 * no longer shared. The suite passed and the run closed GOAL_FULFILLED.
 *
 * <p><b>Why the result is built FROM the adopted file.</b> The first version put the adopted members
 * back INTO the writer's rewrite. participation-totals run 2026-09-25_20-51-35 showed why that is the
 * wrong direction: the rewrite had also dropped a record component and two imports, so the restored
 * members called a constructor and named types the rewritten file no longer had -- 16 candidates in a
 * row failed to compile. Everything the adopted file had -- its header, its imports, its members, its
 * comments -- survives only if the result starts from it.
 *
 * <p><b>How.</b> A candidate that changed nothing the adopted file declared (layout and comments aside)
 * is returned as written. Otherwise the result is the adopted file plus the candidate's imports it lacks
 * and the candidate's members it lacks, matched by signature (a method or constructor by name and
 * parameter types, a field by its variable names, a record's compact constructor, a nested type by
 * name, recursively), each in the candidate's own text. What the candidate changed or removed is
 * reported. A candidate that cannot be parsed is returned unchanged: it will not compile either.
 *
 * <p><b>Open members (2026-09-27).</b> A ticket may EXTEND an existing entry point: receiver-questions
 * run 2026-09-26_20-45-54 had its change to {@code StuckQuestions.answer} restored as adopted three times,
 * so only helpers nothing called could land. The operator names such members after {@code except} on the
 * {@code members:} line; a member of that name (every overload) is taken as the candidate wrote it, in
 * place of the adopted one. Everything else is kept as before.
 */
public final class PreservedMembers {

    private PreservedMembers() {
    }

    /** The content to write, and what of the adopted file the candidate had changed or removed. */
    public record Result(String content, List<String> restored) {
    }

    public static Result restore(String baseline, String candidate) {
        return restore(baseline, candidate, Set.of());
    }

    /** As {@link #restore(String, String)}, with {@code open}: the members (by simple name) the candidate may change. */
    public static Result restore(String baseline, String candidate, Set<String> open) {
        Set<String> opened = open == null ? Set.of() : open;
        if (baseline == null || candidate == null || baseline.equals(candidate)) return new Result(candidate, List.of());
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        // A tree recovered from a syntax error is not the candidate's meaning: leave such a file for the
        // compiler to report, rather than build on a guess at it.
        ParseResult<CompilationUnit> pb = parser.parse(baseline);
        ParseResult<CompilationUnit> pc = parser.parse(candidate);
        if (!pb.isSuccessful() || !pc.isSuccessful() || pb.getResult().isEmpty() || pc.getResult().isEmpty()) {
            return new Result(candidate, List.of());
        }
        CompilationUnit b = pb.getResult().get();
        CompilationUnit c = pc.getResult().get();

        List<String> restored = new ArrayList<>();
        for (TypeDeclaration<?> bt : b.getTypes()) {
            Optional<TypeDeclaration<?>> ct = byName(c.getTypes(), bt.getNameAsString());
            if (ct.isEmpty()) restored.add(bt.getNameAsString() + " (removed)");
            else differences(bt, ct.get(), bt.getNameAsString(), restored, opened);
        }
        if (restored.isEmpty()) return new Result(candidate, List.of());

        List<int[]> at = new ArrayList<>();
        List<String> text = new ArrayList<>();
        for (TypeDeclaration<?> bt : b.getTypes()) {
            byName(c.getTypes(), bt.getNameAsString()).ifPresent(ct -> additions(bt, ct, baseline, candidate, at, text, opened));
        }
        for (TypeDeclaration<?> ct : c.getTypes()) {                   // a whole new top-level type
            if (byName(b.getTypes(), ct.getNameAsString()).isEmpty()) {
                text(ct, candidate).ifPresent(t -> {
                    at.add(new int[]{baseline.length(), baseline.length()});
                    text.add("\n" + t + "\n");
                });
            }
        }
        Set<String> have = new LinkedHashSet<>();
        for (ImportDeclaration i : b.getImports()) have.add(i.toString().strip());
        StringBuilder imports = new StringBuilder();
        for (ImportDeclaration i : c.getImports()) {
            if (have.add(i.toString().strip())) imports.append(i.toString().strip()).append("\n");
        }
        if (!imports.isEmpty()) {
            int pos = b.getImports().isEmpty()
                    ? b.getPackageDeclaration().flatMap(p -> span(p, baseline)).map(s -> s[1]).orElse(0)
                    : span(b.getImports().get(b.getImports().size() - 1), baseline).map(s -> s[1]).orElse(0);
            at.add(new int[]{pos, pos});
            text.add("\n" + imports.toString().stripTrailing());
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < at.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt((Integer i) -> at.get(i)[0]).reversed());
        StringBuilder out = new StringBuilder(baseline);
        for (int i : order) out.replace(at.get(i)[0], at.get(i)[1], text.get(i));
        return new Result(out.toString(), restored);
    }

    /** What the candidate changed of the adopted type: its header, and each member it changed or dropped. */
    private static void differences(TypeDeclaration<?> bt, TypeDeclaration<?> ct, String owner, List<String> out,
                                    Set<String> open) {
        if (!normalised(header(bt)).equals(normalised(header(ct)))) out.add(owner + " declaration (changed)");
        Map<String, BodyDeclaration<?>> cm = members(ct);
        for (Map.Entry<String, BodyDeclaration<?>> e : members(bt).entrySet()) {
            BodyDeclaration<?> c = cm.get(e.getKey());
            if (c == null) {
                out.add(owner + "." + e.getKey() + " (removed)");
            } else if (e.getValue() instanceof TypeDeclaration<?> bn && c instanceof TypeDeclaration<?> cn) {
                differences(bn, cn, owner + "." + bn.getNameAsString(), out, open);
            } else if (open.contains(simpleName(e.getKey()))) {
                // an open member: the candidate's version is taken, not a restoration
            } else if (!normalised(e.getValue()).equals(normalised(c))) {
                out.add(owner + "." + e.getKey() + " (changed)");
            }
        }
    }

    /** The candidate's members the adopted type lacks, inserted before the adopted type's closing brace. */
    private static void additions(TypeDeclaration<?> bt, TypeDeclaration<?> ct, String bsrc, String csrc,
                                  List<int[]> at, List<String> text, Set<String> open) {
        Map<String, BodyDeclaration<?>> bm = members(bt);
        StringBuilder added = new StringBuilder();
        for (Map.Entry<String, BodyDeclaration<?>> e : members(ct).entrySet()) {
            BodyDeclaration<?> b = bm.get(e.getKey());
            if (b == null) {
                text(e.getValue(), csrc).ifPresent(t -> added.append("\n    ").append(t).append("\n"));
            } else if (b instanceof TypeDeclaration<?> bn && e.getValue() instanceof TypeDeclaration<?> cn) {
                additions(bn, cn, bsrc, csrc, at, text, open);
            } else if (open.contains(simpleName(e.getKey())) && !normalised(b).equals(normalised(e.getValue()))) {
                // an open member the candidate changed: its text replaces the adopted one, in place
                Optional<int[]> where = span(b, bsrc);
                Optional<String> t = text(e.getValue(), csrc);
                if (where.isPresent() && t.isPresent()) {
                    at.add(where.get());
                    text.add(t.get());
                }
            }
        }
        if (added.isEmpty()) return;
        int pos = span(bt, bsrc).map(s -> bsrc.lastIndexOf('}', s[1] - 1)).orElse(-1);
        if (pos < 0) return;
        at.add(new int[]{pos, pos});
        text.add(added.toString());
    }

    /** The simple name a member key names: {@code answer(String,Map)} -> answer, {@code field a,b} -> a. */
    static String simpleName(String key) {
        String k = key.startsWith("field ") ? key.substring(6).split(",")[0]
                : key.startsWith("type ") ? key.substring(5) : key;
        int cut = k.length();
        for (char ch : new char[]{'(', '{'}) {
            int i = k.indexOf(ch);
            if (i >= 0) cut = Math.min(cut, i);
        }
        return k.substring(0, cut).strip();
    }

    /** A type declaration without its members: name, modifiers, annotations, type parameters, supertypes, components. */
    private static Node header(TypeDeclaration<?> t) {
        TypeDeclaration<?> h = t.clone();
        h.setMembers(new NodeList<>());
        return h;
    }

    private static Map<String, BodyDeclaration<?>> members(TypeDeclaration<?> t) {
        Map<String, BodyDeclaration<?>> out = new LinkedHashMap<>();
        for (BodyDeclaration<?> m : t.getMembers()) key(m).ifPresent(k -> out.putIfAbsent(k, m));
        return out;
    }

    private static Optional<TypeDeclaration<?>> byName(List<TypeDeclaration<?>> types, String name) {
        return types.stream().filter(t -> t.getNameAsString().equals(name)).findFirst();
    }

    /** How a member is matched across versions; empty for members this does not track (initialisers). */
    public static Optional<String> key(BodyDeclaration<?> m) {
        if (m instanceof CompactConstructorDeclaration cc) return Optional.of(cc.getNameAsString() + "{compact}");
        if (m instanceof CallableDeclaration<?> cd) {
            return Optional.of(cd.getNameAsString() + cd.getParameters().stream().map(p -> p.getType().asString())
                    .collect(Collectors.joining(",", "(", ")")));
        }
        if (m instanceof FieldDeclaration fd) {
            return Optional.of("field " + fd.getVariables().stream().map(v -> v.getNameAsString()).collect(Collectors.joining(",")));
        }
        if (m instanceof TypeDeclaration<?> td) return Optional.of("type " + td.getNameAsString());
        return Optional.empty();
    }

    private static final DefaultPrettyPrinter NO_COMMENTS = new DefaultPrettyPrinter(new DefaultPrinterConfiguration()
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_COMMENTS))
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_JAVADOC)));

    private static String normalised(Node n) {
        return NO_COMMENTS.print(n).replaceAll("\\s+", " ").strip();
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

    /** A 1-based line and column (JavaParser counts a tab as one column) as a string offset. */
    private static int offset(String src, int line, int column) {
        int pos = 0;
        for (int l = 1; l < line; l++) {
            int nl = src.indexOf('\n', pos);
            if (nl < 0) return -1;
            pos = nl + 1;
        }
        return pos + column - 1;
    }
}
