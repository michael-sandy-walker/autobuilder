package com.walpro.autobuilder.core.build;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * T4-DY slice A: a stuck loop's failure signature read as a QUESTION about the code, answered from
 * the code itself.
 *
 * <p>A signature is the text the grind detector records for a failure that keeps recurring, e.g.
 * {@code "the build fails: Foo has no getBar()"}. When the signature names something the project's
 * own sources can answer for, {@link #answer} returns what those sources declare; otherwise empty.
 * Empty is "the signature alone does not say what to look up", never "nothing is wrong".
 */
public final class StuckQuestions {

    /** What was asked of the sources. */
    public enum Kind {
        /** A type was called with a member it may not declare: what does it declare? */
        MEMBERS,
        /** A record was constructed with arguments its constructor may not take: what does it take? */
        CONSTRUCTOR,
        /** A method was called with arguments it may not take: which overloads does its class declare? */
        OVERLOADS
    }

    /**
     * The answer, read from the type's own source.
     *
     * @param kind     the question
     * @param type     the simple name of the type the question is about
     * @param member   the member the signature names (for MEMBERS and OVERLOADS; null for CONSTRUCTOR)
     * @param declared what the type declares: for MEMBERS, {@link DeclaredMembers#of}'s list; for
     *                 CONSTRUCTOR, each constructor as {@code Type(ParamType name, ...)} starting with
     *                 a record's canonical one; for OVERLOADS, each method of that name as
     *                 {@code name(ParamType name, ...)}
     * @param meant    for MEMBERS, the declared member the missing one names ({@link DeclaredMembers#meantBy}),
     *                 or null when none; null for the other kinds
     */
    public record Answer(Kind kind, String type, String member, List<String> declared, String meant) {
    }

    /** "Foo has no bar(...)": the grind detector's own wording for a member a type may not declare. */
    private static final Pattern MISSING_MEMBER = Pattern.compile("\\b(\\w+) has no (\\w+)\\(");
    /** javac: "constructor Foo in record a.b.Foo cannot be applied to given types". */
    private static final Pattern CONSTRUCTOR_MISMATCH =
            Pattern.compile("\\bconstructor \\w+ in \\w+ ([\\w.]+) cannot be applied to given types");
    /** javac: "method bar in class a.b.Foo cannot be applied to given types". */
    private static final Pattern METHOD_MISMATCH =
            Pattern.compile("\\bmethod (\\w+) in \\w+ ([\\w.]+) cannot be applied to given types");

    private StuckQuestions() {
    }

    /**
     * The answer the project's sources give to the question {@code signature} poses, or empty.
     *
     * @param signature the recorded failure signature
     * @param sources   the project's source files, path relative to the project root -> content
     */
    public static Optional<Answer> answer(String signature, Map<String, String> sources) {
        if (signature == null || sources == null || sources.isEmpty()) return Optional.empty();
        Matcher m = MISSING_MEMBER.matcher(signature);
        if (m.find()) return members(m.group(1), m.group(2), sources);
        m = CONSTRUCTOR_MISMATCH.matcher(signature);
        if (m.find()) return constructors(m.group(1), sources);
        m = METHOD_MISMATCH.matcher(signature);
        if (m.find()) return overloads(m.group(2), m.group(1), sources);
        return Optional.empty();
    }

    private static Optional<Answer> members(String simpleName, String member, Map<String, String> sources) {
        // The signature names the type by its simple name only: answer when exactly one source declares it.
        List<String> declaring = sources.values().stream()
                .filter(src -> !declarations(src, t -> t.getNameAsString().equals(simpleName)).isEmpty())
                .toList();
        if (declaring.size() != 1) return Optional.empty();
        List<String> declared = DeclaredMembers.of(declaring.get(0), simpleName);
        return Optional.of(new Answer(Kind.MEMBERS, simpleName, member, declared,
                DeclaredMembers.meantBy(member, declared)));
    }

    private static Optional<Answer> constructors(String qualifiedName, Map<String, String> sources) {
        return typeNamed(qualifiedName, sources).map(type -> {
            Set<String> declared = new LinkedHashSet<>();
            if (type instanceof RecordDeclaration r) {
                declared.add(signature(r.getNameAsString(), r.getParameters()));
            }
            type.getConstructors().forEach(c -> declared.add(signature(c)));
            return new Answer(Kind.CONSTRUCTOR, type.getNameAsString(), null, new ArrayList<>(declared), null);
        });
    }

    private static Optional<Answer> overloads(String qualifiedName, String method, Map<String, String> sources) {
        return typeNamed(qualifiedName, sources).map(type -> new Answer(Kind.OVERLOADS, type.getNameAsString(),
                method, type.getMethodsByName(method).stream().map(StuckQuestions::signature).toList(), null));
    }

    /** The one type whose fully qualified name javac reported, from whichever source declares it. */
    private static Optional<TypeDeclaration<?>> typeNamed(String qualifiedName, Map<String, String> sources) {
        Predicate<TypeDeclaration<?>> named = t -> t.getFullyQualifiedName().map(qualifiedName::equals).orElse(false);
        return sources.values().stream().flatMap(src -> declarations(src, named).stream()).findFirst();
    }

    /** The types {@code source} declares, nested ones included, that satisfy {@code wanted}; empty when unparseable. */
    private static List<TypeDeclaration<?>> declarations(String source, Predicate<TypeDeclaration<?>> wanted) {
        if (source == null) return List.of();
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
        List<TypeDeclaration<?>> found = new ArrayList<>();
        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
            if (wanted.test(t)) found.add(t);
        }
        return found;
    }

    private static String signature(CallableDeclaration<?> callable) {
        return signature(callable.getNameAsString(), callable.getParameters());
    }

    /** {@code name(ParamType name, ...)}, the types as written in the source. */
    private static String signature(String name, NodeList<Parameter> parameters) {
        return name + parameters.stream()
                .map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : "") + " " + p.getNameAsString())
                .collect(Collectors.joining(", ", "(", ")"));
    }
}
