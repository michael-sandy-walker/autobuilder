package com.walpro.autobuilder.core.coldread;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.walpro.autobuilder.core.merge.CapabilityCut.Declaration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-EK: a tree with one capability taken out of WORKING but not out of the BUILD.
 *
 * <p>A claim about a capability is anchored only if the tree without that capability no longer bears it out. Deleting
 * the capability's declarations is the obvious way to take it out, and it proves nothing: the other capabilities use
 * its types, the tree no longer builds, and every claim "fails" on a tree that never ran. So the declarations stay and
 * their method bodies go -- each one throws -- and the tree still builds and starts. What a claim then observes is the
 * capability missing, not the build broken.
 *
 * <p>Kept, because without them nothing starts at all and the variant would again prove nothing: constructors (the
 * framework builds the objects through them), the program's entry point, and methods that declare the objects the
 * framework assembles ({@code @Bean}). A type's id stubs every method of that type; a member's id stubs only the
 * methods of that name (overloads share an id). Field ids stub nothing.
 */
public final class CapabilityStub {

    static final String STUB = "throw new UnsupportedOperationException(\"taken out to check a claim\");";

    private static final Set<String> KEEP_ANNOTATED = Set.of("Bean");

    private CapabilityStub() {
    }

    /**
     * @param stubbed the files that changed, by path, with their stubbed text
     * @param methods the methods stubbed, as {@code Type.method}
     */
    public record Result(Map<String, String> stubbed, List<String> methods) {
    }

    public static Result stub(Map<String, String> source, Collection<String> declarationIds, Map<String, Declaration> inventory) {
        Map<String, List<Declaration>> byFile = new LinkedHashMap<>();
        for (String id : declarationIds) {
            Declaration d = inventory.get(id);
            if (d == null || d.kind().equals("field")) continue;
            byFile.computeIfAbsent(d.path(), k -> new ArrayList<>()).add(d);
        }
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        Map<String, String> out = new LinkedHashMap<>();
        List<String> methods = new ArrayList<>();
        byFile.forEach((path, declarations) -> {
            String text = source.get(path);
            if (text == null) return;
            CompilationUnit cu = parser.parse(text).getResult().orElse(null);
            if (cu == null) return;
            boolean changed = false;
            for (Declaration d : declarations) {
                String typeId = d.kind().equals("type") ? d.id() : d.id().substring(0, d.id().lastIndexOf('.'));
                String member = d.kind().equals("type") ? null : d.id().substring(d.id().lastIndexOf('.') + 1);
                for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                    if (!names(t, typeId)) continue;
                    for (MethodDeclaration m : t.getMethods()) {
                        if (member != null && !m.getNameAsString().equals(member)) continue;
                        if (!stubbable(m)) continue;
                        String body = m.getBody().map(BlockStmt::toString).orElse("");
                        if (body.contains("taken out to check a claim")) continue;
                        m.setBody(new BlockStmt().addStatement(com.github.javaparser.StaticJavaParser.parseStatement(STUB)));
                        methods.add(simpleName(t) + "." + m.getNameAsString());
                        changed = true;
                    }
                }
            }
            if (changed) out.put(path, cu.toString());
        });
        return new Result(out, methods);
    }

    private static boolean stubbable(MethodDeclaration m) {
        if (m.getBody().isEmpty()) return false;
        if (m.isStatic() && m.getNameAsString().equals("main")) return false;
        return m.getAnnotations().stream().noneMatch(a -> KEEP_ANNOTATED.contains(a.getNameAsString()));
    }

    /** Whether {@code t} is the type an inventory id names: by its nested name, or qualified by its package. */
    private static boolean names(TypeDeclaration<?> t, String typeId) {
        String nested = simpleName(t);
        if (nested.equals(typeId)) return true;
        String pkg = t.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString()).orElse("");
        return (pkg.isEmpty() ? nested : pkg + "." + nested).equals(typeId);
    }

    private static String simpleName(TypeDeclaration<?> t) {
        String name = t.getNameAsString();
        Node n = t.getParentNode().orElse(null);
        while (n instanceof TypeDeclaration<?> outer) {
            name = outer.getNameAsString() + "." + name;
            n = outer.getParentNode().orElse(null);
        }
        return name;
    }
}
