package com.walpro.autobuilder.analysis.structure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T4-BS: extracts structural "wiring" facts from the output Java sources to sharpen the completion
 * verifier's input. T4-BP-3 runs the same LOCAL model that false-positived 18/18 on the 2026-06-16
 * hollow artifact; local-first rules out a stronger model, so the lever is the verifier's INPUT.
 * Instead of a flat 25-files × 150-lines blob, this surfaces the concrete wiring smells that
 * distinguish a hollow artifact from a real one — pattern recognition over wiring beats
 * presence-matching over a blob ([[feedback-activity-not-progress]]: source "what's missing" from
 * the compiler grammar + symbol model, not string-scraping).
 *
 * <p>Facts surfaced (each a high-confidence structural observation — the analyzer errs toward
 * silence so it raises, not lowers, precision):
 * <ul>
 *   <li><b>stub bodies</b> — a method that only {@code return null} / has an empty body / only throws;</li>
 *   <li><b>never-assigned fields</b> — a returned-object's fields that no code ever assigns (the
 *       empty {@code PriceBreakdown});</li>
 *   <li><b>missing persistence</b> — entities are built but no {@code save*}/{@code persist*} call
 *       exists anywhere (the order never persisted);</li>
 *   <li><b>unmapped throws</b> — exceptions are thrown but no {@code @ControllerAdvice} /
 *       {@code @ExceptionHandler} / {@code @ResponseStatus} maps them to a status; when mappings
 *       DO exist, the comparison sharpens to type level: thrown types no mapping covers, and
 *       mapped types nothing ever instantiates (field run 3, 2026-07-03: raw
 *       {@code RuntimeException} thrown past 8 mapped-but-never-thrown exception types — every
 *       rule violation a 500 while the file-level check stayed silent);</li>
 *   <li><b>unread parameters</b> — a method parameter the body never references;</li>
 *   <li><b>missing validation surface</b> — a request-body binding whose declared type carries no
 *       field constraint annotations and/or whose parameter has no validation trigger, so a
 *       "rejects invalid input / returns 400" requirement cannot be met (the run-5 measured
 *       verifier false-pass class: validation criteria graded ✓ with no validation code).</li>
 * </ul>
 *
 * <p>Pure AST over the supplied file set — no classpath, no symbol-solver configuration, no network;
 * unparseable files are skipped. Bounded output ({@value #MAX_OBSERVATIONS}) so it never bloats the prompt.
 */
@Slf4j
@Component
public class WiringFactsAnalyzer {

    static final int MAX_OBSERVATIONS = 25;

    private static final Pattern MUTATOR_CALL = Pattern.compile("^(save|persist|store|insert)\\w*$");
    private static final Set<String> STATUS_MAPPING_ANNOTATIONS =
            Set.of("ControllerAdvice", "RestControllerAdvice", "ExceptionHandler", "ResponseStatus");
    private static final Pattern JAVA_FILE = Pattern.compile(".*\\.java$");

    // Validation-surface data tables (ecosystem knowledge as data, not code). The check self-gates
    // on the request-body marker actually appearing in the target code — no opinion otherwise.
    // Simple-name matching keeps them package-agnostic (javax.* and jakarta.* both hit), but the
    // vocabulary itself is still a frozen worldview — T4-BM (registry → discovery migration) is
    // the strategic answer; these rows are its discovery data, kept deliberately declarative.
    private static final Set<String> INJECTION_MARKER_ANNOTATIONS =
            Set.of("Autowired", "Inject", "Resource", "Value", "MockBean", "Mock");
    private static final String REQUEST_BODY_ANNOTATION = "RequestBody";
    private static final Set<String> VALIDATION_TRIGGER_ANNOTATIONS = Set.of("Valid", "Validated");
    private static final Set<String> FIELD_CONSTRAINT_ANNOTATIONS = Set.of(
            "NotNull", "NotBlank", "NotEmpty", "Size", "Pattern", "Min", "Max",
            "DecimalMin", "DecimalMax", "Email", "Digits",
            "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
            "Past", "PastOrPresent", "Future", "FutureOrPresent", "AssertTrue", "AssertFalse");

    /**
     * Returns the wiring observations for the given output files, capped at {@value #MAX_OBSERVATIONS}.
     * Empty when nothing structural stands out (or nothing parses) — callers treat empty as
     * "no structural concern", never as evidence of correctness.
     */
    public List<String> analyze(Map<String, String> outputFiles) {
        if (outputFiles == null || outputFiles.isEmpty()) return List.of();

        List<CompilationUnit> units = new ArrayList<>();
        for (Map.Entry<String, String> e : outputFiles.entrySet()) {
            if (e.getKey() == null || !JAVA_FILE.matcher(e.getKey()).matches()) continue;
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            try {
                new JavaParser().parse(e.getValue()).getResult().ifPresent(units::add);
            } catch (RuntimeException ex) {
                log.debug("T4-BS: skipping unparseable {}: {}", e.getKey(), ex.getMessage());
            }
        }
        if (units.isEmpty()) return List.of();

        List<String> observations = new ArrayList<>();
        // Project-wide facts computed once.
        Set<String> calledMethodNames = collectCalledMethodNames(units);
        boolean anyThrow = units.stream().anyMatch(u ->
                !u.findAll(ThrowStmt.class).isEmpty()
                        || u.findAll(MethodCallExpr.class).stream().anyMatch(c -> "orElseThrow".equals(c.getNameAsString())));
        boolean anyStatusMapping = units.stream().anyMatch(u ->
                u.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                        .anyMatch(a -> STATUS_MAPPING_ANNOTATIONS.contains(a.getNameAsString())));
        boolean anyPersistenceCall = calledMethodNames.stream().anyMatch(n -> MUTATOR_CALL.matcher(n).matches());
        boolean anyEntityType = units.stream().anyMatch(u ->
                u.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                        .anyMatch(a -> "Entity".equals(a.getNameAsString())));

        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> type : cu.getTypes()) {
                collectTypeObservations(type, observations);
                if (observations.size() >= MAX_OBSERVATIONS) return observations.subList(0, MAX_OBSERVATIONS);
            }
        }

        collectValidationSurfaceObservations(units, observations);
        if (observations.size() >= MAX_OBSERVATIONS) return observations.subList(0, MAX_OBSERVATIONS);

        if (anyThrow && !anyStatusMapping) {
            observations.add("Exceptions are thrown but no HTTP status mapping was found "
                    + "(no @ControllerAdvice / @ExceptionHandler / @ResponseStatus) — failures will surface as 500s.");
        } else if (anyThrow) {
            // Type-level half of the same invariant (field run 3, 2026-07-03): the artifact mapped
            // 8 dedicated exception types in a @ControllerAdvice and threw raw RuntimeException
            // everywhere — every business-rule violation surfaced as a 500 while the file-level
            // check above stayed silent because mappings existed. Compare THROWN types against
            // MAPPED types (project-declared supertype chains resolved, so a thrown subclass
            // covered by a mapped ancestor never false-alarms).
            collectThrowMappingObservations(units, observations);
        }
        if (anyEntityType && !anyPersistenceCall) {
            observations.add("Persistent entities are declared but no save/persist call exists anywhere — "
                    + "entities may be built in memory but never stored.");
        }

        return observations.size() > MAX_OBSERVATIONS ? observations.subList(0, MAX_OBSERVATIONS) : observations;
    }

    /** Formats the observations as a prompt block, or empty string when there are none. */
    public String asPromptBlock(Map<String, String> outputFiles) {
        List<String> obs = analyze(outputFiles);
        if (obs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("STRUCTURAL WIRING NOTES (concrete facts from the code — weigh these against each requirement):\n");
        for (String o : obs) sb.append("- ").append(o).append("\n");
        return sb.toString();
    }

    private void collectTypeObservations(TypeDeclaration<?> type, List<String> out) {
        String typeName = type.getNameAsString();
        // Records auto-assign their components; skip field/param analysis that would misfire on them.
        boolean isRecord = type.isRecordDeclaration();

        for (MethodDeclaration md : type.findAll(MethodDeclaration.class)) {
            if (out.size() >= MAX_OBSERVATIONS) return;
            String label = typeName + "." + md.getNameAsString() + "()";
            md.getBody().ifPresent(body -> {
                if (isStubBody(md, body)) {
                    out.add(label + " is a stub (returns null / empty body / only throws) — no real implementation.");
                } else {
                    for (Parameter p : md.getParameters()) {
                        if (out.size() >= MAX_OBSERVATIONS) return;
                        if (!parameterIsRead(body, p.getNameAsString())) {
                            out.add(label + " never reads its parameter '" + p.getNameAsString()
                                    + "' — the input is ignored.");
                        }
                    }
                }
            });
        }

        if (!isRecord) {
            Set<String> assigned = assignedFieldNames(type);
            for (com.github.javaparser.ast.body.FieldDeclaration fd : type.getFields()) {
                if (out.size() >= MAX_OBSERVATIONS) return;
                if (fd.isStatic()) continue;
                // Container-injected fields are assigned by the DI container, not by code — the
                // field-injection style produced 8 false "never assigned" facts on the run-3
                // artifact (2026-07-03). Annotation names are data rows (T4-BM territory).
                if (fd.getAnnotations().stream()
                        .anyMatch(a -> INJECTION_MARKER_ANNOTATIONS.contains(a.getNameAsString()))) {
                    continue;
                }
                for (VariableDeclarator v : fd.getVariables()) {
                    String name = v.getNameAsString();
                    boolean hasInitializer = v.getInitializer().isPresent();
                    if (!hasInitializer && !assigned.contains(name)) {
                        out.add(typeName + "." + name + " is never assigned — it stays at its default value "
                                + "(returned objects carry uncomputed fields).");
                    }
                }
            }
        }
    }

    /** A method body is a stub when it is empty (non-void), only {@code return null}, or only throws. */
    private static boolean isStubBody(MethodDeclaration md, BlockStmt body) {
        List<Statement> stmts = body.getStatements();
        boolean voidReturn = "void".equals(md.getType().asString());
        if (stmts.isEmpty()) return !voidReturn; // empty non-void body produces nothing meaningful
        if (stmts.size() == 1) {
            Statement only = stmts.get(0);
            if (only instanceof ReturnStmt r) {
                return r.getExpression().map(e -> e instanceof NullLiteralExpr).orElse(false);
            }
            return only instanceof ThrowStmt;
        }
        return false;
    }

    /**
     * Validation-surface facts: for every request-body binding whose declared type is in the file
     * set, report when the surface is missing on either side — no field constraint annotations on
     * the type, no validation trigger on the parameter (or its declaring type), or both. Silent
     * when both sides are present, when the body type is not declared in the file set, or when the
     * project has no request-body bindings at all (the self-gate).
     */
    /**
     * Type-level throw-vs-mapping comparison, applied only when SOME status mapping exists (the
     * mapping-free case is the file-level fact above). Reports, as facts:
     * <ul>
     *   <li>a thrown exception type not covered by any mapped type (its project-declared
     *       supertype chain resolved) — those failures bypass the mappings and surface as 500s;</li>
     *   <li>a handler-mapped exception type that is never instantiated anywhere — the mapping
     *       exists but nothing can ever reach it.</li>
     * </ul>
     * Conservative: bare re-throws ({@code throw e;}) carry no type name and are skipped; mapped
     * types count as reachable if instantiated ANYWHERE (covers {@code orElseThrow(() -> new E())}
     * and factory patterns), not just inside {@code throw} statements.
     */
    private void collectThrowMappingObservations(List<CompilationUnit> units, List<String> out) {
        Set<String> mapped = new LinkedHashSet<>();
        // Run 18 (2026-07-06): the gap-only facts left the checker guessing on behaviour that
        // EXISTS — it marked a working 422 mapping unmet, the banked ✗ walled the subgoal, and
        // the root gave up on finished work. Record the status expression each mapping declares
        // (quoted from the code, no translation) so a positive fact can be emitted below.
        Map<String, String> statusTextByMapped = new java.util.LinkedHashMap<>();
        for (CompilationUnit cu : units) {
            for (com.github.javaparser.ast.expr.AnnotationExpr a
                    : cu.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class)) {
                if (!"ExceptionHandler".equals(a.getNameAsString())) continue;
                List<String> targets = new ArrayList<>();
                // @ExceptionHandler(X.class) / ({X.class, Y.class}) — collect the class literals.
                a.findAll(com.github.javaparser.ast.expr.ClassExpr.class)
                        .forEach(cls -> targets.add(simpleName(cls.getType().asString())));
                // Marker form: @ExceptionHandler with the type on the handler method's parameter.
                a.getParentNode().ifPresent(parent -> {
                    if (parent instanceof MethodDeclaration handler) {
                        if (targets.isEmpty()) {
                            handler.getParameters().forEach(p -> targets.add(simpleName(p.getTypeAsString())));
                        }
                        String status = handlerStatusText(handler);
                        if (status != null) {
                            targets.forEach(t -> statusTextByMapped.putIfAbsent(t, status));
                        }
                    }
                });
                mapped.addAll(targets);
            }
            // A type annotated @ResponseStatus maps itself.
            for (TypeDeclaration<?> type : cu.getTypes()) {
                type.getAnnotations().stream()
                        .filter(a -> "ResponseStatus".equals(a.getNameAsString()))
                        .findFirst()
                        .ifPresent(a -> {
                            mapped.add(type.getNameAsString());
                            a.findFirst(com.github.javaparser.ast.expr.FieldAccessExpr.class)
                                    .ifPresent(f -> statusTextByMapped.putIfAbsent(type.getNameAsString(), f.toString()));
                        });
            }
        }
        if (mapped.isEmpty()) return; // advice class without resolvable targets — no opinion

        // Project-declared supertype chains by simple name (extends edges visible in the file set).
        Map<String, String> extendsEdge = new java.util.HashMap<>();
        for (CompilationUnit cu : units) {
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c
                        && !c.getExtendedTypes().isEmpty()) {
                    extendsEdge.put(c.getNameAsString(), simpleName(c.getExtendedTypes(0).getNameAsString()));
                }
            }
        }

        Set<String> thrownUnmapped = new LinkedHashSet<>();
        Set<String> thrownAndMapped = new LinkedHashSet<>();
        Set<String> instantiated = new LinkedHashSet<>();
        for (CompilationUnit cu : units) {
            cu.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)
                    .forEach(oce -> instantiated.add(simpleName(oce.getType().getNameAsString())));
            for (ThrowStmt ts : cu.findAll(ThrowStmt.class)) {
                if (!(ts.getExpression() instanceof com.github.javaparser.ast.expr.ObjectCreationExpr oce)) continue;
                String thrown = simpleName(oce.getType().getNameAsString());
                String covering = coveringMapping(thrown, mapped, extendsEdge);
                if (covering == null) {
                    thrownUnmapped.add(thrown);
                } else {
                    String status = statusTextByMapped.get(covering);
                    thrownAndMapped.add(status != null ? thrown + " → " + status : thrown);
                }
            }
        }
        List<String> mappedNeverThrown = mapped.stream().filter(m -> !instantiated.contains(m)).toList();

        if (!thrownUnmapped.isEmpty() && out.size() < MAX_OBSERVATIONS) {
            out.add("Status mappings exist, but thrown exception type(s) " + thrownUnmapped
                    + " are not covered by any mapped type — those failures bypass the handlers and surface as 500s.");
        }
        if (!mappedNeverThrown.isEmpty() && out.size() < MAX_OBSERVATIONS) {
            out.add("Exception type(s) " + mappedNeverThrown
                    + " are mapped to statuses but never instantiated anywhere — nothing can reach those mappings.");
        }
        // The positive half (run 18): a requirement about a failure status that IS wired should
        // be confirmable from this line alone, instead of guessed from raw handler code.
        if (!thrownAndMapped.isEmpty() && out.size() < MAX_OBSERVATIONS) {
            out.add("Thrown exception type(s) WITH a status mapping in place: " + thrownAndMapped
                    + " — these failure paths return the mapped status.");
        }
    }

    /**
     * The mapped type covering the thrown type — itself or any project-declared ancestor —
     * or {@code null} when no mapping covers it.
     */
    private static String coveringMapping(String thrown, Set<String> mapped, Map<String, String> extendsEdge) {
        String current = thrown;
        for (int hops = 0; current != null && hops < 10; hops++) {
            if (mapped.contains(current)) return current;
            current = extendsEdge.get(current);
        }
        return null;
    }

    /**
     * The status expression a handler method declares, quoted verbatim from the code: the
     * {@code @ResponseStatus} member when present, else the first status constant referenced in
     * the body. {@code null} when neither exists (the fact is then emitted without a status).
     */
    private static String handlerStatusText(MethodDeclaration handler) {
        for (com.github.javaparser.ast.expr.AnnotationExpr a : handler.getAnnotations()) {
            if ("ResponseStatus".equals(a.getNameAsString())) {
                return a.findFirst(com.github.javaparser.ast.expr.FieldAccessExpr.class)
                        .map(Object::toString)
                        .orElse(a.toString());
            }
        }
        return handler.getBody()
                .flatMap(b -> b.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                        .filter(f -> "HttpStatus".equals(simpleName(f.getScope().toString())))
                        .findFirst())
                .map(Object::toString)
                .orElse(null);
    }

    private static String simpleName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot >= 0 ? typeName.substring(lastDot + 1) : typeName;
    }

    private void collectValidationSurfaceObservations(List<CompilationUnit> units, List<String> out) {
        Map<String, TypeDeclaration<?>> declaredTypes = new java.util.LinkedHashMap<>();
        for (CompilationUnit u : units) {
            for (TypeDeclaration<?> t : u.findAll(TypeDeclaration.class)) {
                declaredTypes.putIfAbsent(t.getNameAsString(), t);
            }
        }

        for (CompilationUnit u : units) {
            for (MethodDeclaration md : u.findAll(MethodDeclaration.class)) {
                for (Parameter p : md.getParameters()) {
                    if (out.size() >= MAX_OBSERVATIONS) return;
                    if (p.getAnnotations().stream()
                            .noneMatch(a -> REQUEST_BODY_ANNOTATION.equals(a.getNameAsString()))) continue;
                    if (!p.getType().isClassOrInterfaceType()) continue;
                    var paramType = p.getType().asClassOrInterfaceType();
                    if (paramType.getTypeArguments().isPresent()) continue; // container types: stay silent
                    TypeDeclaration<?> bodyType = declaredTypes.get(paramType.getNameAsString());
                    if (bodyType == null) continue; // not declared in the file set: stay silent

                    boolean hasConstraints = bodyType.findAll(
                                    com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                            .anyMatch(a -> FIELD_CONSTRAINT_ANNOTATIONS.contains(a.getNameAsString()));
                    boolean hasTrigger = hasValidationTrigger(p, md);

                    String binding = md.findAncestor(TypeDeclaration.class)
                            .map(t -> t.getNameAsString() + "." + md.getNameAsString() + "()")
                            .orElse(md.getNameAsString() + "()");
                    String typeName = bodyType.getNameAsString();
                    if (!hasConstraints && !hasTrigger) {
                        out.add("Request body " + typeName + " bound in " + binding
                                + " has NO declarative validation surface: no field constraint annotations on "
                                + typeName + " and no validation trigger on the parameter. If a requirement"
                                + " expects invalid input to be rejected (e.g. a 400), confirm the method"
                                + " bodies validate manually — no annotation-driven validation exists.");
                    } else if (!hasTrigger) {
                        out.add("Request body " + typeName + " declares field constraints, but " + binding
                                + " binds it without a validation trigger — the declared constraints are not"
                                + " enforced on this binding unless the method bodies validate manually.");
                    } else if (!hasConstraints) {
                        out.add(binding + " requests validation of " + typeName + " but " + typeName
                                + " declares no field constraint annotations — the declarative constraint set"
                                + " is empty; only manual checks in the method bodies (if any) validate it.");
                    }
                }
            }
        }
    }

    /** A trigger counts on the parameter itself or as {@code @Validated} on the declaring type. */
    private static boolean hasValidationTrigger(Parameter p, MethodDeclaration md) {
        if (p.getAnnotations().stream()
                .anyMatch(a -> VALIDATION_TRIGGER_ANNOTATIONS.contains(a.getNameAsString()))) return true;
        return md.findAncestor(TypeDeclaration.class)
                .map(t -> ((TypeDeclaration<?>) t).getAnnotations().stream()
                        .anyMatch(a -> VALIDATION_TRIGGER_ANNOTATIONS.contains(a.getNameAsString())))
                .orElse(false);
    }

    private static boolean parameterIsRead(BlockStmt body, String paramName) {
        return body.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(paramName));
    }

    /** Field names that some code assigns (covers {@code this.f = ...} and {@code f = ...}). */
    private static Set<String> assignedFieldNames(TypeDeclaration<?> type) {
        Set<String> assigned = new LinkedHashSet<>();
        for (AssignExpr a : type.findAll(AssignExpr.class)) {
            var target = a.getTarget();
            if (target.isNameExpr()) {
                assigned.add(target.asNameExpr().getNameAsString());
            } else if (target.isFieldAccessExpr()) {
                assigned.add(target.asFieldAccessExpr().getNameAsString());
            }
        }
        return assigned;
    }

    private static Set<String> collectCalledMethodNames(List<CompilationUnit> units) {
        Set<String> names = new LinkedHashSet<>();
        for (CompilationUnit u : units) {
            for (MethodCallExpr c : u.findAll(MethodCallExpr.class)) {
                names.add(c.getNameAsString());
            }
        }
        return names;
    }
}
