package com.walpro.autobuilder.analysis.structure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-BW: execution-free proof that the artifact's dependency-injection wiring can resolve —
 * every injection point whose dependency type is declared IN the project must have a bean
 * source (a container-marker-annotated class, a factory-method return type, or a bean source
 * that implements/extends the injected type).
 *
 * <p>From false completion #3 (run 13, 2026-07-04): the last merged mutation full-rewrote a
 * service class dropping its container marker while a controller still injected it. The
 * artifact compiled, packaged, and passed 170/170 text-level criteria — and died at context
 * refresh before serving a single request. Compilation does not need the marker; only the
 * container's own resolution rules do, and those rules are statically checkable.
 *
 * <p><b>Self-gated the ecosystem-marker way ([[feedback-ecosystem-gated-heuristics]]):</b> a
 * project with no container markers yields no bean sources and the query returns nothing —
 * marker-free projects get no opinion. The marker names are data rows (T4-BM discovery
 * territory), simple-name matched so package variants all hit.
 *
 * <p><b>Conservative by construction</b> — this feeds a blocking gate, so every unsure case is
 * silent: generic/parameterised injection targets (collections of beans, providers), types not
 * declared in the file set (the container may manufacture them from configuration the source
 * doesn't show), unparseable files, enums and annotations. Constructor parameters count as
 * injection points only under the container's own instantiation rule: the class carries a
 * container marker AND (it has exactly one constructor, or the constructor carries an
 * injection marker).
 */
@Slf4j
public final class InjectionWiringQuery {

    /** Annotations that mark a class as container-managed (data rows — T4-BM territory). */
    private static final Set<String> CONTAINER_MARKERS = Set.of(
            "Service", "Component", "Repository", "Controller", "RestController",
            "ControllerAdvice", "RestControllerAdvice", "Configuration");

    /** Annotations that mark an explicit injection point (data rows — T4-BM territory). */
    private static final Set<String> INJECTION_MARKERS = Set.of("Autowired", "Inject", "Resource");

    /** Annotation marking a factory method whose return type becomes a bean source. */
    private static final String FACTORY_METHOD_MARKER = "Bean";

    private static final int MAX_ANCESTOR_HOPS = 10;

    private InjectionWiringQuery() {
    }

    /** An injection point whose project-declared dependency type has no bean source. */
    public record UnresolvedInjection(String injectedType, String enclosingType, String kind) {
        public String describe() {
            return "'" + injectedType + "' is " + kind + "-injected at " + enclosingType
                    + " but no container-managed source provides it";
        }
    }

    /**
     * Returns every unresolved injection in the given file set, or empty when the wiring
     * resolves — or when no answer is possible (no container markers, nothing parses).
     * Callers must treat empty as "no structural concern", never as proof of correctness.
     */
    public static List<UnresolvedInjection> unresolvedInjections(Map<String, String> files) {
        if (files == null || files.isEmpty()) return List.of();

        List<CompilationUnit> units = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getKey() == null || !e.getKey().endsWith(".java")) continue;
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            try {
                new JavaParser().parse(e.getValue()).getResult().ifPresent(units::add);
            } catch (RuntimeException ex) {
                log.debug("T4-BW: skipping unparseable {}: {}", e.getKey(), ex.getMessage());
            }
        }
        if (units.isEmpty()) return List.of();

        // Project-declared classes/interfaces by simple name (enums/annotations excluded — they
        // are not injectable bean types and flagging them would only risk false blocks).
        Map<String, ClassOrInterfaceDeclaration> declared = new LinkedHashMap<>();
        for (CompilationUnit cu : units) {
            for (ClassOrInterfaceDeclaration t : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                declared.putIfAbsent(t.getNameAsString(), t);
            }
        }

        Set<String> provided = providedTypes(units, declared);
        if (provided.isEmpty()) return List.of(); // marker-free project — no opinion (self-gate)

        List<UnresolvedInjection> violations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!(type instanceof ClassOrInterfaceDeclaration cls) || cls.isInterface()) continue;
                if (!hasAnyAnnotation(cls, CONTAINER_MARKERS)) continue; // only container-instantiated classes are injected into
                collectInjectionPoints(cls, declared, provided, violations, seen);
            }
        }
        return violations;
    }

    /**
     * Bean sources plus everything they satisfy: marker-annotated classes, factory-method
     * return types, and the project-declared supertypes of every marker-annotated class
     * (an injected interface is satisfied by a bean source implementing it).
     */
    private static Set<String> providedTypes(List<CompilationUnit> units,
                                             Map<String, ClassOrInterfaceDeclaration> declared) {
        Set<String> provided = new LinkedHashSet<>();
        for (CompilationUnit cu : units) {
            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (hasAnyAnnotation(cls, CONTAINER_MARKERS)) {
                    provided.add(cls.getNameAsString());
                    addProjectAncestors(cls, declared, provided);
                }
            }
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                if (md.getAnnotations().stream()
                        .anyMatch(a -> FACTORY_METHOD_MARKER.equals(a.getNameAsString()))) {
                    simpleTypeName(md.getType()).ifPresent(returned -> {
                        provided.add(returned);
                        ClassOrInterfaceDeclaration returnedDecl = declared.get(returned);
                        if (returnedDecl != null) addProjectAncestors(returnedDecl, declared, provided);
                    });
                }
            }
        }
        return provided;
    }

    /** Walks extends/implements edges resolvable within the file set, bounded. */
    private static void addProjectAncestors(ClassOrInterfaceDeclaration cls,
                                            Map<String, ClassOrInterfaceDeclaration> declared,
                                            Set<String> out) {
        List<ClassOrInterfaceDeclaration> frontier = List.of(cls);
        for (int hop = 0; hop < MAX_ANCESTOR_HOPS && !frontier.isEmpty(); hop++) {
            List<ClassOrInterfaceDeclaration> next = new ArrayList<>();
            for (ClassOrInterfaceDeclaration current : frontier) {
                List<ClassOrInterfaceType> supertypes = new ArrayList<>();
                supertypes.addAll(current.getExtendedTypes());
                supertypes.addAll(current.getImplementedTypes());
                for (ClassOrInterfaceType st : supertypes) {
                    String name = st.getNameAsString();
                    if (out.add(name)) {
                        ClassOrInterfaceDeclaration decl = declared.get(name);
                        if (decl != null) next.add(decl);
                    }
                }
            }
            frontier = next;
        }
    }

    private static void collectInjectionPoints(ClassOrInterfaceDeclaration cls,
                                               Map<String, ClassOrInterfaceDeclaration> declared,
                                               Set<String> provided,
                                               List<UnresolvedInjection> violations,
                                               Set<String> seen) {
        String enclosing = cls.getNameAsString();

        for (FieldDeclaration fd : cls.getFields()) {
            if (!hasAnyAnnotation(fd, INJECTION_MARKERS)) continue;
            checkInjectedType(fd.getElementType(), enclosing, "field", declared, provided, violations, seen);
        }

        for (MethodDeclaration md : cls.getMethods()) {
            if (!hasAnyAnnotation(md, INJECTION_MARKERS)) continue;
            for (Parameter p : md.getParameters()) {
                checkInjectedType(p.getType(), enclosing, "setter", declared, provided, violations, seen);
            }
        }

        List<ConstructorDeclaration> ctors = cls.getConstructors();
        for (ConstructorDeclaration ctor : ctors) {
            boolean marked = hasAnyAnnotation(ctor, INJECTION_MARKERS);
            if (!marked && ctors.size() != 1) continue; // container's own instantiation rule
            for (Parameter p : ctor.getParameters()) {
                checkInjectedType(p.getType(), enclosing, "constructor", declared, provided, violations, seen);
            }
        }
    }

    private static void checkInjectedType(Type type, String enclosing, String kind,
                                          Map<String, ClassOrInterfaceDeclaration> declared,
                                          Set<String> provided,
                                          List<UnresolvedInjection> violations,
                                          Set<String> seen) {
        if (!(type instanceof ClassOrInterfaceType cit)) return;
        if (cit.getTypeArguments().isPresent()) return; // collections/providers of beans: stay silent
        String name = cit.getNameAsString();
        if (!declared.containsKey(name)) return; // not project-declared: the container may provide it
        if (provided.contains(name)) return;
        if (seen.add(name + "@" + enclosing)) {
            violations.add(new UnresolvedInjection(name, enclosing, kind));
        }
    }

    private static boolean hasAnyAnnotation(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node,
                                            Set<String> names) {
        // Fully-qualified annotation use (`@org.x.Service`) parses to the qualified name —
        // match on the last segment so both spellings hit.
        return node.getAnnotations().stream().anyMatch(a -> {
            String name = a.getNameAsString();
            int dot = name.lastIndexOf('.');
            return names.contains(dot >= 0 ? name.substring(dot + 1) : name);
        });
    }

    private static java.util.Optional<String> simpleTypeName(Type type) {
        if (type instanceof ClassOrInterfaceType cit && cit.getTypeArguments().isEmpty()) {
            return java.util.Optional.of(cit.getNameAsString());
        }
        return java.util.Optional.empty();
    }
}
