package com.walpro.autobuilder.analysis.structure;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * T4-BQ slice 1: usage-derived member synthesis on the already-configured JavaParser symbol
 * solver ({@link com.walpro.autobuilder.config.JavaParserConfig}).
 *
 * <p>Given a missing type name and the on-disk source tree, this walker scans every call site that
 * references the type and reports the member signatures those call sites <i>prove</i> must exist:
 * <ul>
 *   <li>{@code new T(a, b)} &rarr; a constructor with two parameters;</li>
 *   <li>a receiver declared {@code T x} (field / parameter / local) followed by {@code x.foo(a)}
 *       &rarr; a method {@code foo} with one parameter.</li>
 * </ul>
 *
 * <p>The point is to create a missing-type skeleton with its <i>whole</i> usage closure in one
 * pass, instead of the resolver dribbling out one missing-<i>member</i> probe failure at a time
 * (the {@code CANNOT_FIND_SYMBOL|<member>} shape that dominated the 2026-06-14 run). Consumers:
 * {@link com.walpro.autobuilder.core.build.inject.IdiomSkeletonResolver}.
 *
 * <p><b>Conservative signature rule</b> (from the T4-BQ ticket): parameter and return types are
 * the exact resolved type only when it is a JDK type or primitive; otherwise {@code Object}. We
 * never emit a project type name (it might itself be missing, which would just relocate the
 * failure) and never guess generics beyond what call sites prove. Bodies are stubs — T4-AQ
 * stub-detection applies to the output; this closes the compile closure, it does not claim the
 * behaviour is implemented.
 */
@Slf4j
@Component
public class UsageDerivedMemberSynthesizer {

    /** A member a call site proves must exist on the missing type. */
    public record MemberSignature(boolean constructor, String name, List<String> paramTypes, String returnType) {

        /** Identity for dedup: constructors collapse by arity, methods by name + arity. */
        String dedupKey() {
            return (constructor ? "<init>" : name) + "/" + paramTypes.size();
        }
    }

    /**
     * Walks {@code javaRoot} and returns the deduped member signatures referenced for
     * {@code missingType}. Never throws on a malformed source file — unparseable files are
     * skipped (the tree is mid-evolution and often does not compile).
     */
    public List<MemberSignature> synthesize(String missingType, Path javaRoot) {
        if (missingType == null || missingType.isBlank() || javaRoot == null || !Files.isDirectory(javaRoot)) {
            return List.of();
        }
        Map<String, MemberSignature> bySignature = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(javaRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    // The type's own declaration file carries no usage we can learn members from.
                    .filter(p -> !p.getFileName().toString().equals(missingType + ".java"))
                    .forEach(p -> collectFrom(p, missingType, bySignature));
        } catch (Exception e) {
            log.debug("UsageDerivedMemberSynthesizer: walk failed for {} ({})", missingType, e.getMessage());
        }
        return new ArrayList<>(bySignature.values());
    }

    private void collectFrom(Path file, String missingType, Map<String, MemberSignature> sink) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(file);
        } catch (Exception e) {
            return; // mid-evolution source that does not parse — nothing to learn here
        }

        // Constructors: new T(args)
        cu.findAll(ObjectCreationExpr.class).stream()
                .filter(oce -> missingType.equals(oce.getType().getNameAsString()))
                .forEach(oce -> record(sink, new MemberSignature(
                        true, missingType, paramTypesOf(oce.getArguments()), "void")));

        // Receivers declared of the missing type, then x.method(args)
        Set<String> receivers = receiversOfType(cu, missingType);
        if (!receivers.isEmpty()) {
            cu.findAll(MethodCallExpr.class).stream()
                    .filter(mce -> mce.getScope().map(s -> s instanceof NameExpr ne
                            && receivers.contains(ne.getNameAsString())).orElse(false))
                    .forEach(mce -> record(sink, new MemberSignature(
                            false, mce.getNameAsString(), paramTypesOf(mce.getArguments()), "Object")));
        }
    }

    /** Names of fields / parameters / locals whose declared simple type is {@code missingType}. */
    private Set<String> receiversOfType(CompilationUnit cu, String missingType) {
        Set<String> names = new HashSet<>();
        cu.findAll(VariableDeclarator.class).stream()
                .filter(v -> missingType.equals(v.getType().asString()))
                .forEach(v -> names.add(v.getNameAsString()));
        cu.findAll(Parameter.class).stream()
                .filter(p -> missingType.equals(p.getType().asString()))
                .forEach(p -> names.add(p.getNameAsString()));
        return names;
    }

    private List<String> paramTypesOf(List<Expression> args) {
        List<String> types = new ArrayList<>(args.size());
        for (Expression arg : args) {
            types.add(safeType(arg));
        }
        return types;
    }

    /**
     * The exact resolved type only when it is a JDK type or primitive; {@code Object} otherwise.
     * Resolution is attempted on a frequently-uncompilable tree, so failure is the common case and
     * is swallowed.
     */
    private String safeType(Expression arg) {
        try {
            String resolved = arg.calculateResolvedType().describe();
            if (resolved == null || resolved.isBlank()) return "Object";
            if (isPrimitive(resolved) || resolved.startsWith("java.")) return resolved;
            return "Object";
        } catch (Exception | StackOverflowError e) {
            return "Object";
        }
    }

    private boolean isPrimitive(String t) {
        return switch (t) {
            case "boolean", "byte", "char", "short", "int", "long", "float", "double", "void" -> true;
            default -> false;
        };
    }

    private void record(Map<String, MemberSignature> sink, MemberSignature sig) {
        sink.putIfAbsent(sig.dedupKey(), sig);
    }
}
