package com.walpro.autobuilder.protocol.evolution;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.walpro.autobuilder.core.build.PreservedMembers;
import com.walpro.autobuilder.core.build.SkeletonStubber;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T6-T, the hybrid's member-level application: a whole-file block for an EXISTING Java file is applied declaration by
 * declaration -- each member it writes replaces the member of the same signature (or the skeleton stub of the same name
 * and arity) or is added; a member it leaves out is KEPT; imports are united; the type's annotations are united. So a
 * whole file written from the parts a prompt showed changes what it wrote and nothing else, and no member is ever
 * duplicated.
 *
 * <p>Why. ladder-grind field50 run 1 lost 15 of 19 candidates to text: whole-file rewrites written from parts dropped
 * the rest of a 1,079-line class ("class, interface, enum, or record expected"), dropped a class header ("unnamed
 * classes"), and declared {@code @Slf4j} and {@code refuse(..)} twice. A declaration is the unit those failures broke;
 * {@link PreservedMembers} and the lineage merge already work in it.
 *
 * <p>Empty when either side does not parse or the written file lacks the existing file's type -- the block is then
 * left as written, and the compile check decides.
 */
public final class MemberMerge {

    private MemberMerge() {
    }

    public record Merged(String content, List<String> replaced, List<String> added, List<String> kept) {
    }

    private static final JavaParser PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    public static Optional<Merged> merge(String existing, String written) {
        CompilationUnit ex = parse(existing);
        if (ex == null || ex.getTypes().isEmpty()) return Optional.empty();
        TypeDeclaration<?> exType = ex.getType(0);
        CompilationUnit wr = parse(written);
        if (wr == null || wr.getTypes().isEmpty() || wr.getTypes().stream().noneMatch(t -> t.getNameAsString().equals(exType.getNameAsString()))) {
            // a FRAGMENT: only the members to add or change (field50: the model wrote just grindDetected(..), just the
            // GrindDetected record) -- read as the body of the existing type
            String asType = fragmentAsType(written, exType.getNameAsString());
            if (asType == null) return Optional.empty();
            written = asType;
            wr = parse(written);
        }
        TypeDeclaration<?> wrType = wr.getTypes().stream().filter(t -> t.getNameAsString().equals(exType.getNameAsString()))
                .findFirst().orElse(null);
        if (wrType == null) return Optional.empty();

        // the written members, last one winning when the answer repeated a signature
        Map<String, BodyDeclaration<?>> writtenByKey = new LinkedHashMap<>();
        for (BodyDeclaration<?> m : wrType.getMembers()) PreservedMembers.key(m).ifPresent(k -> writtenByKey.put(k, m));

        List<Edit> edits = new ArrayList<>();
        List<String> replaced = new ArrayList<>(), added = new ArrayList<>(), kept = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (BodyDeclaration<?> m : exType.getMembers()) {
            Optional<String> key = PreservedMembers.key(m);
            if (key.isEmpty()) continue;
            BodyDeclaration<?> w = writtenByKey.get(key.get());
            if (w == null && isStub(m)) w = sameNameAndArity(writtenByKey, (CallableDeclaration<?>) m, used);
            if (w == null) { kept.add(key.get()); continue; }
            used.add(PreservedMembers.key(w).orElse(""));
            if (!text(written, w).equals(text(existing, m))) {
                // a written member without a comment keeps the existing member's comment
                int from = w.getComment().isPresent() ? start(existing, m) : offset(existing, m.getBegin().orElseThrow());
                String with = w.getComment().isPresent() ? text(written, w) : written.substring(offset(written, w.getBegin().orElseThrow()), end(written, w));
                edits.add(new Edit(from, end(existing, m), with));
                replaced.add(key.get());
            }
        }
        StringBuilder additions = new StringBuilder();
        for (Map.Entry<String, BodyDeclaration<?>> w : writtenByKey.entrySet()) {
            if (used.contains(w.getKey()) || exType.getMembers().stream().anyMatch(m -> PreservedMembers.key(m).map(w.getKey()::equals).orElse(false))) continue;
            additions.append("\n    ").append(text(written, w.getValue()).replace("\n", "\n")).append("\n");
            added.add(w.getKey());
        }
        if (!additions.isEmpty()) {
            int close = existing.lastIndexOf('}', offset(existing, exType.getEnd().orElseThrow()));
            edits.add(new Edit(close, close, additions.toString()));
        }
        // the type's annotations: the written ones the existing type lacks are added
        StringBuilder annotations = new StringBuilder();
        for (AnnotationExpr a : wrType.getAnnotations()) {
            if (exType.getAnnotations().stream().noneMatch(e -> e.getNameAsString().equals(a.getNameAsString()))) annotations.append(a).append("\n");
        }
        if (!annotations.isEmpty()) {
            int at = start(existing, exType);
            edits.add(new Edit(at, at, annotations.toString()));
        }
        // imports united
        StringBuilder imports = new StringBuilder();
        for (ImportDeclaration i : wr.getImports()) {
            if (ex.getImports().stream().noneMatch(e -> e.toString().strip().equals(i.toString().strip()))) imports.append(i.toString().strip()).append("\n");
        }
        if (!imports.isEmpty()) {
            int at = ex.getImports().isEmpty()
                    ? ex.getPackageDeclaration().map(p -> lineEnd(existing, end(existing, p))).orElse(0)
                    : lineEnd(existing, end(existing, ex.getImports().get(ex.getImports().size() - 1)));
            edits.add(new Edit(at, at, (ex.getImports().isEmpty() ? "\n" : "") + imports));
        }
        if (edits.isEmpty()) return Optional.of(new Merged(existing, replaced, added, kept));
        edits.sort(Comparator.comparingInt(Edit::from).reversed());
        StringBuilder out = new StringBuilder(existing);
        for (Edit e : edits) out.replace(e.from(), e.to(), e.text());
        return parse(out.toString()) == null ? Optional.empty() : Optional.of(new Merged(out.toString(), replaced, added, kept));
    }

    /**
     * A fragment of members as a compilation unit of the existing type's name: its import lines first, the rest as the
     * type's body. A copied closing brace of the type is tolerated once. Null when it does not parse that way.
     */
    static String fragmentAsType(String fragment, String typeName) {
        StringBuilder imports = new StringBuilder(), body = new StringBuilder();
        for (String line : fragment.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("import ") || t.startsWith("package ")) {
                if (t.startsWith("import ")) imports.append(t).append("\n");
            } else {
                body.append(line).append("\n");
            }
        }
        String b = body.toString().stripTrailing();
        for (int attempt = 0; attempt < 2; attempt++) {
            String unit = imports + "class " + typeName + " {\n" + b + "\n}\n";
            CompilationUnit cu = parse(unit);
            if (cu != null && !cu.getTypes().isEmpty() && cu.getType(0).getMembers().isNonEmpty()) return unit;
            if (!b.endsWith("}")) break;
            b = b.substring(0, b.length() - 1).stripTrailing();   // the type's own closing brace, copied
        }
        return null;
    }

    /** Whether a whole-file block for this existing file can be applied member by member. */
    public static boolean applies(String existing, String written) {
        return merge(existing, written).isPresent();
    }

    private record Edit(int from, int to, String text) {
    }

    private static boolean isStub(BodyDeclaration<?> m) {
        return m instanceof CallableDeclaration<?> && m.toString().contains("UnsupportedOperationException(\"" + SkeletonStubber.STUB_MESSAGE + "\")");
    }

    private static BodyDeclaration<?> sameNameAndArity(Map<String, BodyDeclaration<?>> written, CallableDeclaration<?> stub,
                                                       java.util.Set<String> used) {
        for (Map.Entry<String, BodyDeclaration<?>> w : written.entrySet()) {
            if (used.contains(w.getKey())) continue;
            if (w.getValue() instanceof CallableDeclaration<?> c && c.getNameAsString().equals(stub.getNameAsString())
                    && c.getParameters().size() == stub.getParameters().size()) return c;
        }
        return null;
    }

    private static CompilationUnit parse(String s) {
        try {
            var r = PARSER.parse(s);
            return r.isSuccessful() ? r.getResult().orElse(null) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A declaration's text as its file has it, with its comment. */
    private static String text(String source, Node n) {
        return source.substring(start(source, n), end(source, n));
    }

    private static int start(String source, Node n) {
        Position p = n.getComment().flatMap(Node::getBegin).orElse(n.getBegin().orElseThrow());
        Position q = n.getBegin().orElseThrow();
        return offset(source, p.isBefore(q) ? p : q);
    }

    private static int end(String source, Node n) {
        return offset(source, n.getEnd().orElseThrow()) + 1;
    }

    private static int lineEnd(String source, int at) {
        int nl = source.indexOf('\n', at);
        return nl < 0 ? source.length() : nl + 1;
    }

    /** A 1-based line/column as a character offset. */
    private static int offset(String source, Position p) {
        int line = 1, i = 0;
        while (line < p.line && i < source.length()) {
            if (source.charAt(i++) == '\n') line++;
        }
        return Math.min(source.length(), i + p.column - 1);
    }
}
