package com.walpro.autobuilder.analysis.structure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * T1-FV: what an endpoint's own declaration says it ACCEPTS, as distinct from what the artifact
 * stores.
 *
 * <p><b>The defect this answers.</b> {@code RequestBodySynthesizer} derives a request body from
 * the artifact's MODEL class -- every private field of it. The handler that receives the body
 * declares something narrower, and a component the declaration will not bind is refused before
 * the requirement under test is ever reached. Measured on run {@code 2026-08-28_21-47-39}: the
 * probe sent {@code {"bookId":N,"memberId":N,"returned":true}} against a handler declared to take
 * keyed {@code Long} values, drew 400, and the criterion was recorded as contradicted -- on a run
 * that scored 23/23 and lost its only closure attempt to it. Sending exactly what the declaration
 * accepts drew the specified 404. <b>The artifact was correct and the probe manufactured the
 * failure.</b>
 *
 * <p><b>Why this is a query and not a fix inside the synthesizer.</b> Two consumers need the same
 * reading: T1-FV needs to know which components are accepted, and T1-FW needs to know that a
 * stated input component exists and how to ground it. Both ticket bodies say "build the reader
 * once, with two consumers". Deriving it twice is how they would come to disagree.
 *
 * <p><b>The capability was NOT missing, which is why this class is small.</b> Both tickets record
 * that "nothing reads handler parameters". {@code WiringFactsAnalyzer} has read the
 * {@code @RequestBody} parameter and resolved its declared type since T3-BX -- to answer a
 * different question (does that type declare validation constraints), and wired to one consumer
 * that writes prompt facts. What was missing was a route from that reading to the synthesizer, not
 * the reading ([[project-capability-outruns-routing]]). This class is that route, and
 * {@code WiringFactsAnalyzer} now shares it.
 *
 * <p><b>Silence is the safety property, as everywhere else on this path.</b> An unreadable
 * declaration yields empty and the caller keeps whatever it would have done. Reporting a
 * confident narrow answer from a declaration this class did not really understand would swap a
 * wrong body for a wrong-and-trusted one.
 */
@Slf4j
public final class RequestBodyContractQuery {

    /** What a handler's own declaration says it will bind. */
    public sealed interface AcceptedBody permits NamedType, KeyedValues {
    }

    /**
     * The declaration names a type declared in the artifact -- a request object, which may or may
     * not be the entity. Its fields ARE the accepted components.
     */
    public record NamedType(String simpleName) implements AcceptedBody {
    }

    /**
     * The declaration is a two-parameter container -- keys of one type, values of another. Only
     * components whose value fits {@code valueTypeSimpleName} can bind, whatever the model holds.
     */
    public record KeyedValues(String valueTypeSimpleName) implements AcceptedBody {
    }

    private RequestBodyContractQuery() {
    }

    /**
     * The body declaration of the handler serving {@code path}, or empty when there is no single
     * confident answer.
     *
     * <p>Empty covers every case where a narrower body could be wrong: no handler matches, none
     * of the matching handlers takes a body, the mapping path is not a literal this class can
     * ground, or two handlers on the same path declare DIFFERENT bodies and nothing here can say
     * which one the probe will reach.
     */
    public static Optional<AcceptedBody> acceptedBodyFor(String path, Path artifactRoot) {
        String wanted = EndpointSurfaceQuery.normalizePath(path);
        if (wanted == null || artifactRoot == null) return Optional.empty();

        Set<AcceptedBody> found = new LinkedHashSet<>();
        for (Path source : javaSources(artifactRoot)) {
            Optional<CompilationUnit> parsed = parse(source);
            if (parsed.isEmpty()) continue;
            collectFor(parsed.get(), wanted, found);
        }
        if (found.size() != 1) {
            if (found.size() > 1) {
                log.debug("T1-FV: {} has {} differing body declarations — no opinion", wanted, found.size());
            }
            return Optional.empty();
        }
        return Optional.of(found.iterator().next());
    }

    /** Every handler in one unit whose composed mapping matches, contributing its body type. */
    private static void collectFor(CompilationUnit cu, String wanted, Set<AcceptedBody> out) {
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            List<String> prefixes = new ArrayList<>();
            for (AnnotationExpr a : type.getAnnotations()) {
                if (AnnotationVocabularies.GENERIC_MAPPING.equals(a.getNameAsString())) {
                    List<String> p = literalPaths(a);
                    if (p == null) return;   // ungroundable prefix: this unit cannot be trusted
                    prefixes.addAll(p);
                }
            }
            if (prefixes.isEmpty()) prefixes = List.of("");

            for (MethodDeclaration md : type.getMethods()) {
                if (!mapsTo(md, prefixes, wanted)) continue;
                bodyParameter(md).flatMap(RequestBodyContractQuery::classify).ifPresent(out::add);
            }
        }
    }

    /** Whether any of this method's mappings composes to the wanted path. */
    private static boolean mapsTo(MethodDeclaration md, List<String> prefixes, String wanted) {
        for (AnnotationExpr a : md.getAnnotations()) {
            String name = a.getNameAsString();
            if (!AnnotationVocabularies.VERB_MAPPING.containsKey(name)
                    && !AnnotationVocabularies.GENERIC_MAPPING.equals(name)) continue;
            List<String> methodPaths = literalPaths(a);
            if (methodPaths == null) continue;             // ungroundable: contributes nothing
            if (methodPaths.isEmpty()) methodPaths = List.of("");
            for (String prefix : prefixes) {
                for (String mp : methodPaths) {
                    if (wanted.equals(EndpointSurfaceQuery.normalizePath(prefix + "/" + mp))) return true;
                }
            }
        }
        return false;
    }

    /**
     * The parameter carrying the request body. The marker is the vocabulary's, not a guess from
     * position -- a handler takes path variables and query parameters too, and any of them could
     * sit first.
     */
    private static Optional<Parameter> bodyParameter(MethodDeclaration md) {
        return md.getParameters().stream()
                .filter(p -> p.getAnnotations().stream()
                        .anyMatch(a -> AnnotationVocabularies.REQUEST_BODY.equals(a.getNameAsString())))
                .findFirst();
    }

    /**
     * A declared parameter type read as an accepted-body shape.
     *
     * <p>The distinction is structural rather than by type name: a container with exactly TWO type
     * arguments is keys-and-values, and its second argument is what a component's value must fit.
     * One type argument is a sequence -- a different body shape altogether, and this class has no
     * opinion on it. No type arguments and the name of a type is a request object.
     */
    private static Optional<AcceptedBody> classify(Parameter p) {
        Type declared = p.getType();
        if (!declared.isClassOrInterfaceType()) return Optional.empty();
        ClassOrInterfaceType t = declared.asClassOrInterfaceType();

        if (t.getTypeArguments().isEmpty()) return Optional.of(new NamedType(t.getNameAsString()));

        List<Type> args = t.getTypeArguments().get();
        if (args.size() != 2) return Optional.empty();
        Type value = args.get(1);
        if (!value.isClassOrInterfaceType()) return Optional.empty();
        return Optional.of(new KeyedValues(value.asClassOrInterfaceType().getNameAsString()));
    }

    /**
     * String path values of one mapping annotation. Empty list = declares no path (inherits the
     * prefix). Null = a path expression exists but is not a literal, so it cannot be grounded --
     * the same three-way answer {@code EndpointSurfaceQuery} gives, for the same reason.
     */
    private static List<String> literalPaths(AnnotationExpr a) {
        List<Expression> exprs = new ArrayList<>();
        if (a instanceof SingleMemberAnnotationExpr s) {
            exprs.add(s.getMemberValue());
        } else if (a instanceof NormalAnnotationExpr n) {
            n.getPairs().stream()
                    .filter(pair -> "value".equals(pair.getNameAsString())
                            || "path".equals(pair.getNameAsString()))
                    .forEach(pair -> exprs.add(pair.getValue()));
        }
        List<String> out = new ArrayList<>();
        for (Expression e : exprs) {
            if (e instanceof StringLiteralExpr lit) {
                out.add(lit.asString());
            } else if (e.isArrayInitializerExpr()) {
                for (Expression inner : e.asArrayInitializerExpr().getValues()) {
                    if (!(inner instanceof StringLiteralExpr l)) return null;
                    out.add(l.asString());
                }
            } else {
                return null;
            }
        }
        return out;
    }

    private static Optional<CompilationUnit> parse(Path source) {
        try {
            return new JavaParser().parse(Files.readString(source)).getResult();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Main sources when the artifact has them, the whole tree otherwise; test sources skipped. */
    private static List<Path> javaSources(Path artifactRoot) {
        if (!Files.isDirectory(artifactRoot)) return List.of();
        Path main = artifactRoot.resolve("src/main/java");
        Path scanRoot = Files.isDirectory(main) ? main : artifactRoot;
        try (Stream<Path> files = Files.walk(scanRoot)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("src/test/"))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
