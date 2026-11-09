package com.walpro.autobuilder.analysis.structure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.walpro.autobuilder.core.build.AdoptionManifest;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2-EI (D2, P5): the deterministic half of the closure-edge coverage invariant — "what's next?"
 * answered as a QUERY over intended-vs-built structure, not as an LLM call that mints subgoals.
 *
 * <p><b>Intended</b> endpoints are parsed from the root goal's specification text (verb-qualified
 * path mentions — spec data carried by the goal tree). <b>Built</b> endpoints are derived LIVE
 * from the output sources' request-mapping annotations (the symbol model; never stored — storing
 * "am I built" is the stale-fact trap). The difference is the set of spec-named endpoints with no
 * matching request mapping in the artifact — exactly the gap behind false completion #2 (Book-only
 * artifact completed formally while the spec named Members/Loans endpoints) and run 4's missing
 * {@code POST /loans}.
 *
 * <p>Erring toward silence: no opinion when the spec names no verb-qualified endpoints, when the
 * output file set is unavailable, or when ANY request mapping in the output carries a path the
 * parser cannot ground to a string literal (a constant/concatenation — the built set would be
 * incomplete and every comparison against it unsafe). A method-level bare {@code RequestMapping}
 * (verb attribute not parsed) counts as serving ALL verbs on its path so an unusual-but-valid
 * mapping never produces a false "missing". Annotation names are declarative data rows (see
 * T4-BM — discovery, not frozen literals, is the strategic answer).
 */
@Slf4j
@Component
public class EndpointSurfaceQuery {

    private static final Pattern JAVA_FILE = Pattern.compile(".*\\.java$");

    /** Verb-qualified path mention in spec prose -- one grammar, held in {@link EndpointPaths}. */
    private static final Pattern SPEC_ENDPOINT = EndpointPaths.MENTION;

    // Request-mapping vocabulary (ecosystem knowledge as data rows — T4-BM territory). Held in
    // AnnotationVocabularies since 2026-09-08, because RequestBodyContractQuery reads the same
    // handlers and a second private copy is how the two would drift.
    private static final Map<String, String> VERB_MAPPING_ANNOTATIONS = AnnotationVocabularies.VERB_MAPPING;
    private static final String GENERIC_MAPPING_ANNOTATION = AnnotationVocabularies.GENERIC_MAPPING;
    private static final String ANY_VERB = "*";

    /**
     * Spec-named endpoints with no matching request mapping in the output, rendered as injectable
     * work-item descriptions. Empty = no deterministic gap (or nothing to say — spec names no
     * endpoints / output unavailable / built set ungroundable), never "everything is implemented".
     */
    public List<String> missingEndpointWorkItems(String specText, Map<String, String> outputFiles) {
        List<Endpoint> missing = missingEndpoints(specText, outputFiles);
        List<String> items = new ArrayList<>(missing.size());
        for (Endpoint e : missing) {
            items.add(WORK_ITEM_PREFIX + e + WORK_ITEM_SUFFIX);
        }
        return items;
    }

    // The work-item template is owned here so the promotion guard can recognize and invert it.
    private static final String WORK_ITEM_PREFIX = "Provide the ";
    private static final String WORK_ITEM_SUFFIX =
            " endpoint named in the goal specification — no request mapping for it exists in the output.";
    private static final Pattern WORK_ITEM_ENDPOINT = Pattern.compile(
            "^" + Pattern.quote(WORK_ITEM_PREFIX) + "([A-Z]+|\\*) (/\\S*)" + Pattern.quote(WORK_ITEM_SUFFIX) + "$");

    /**
     * Inverts {@link #missingEndpointWorkItems}'s template: the endpoint a work-item description
     * demands, or empty when the description is not one of this query's work items. Lets the
     * promotion path recognize an endpoint work item without any metadata stamp — reopened twins
     * keep their description, so the recognition survives every path that preserves it.
     */
    public Optional<Endpoint> endpointFromWorkItem(String description) {
        if (description == null) return Optional.empty();
        Matcher m = WORK_ITEM_ENDPOINT.matcher(description.strip());
        if (!m.matches()) return Optional.empty();
        String path = EndpointPaths.normalizePath(m.group(2));
        if (path == null) return Optional.empty();
        return Optional.of(new Endpoint(m.group(1), path));
    }

    /**
     * Whether the output currently serves {@code endpoint}. Empty = no opinion (no output to
     * inspect, or a mapping path could not be grounded to a literal — the same abstain rules as
     * the closure-edge query); callers must treat empty as "do not act".
     */
    public Optional<Boolean> isBuilt(Endpoint endpoint, Map<String, String> outputFiles) {
        if (endpoint == null || outputFiles == null || outputFiles.isEmpty()) return Optional.empty();
        Optional<Set<Endpoint>> built = builtInOutput(outputFiles);
        if (built.isEmpty()) return Optional.empty();
        boolean covered = built.get().stream().anyMatch(have ->
                have.path().equals(endpoint.path())
                        && (ANY_VERB.equals(have.verb()) || have.verb().equals(endpoint.verb())));
        return Optional.of(covered);
    }

    /** The raw missing set; see {@link #missingEndpointWorkItems}. */
    public List<Endpoint> missingEndpoints(String specText, Map<String, String> outputFiles) {
        if (specText == null || specText.isBlank()) return List.of();
        if (outputFiles == null || outputFiles.isEmpty()) return List.of(); // cannot see the artifact — no opinion

        Set<Endpoint> declared = declaredInSpec(specText);
        if (declared.isEmpty()) return List.of(); // spec names no endpoints — self-gate, no opinion

        Optional<Set<Endpoint>> built = builtInOutput(outputFiles);
        if (built.isEmpty()) {
            log.info("T2-EI D2: a request mapping carries a non-literal path — built set ungroundable, abstaining");
            return List.of();
        }
        List<Endpoint> missing = new ArrayList<>();
        for (Endpoint want : declared) {
            boolean covered = built.get().stream().anyMatch(have ->
                    have.path().equals(want.path())
                            && (ANY_VERB.equals(have.verb()) || have.verb().equals(want.verb())));
            if (!covered) missing.add(want);
        }
        return missing;
    }

    /**
     * T4-CV: whether the specification demands an artifact that RUNS, derived forward from the
     * spec rather than backward from what the run has already produced.
     *
     * <p>A spec that names endpoints is asking for a served surface, and nothing can serve unless
     * the packaged artifact starts. This is the requirement the loop never had: {@code
     * PreservedArtifactService} defines "entry point required" retrospectively as "some snapshot
     * already has one", which is false exactly in the runs that never wrote one — six recorded
     * runs died at {@code Unable to find main class} with that flag reading {@code false}.
     *
     * <p>Self-gating in the same spirit as the rest of this query: a spec naming no endpoints gets
     * no opinion, because a library or batch target legitimately has no entry point.
     */
    public boolean specRequiresRunnableArtifact(String specText) {
        return specText != null && !specText.isBlank() && !declaredInSpec(specText).isEmpty();
    }

    /**
     * Verb-qualified endpoint mentions in the spec text, normalized. Also consumed by T4-BT.
     *
     * <p>T6-T: none on an ADOPTED project whose tree as adopted served no request mapping -- it is not an HTTP
     * application, and a verb and path in its specification is prose about something else (constraint-samples run
     * 2026-10-20_18-04-44: a quoted past field result, "POST /loans -> 201 ...", refused closure at 27/27 and had the
     * model invent the handler). Every consumer reads the intended surface here, so the gate has one home.
     */
    public Set<Endpoint> declaredInSpec(String specText) {
        if (adoptionManifest != null && adoptionManifest.servedAtAdoption().filter(served -> !served).isPresent()) {
            return Set.of();
        }
        return EndpointPaths.declaredIn(specText);
    }

    /** T6-T: what the adopted tree served; absent outside Spring and for a project the loop wrote itself. */
    @Setter
    @Autowired(required = false)
    private AdoptionManifest adoptionManifest;

    /**
     * Endpoints served by the output sources' request-mapping annotations, normalized.
     * Empty optional = at least one mapping path could not be grounded to a literal, so the
     * built set is incomplete and no comparison against it is safe.
     */
    /**
     * The surface a codebase SERVES, asked of the code alone.
     *
     * <p>Same computation as {@link #builtInOutput}, which exists to be compared against a
     * specification and is therefore package-scoped. This is the question asked in the other
     * direction, by a reader who has no specification yet because the project is not theirs: what
     * does this thing offer? Empty for the same reason as there — a mapping path that is not a
     * literal makes the set a lower bound, and a lower bound presented as a surface would be a
     * false account.
     */
    public Optional<Set<Endpoint>> servedByCode(Map<String, String> sources) {
        return builtInOutput(sources);
    }

    /** One request handler: the route, the type that declares it (nested name, and with its package) and the method. */
    public record Handler(Endpoint endpoint, String type, String qualifiedType, String method) {
    }

    /**
     * T4-EK: the served surface with the method that handles each route -- what a feature of a project the loop did
     * not build can be taken out by. Empty under the same rule as {@link #servedByCode}: a mapping path that is not a
     * literal makes the list a lower bound.
     */
    public Optional<List<Handler>> handlersByCode(Map<String, String> sources) {
        List<Handler> out = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (e.getKey() == null || !JAVA_FILE.matcher(e.getKey()).matches()) continue;
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            try {
                Optional<CompilationUnit> cu = com.walpro.autobuilder.analysis.structure.JavaSources.parser().parse(e.getValue()).getResult();
                if (cu.isPresent() && !collectBuiltEndpoints(cu.get(), (endpoint, type, md) -> out.add(new Handler(endpoint,
                        nestedName(type), qualified(type), md.getNameAsString())))) {
                    return Optional.empty();
                }
            } catch (RuntimeException ex) {
                log.debug("T4-EK: skipping unparseable {}: {}", e.getKey(), ex.getMessage());
            }
        }
        return Optional.of(out);
    }

    private interface HandlerSink {
        void found(Endpoint endpoint, TypeDeclaration<?> type, MethodDeclaration method);
    }

    private static String nestedName(TypeDeclaration<?> t) {
        String name = t.getNameAsString();
        com.github.javaparser.ast.Node n = t.getParentNode().orElse(null);
        while (n instanceof TypeDeclaration<?> outer) {
            name = outer.getNameAsString() + "." + name;
            n = outer.getParentNode().orElse(null);
        }
        return name;
    }

    private static String qualified(TypeDeclaration<?> t) {
        String pkg = t.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString()).orElse("");
        return pkg.isEmpty() ? nestedName(t) : pkg + "." + nestedName(t);
    }

    Optional<Set<Endpoint>> builtInOutput(Map<String, String> outputFiles) {
        Set<Endpoint> built = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : outputFiles.entrySet()) {
            if (e.getKey() == null || !JAVA_FILE.matcher(e.getKey()).matches()) continue;
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            try {
                Optional<CompilationUnit> cu = com.walpro.autobuilder.analysis.structure.JavaSources.parser().parse(e.getValue()).getResult();
                if (cu.isPresent() && !collectBuiltEndpoints(cu.get(), (endpoint, type, md) -> built.add(endpoint))) {
                    return Optional.empty();
                }
            } catch (RuntimeException ex) {
                log.debug("T2-EI D2: skipping unparseable {}: {}", e.getKey(), ex.getMessage());
            }
        }
        return Optional.of(built);
    }

    /** Returns false when any mapping path in the unit could not be grounded to a literal. */
    private boolean collectBuiltEndpoints(CompilationUnit cu, HandlerSink out) {
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            List<String> prefixes = new ArrayList<>();
            for (AnnotationExpr a : type.getAnnotations()) {
                if (GENERIC_MAPPING_ANNOTATION.equals(a.getNameAsString())) {
                    List<String> p = pathsOf(a);
                    if (p == null) return false; // non-literal class prefix
                    prefixes.addAll(p);
                }
            }
            if (prefixes.isEmpty()) prefixes = List.of("");

            for (MethodDeclaration md : type.getMethods()) {
                for (AnnotationExpr a : md.getAnnotations()) {
                    String name = a.getNameAsString();
                    String verb = VERB_MAPPING_ANNOTATIONS.get(name);
                    boolean generic = GENERIC_MAPPING_ANNOTATION.equals(name);
                    if (verb == null && !generic) continue;
                    List<String> methodPaths = pathsOf(a);
                    if (methodPaths == null) return false; // non-literal method path
                    if (methodPaths.isEmpty()) methodPaths = List.of("");
                    for (String prefix : prefixes) {
                        for (String mp : methodPaths) {
                            String full = EndpointPaths.normalizePath(prefix + "/" + mp);
                            if (full != null) {
                                // A generic mapping's verb attribute is not parsed — claim all
                                // verbs so an unusual-but-valid mapping never reads as missing.
                                out.found(new Endpoint(generic ? ANY_VERB : verb, full), type, md);
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * String path values of one mapping annotation: single-member value, or {@code value}/{@code path}
     * pairs. Empty list = the annotation declares no path (inherits the prefix). Null = a path
     * expression exists but is not a string literal (constant/concatenation) — ungroundable.
     */
    private static List<String> pathsOf(AnnotationExpr a) {
        List<Expression> pathExprs = new ArrayList<>();
        if (a instanceof SingleMemberAnnotationExpr s) {
            pathExprs.add(s.getMemberValue());
        } else if (a instanceof NormalAnnotationExpr n) {
            n.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()) || "path".equals(p.getNameAsString()))
                    .forEach(p -> pathExprs.add(p.getValue()));
        }
        List<String> out = new ArrayList<>();
        for (Expression expr : pathExprs) {
            if (!addLiterals(expr, out)) return null;
        }
        return out;
    }

    /** Returns false when the expression (or an array element) is not a string literal. */
    private static boolean addLiterals(Expression expr, List<String> out) {
        if (expr instanceof StringLiteralExpr s) {
            out.add(s.getValue());
            return true;
        }
        if (expr instanceof ArrayInitializerExpr arr) {
            for (Expression v : arr.getValues()) {
                if (!addLiterals(v, out)) return false;
            }
            return true;
        }
        return false;
    }
}
