package com.walpro.autobuilder.core.build;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * T4-CQ: keep the declarations a file had when the project was ADOPTED, whatever a writer puts there;
 * new declarations beside them are kept too.
 *
 * <p><b>The harm, from the field.</b> participation-roles run 2026-09-25_19-57-35 was asked to ADD two
 * methods to a class and to keep its existing public methods as they are. The model wrote the whole
 * class back from memory: the two methods were right, and eight existing members were not -- a
 * re-grant that had merged two windows now replaced one (boost 0.5 where it was 1.5), {@code grant(null)}
 * threw, the empty value was no longer shared. The suite passed, because the existing tests pinned
 * only part of the existing behaviour, and the run closed GOAL_FULFILLED. "Do not change what exists"
 * was a sentence to the model; the adopted file itself was the authority, and nothing consulted it.
 *
 * <p><b>How.</b> Members are matched by kind and signature (a method or constructor by name and
 * parameter types, a field by its variable names, a record's compact constructor, a nested type by
 * name). One the candidate lacks is put back; one whose text differs -- comments and layout aside --
 * is replaced by the original's source text, exactly as it was written. Everything else in the
 * candidate is left as the writer wrote it. A candidate that cannot be parsed is returned unchanged:
 * it will not compile either, and the compile probe says so.
 */
public final class PreservedMembers {

    private PreservedMembers() {
    }

    /** The candidate with the baseline's members restored, and which ones were. */
    public record Result(String content, List<String> restored) {
    }

    public static Result restore(String baseline, String candidate) {
        if (baseline == null || candidate == null || baseline.equals(candidate)) return new Result(candidate, List.of());
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        // A tree recovered from a syntax error is not the candidate's meaning: leave such a file for the
        // compiler to report, rather than splice members into a guess at it.
        var pb = parser.parse(baseline);
        var pc = parser.parse(candidate);
        if (!pb.isSuccessful() || !pc.isSuccessful()) return new Result(candidate, List.of());
        Optional<CompilationUnit> b = pb.getResult();
        Optional<CompilationUnit> c = pc.getResult();
        if (b.isEmpty() || c.isEmpty()) return new Result(candidate, List.of());
        List<Edit> edits = new ArrayList<>();
        List<String> restored = new ArrayList<>();
        for (TypeDeclaration<?> bt : b.get().getTypes()) {
            c.get().getTypes().stream().filter(t -> t.getNameAsString().equals(bt.getNameAsString())).findFirst()
                    .ifPresent(ct -> compare(bt, ct, baseline, candidate, edits, restored, bt.getNameAsString()));
        }
        if (edits.isEmpty()) return new Result(candidate, List.of());
        // Applied from the end of the file backwards, so each range still points at what it named.
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder out = new StringBuilder(candidate);
        for (Edit e : edits) out.replace(e.start(), e.end(), e.text());
        return new Result(out.toString(), restored);
    }

    private record Edit(int start, int end, String text) {
    }

    private static void compare(TypeDeclaration<?> bt, TypeDeclaration<?> ct, String bsrc, String csrc,
                                List<Edit> edits, List<String> restored, String owner) {
        Map<String, BodyDeclaration<?>> candidates = new LinkedHashMap<>();
        for (BodyDeclaration<?> m : ct.getMembers()) key(m).ifPresent(k -> candidates.putIfAbsent(k, m));
        int insertAt = closingBrace(ct, csrc);
        StringBuilder missing = new StringBuilder();
        for (BodyDeclaration<?> bm : bt.getMembers()) {
            Optional<String> k = key(bm);
            if (k.isEmpty()) continue;
            BodyDeclaration<?> cm = candidates.get(k.get());
            if (cm == null) {
                text(bm, bsrc).ifPresent(t -> missing.append("\n    ").append(t).append("\n"));
                restored.add(owner + "." + k.get() + " (removed)");
                continue;
            }
            if (bm instanceof TypeDeclaration<?> btn && cm instanceof TypeDeclaration<?> ctn) {
                compare(btn, ctn, bsrc, csrc, edits, restored, owner + "." + btn.getNameAsString());
                continue;
            }
            if (normalised(bm).equals(normalised(cm))) continue;
            Optional<int[]> at = span(cm, csrc);
            Optional<String> original = text(bm, bsrc);
            if (at.isPresent() && original.isPresent()) {
                edits.add(new Edit(at.get()[0], at.get()[1], original.get()));
                restored.add(owner + "." + k.get() + " (changed)");
            }
        }
        if (!missing.isEmpty() && insertAt >= 0) edits.add(new Edit(insertAt, insertAt, missing.toString()));
    }

    /** How a member is matched across versions; empty for members this does not track (initialisers). */
    static Optional<String> key(BodyDeclaration<?> m) {
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

    /** The member's source text, annotations included, its own comment excluded. */
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

    private static int closingBrace(TypeDeclaration<?> t, String src) {
        return span(t, src).map(s -> src.lastIndexOf('}', s[1] - 1)).orElse(-1);
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
