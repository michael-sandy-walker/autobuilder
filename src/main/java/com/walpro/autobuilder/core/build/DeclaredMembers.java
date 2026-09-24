package com.walpro.autobuilder.core.build;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * T4-CQ: what a type in the tree ACTUALLY declares for callers -- its public methods, and for a
 * record its component accessors -- read from its source.
 *
 * <p>The authority for "does {@code getChildren()} exist on this type?" is the type's own
 * declaration. On the second adopted project the model called JavaBean getters on records nineteen
 * times ({@code getChildren()} where the record declares {@code children()}); showing it what is
 * declared is cheaper than any number of retries.
 */
public final class DeclaredMembers {

    private DeclaredMembers() {
    }

    /** Callable members of {@code simpleType} in {@code source}, as {@code name(ParamType, ...)}; empty when unparseable. */
    public static List<String> of(String source, String simpleType) {
        if (source == null || simpleType == null) return List.of();
        CompilationUnit cu;
        try {
            // Records need a language level the parser's default does not enable.
            cu = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
                    .parse(source).getResult().orElse(null);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (cu == null) return List.of();
        TypeDeclaration<?> found = null;
        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
            if (t.getNameAsString().equals(simpleType)) {
                found = t;
                break;
            }
        }
        if (found == null) return List.of();
        Optional<TypeDeclaration<?>> type = Optional.of(found);
        Set<String> out = new LinkedHashSet<>();
        if (type.get() instanceof RecordDeclaration r) {
            r.getParameters().forEach(p -> out.add(p.getNameAsString() + "()"));
        }
        boolean isInterface = type.get().isClassOrInterfaceDeclaration()
                && type.get().asClassOrInterfaceDeclaration().isInterface();
        for (MethodDeclaration m : type.get().getMethods()) {
            if (!(isInterface || m.hasModifier(Modifier.Keyword.PUBLIC))) continue;
            String params = m.getParameters().stream().map(p -> p.getType().asString())
                    .collect(Collectors.joining(", "));
            out.add(m.getNameAsString() + "(" + params + ")");
        }
        return new ArrayList<>(out);
    }
}
