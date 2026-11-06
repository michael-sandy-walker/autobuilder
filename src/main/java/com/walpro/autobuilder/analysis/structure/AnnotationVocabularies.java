package com.walpro.autobuilder.analysis.structure;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-BM: ONE home for the annotation-role vocabularies the structural analyzers share. Before
 * this class each consumer carried its own sibling list and they drifted (the injection-point
 * set existed in two sizes; the container-managed set in a third file was missing the advice
 * stereotypes entirely) — one invariant, one home.
 *
 * <p>These are data rows, not code: ecosystem knowledge kept declarative, matched by simple name
 * so every package generation of the same ecosystem hits (javax.* and jakarta.*), and always
 * consumed behind a self-gate (no marker in the target → the consumer has no opinion, per
 * [[feedback-ecosystem-gated-heuristics]]). The strategic direction remains discovery over rows
 * ({@code T4-BM}); {@link #projectDeclaredConstraints(List)} is the first discovered slice — the
 * vocabulary a project DECLARES itself, recognised by the ecosystem's own meta-annotation
 * rather than any list of ours.
 */
public final class AnnotationVocabularies {

    /** Marks a class the container instantiates and injects (one managed instance). */
    public static final Set<String> CONTAINER_MANAGED = Set.of(
            "Service", "Component", "Repository", "Controller", "RestController",
            "ControllerAdvice", "RestControllerAdvice", "Configuration");

    /**
     * T4-EH: marks the type from which the container discovers its managed classes. Without a
     * package attribute the scan covers the marked type's own package and everything below it.
     */
    public static final Set<String> SCAN_ROOT = Set.of("SpringBootApplication", "ComponentScan");

    /** The attributes of a {@link #SCAN_ROOT} annotation that name the scanned packages as text. */
    public static final Set<String> SCAN_PACKAGE_ATTRIBUTES = Set.of("scanBasePackages", "basePackages", "value");

    /** The attributes of a {@link #SCAN_ROOT} annotation that name the scanned packages by a class in each. */
    public static final Set<String> SCAN_PACKAGE_CLASS_ATTRIBUTES = Set.of(
            "scanBasePackageClasses", "basePackageClasses");

    /** Marks an explicit dependency-injection point (field, setter, or constructor). */
    public static final Set<String> INJECTION_POINT = Set.of("Autowired", "Inject", "Resource");

    /**
     * Marks a field the framework assigns outside visible code — the injection points plus
     * configuration values and test doubles. Consumers asking "is this field ever assigned?"
     * need this superset; consumers wiring the DI graph need only {@link #INJECTION_POINT}.
     */
    public static final Set<String> FRAMEWORK_ASSIGNED_FIELD = Set.of(
            "Autowired", "Inject", "Resource", "Value", "MockBean", "Mock");

    /** Marks a method that handles a thrown exception type. */
    public static final String EXCEPTION_HANDLER = "ExceptionHandler";

    /** Declares the response status of a handler, or of an exception type itself. */
    public static final String STATUS_DECLARATION = "ResponseStatus";

    /** Maps a thrown exception (or handler) to a response status. */
    public static final Set<String> STATUS_MAPPING = Set.of(
            "ControllerAdvice", "RestControllerAdvice", EXCEPTION_HANDLER, STATUS_DECLARATION);

    /** Marks a type whose instances the persistence layer stores. */
    public static final Set<String> PERSISTENT_TYPE = Set.of("Entity");

    /**
     * Changes how a method runs without changing its code: transaction demarcation, scheduling.
     * A diff that adds or removes one changes behaviour even when no statement changed.
     */
    public static final Set<String> EXECUTION_MODIFIER = Set.of("Transactional", "Scheduled");

    /** Binds a request body to a parameter. */
    public static final String REQUEST_BODY = "RequestBody";

    /**
     * Request-mapping vocabulary, moved here from {@code EndpointSurfaceQuery} on 2026-09-08 when
     * a second reader needed it (T1-FV). Annotation names are declarative data rows and this is
     * their home -- two private copies is how the surface query and the body-contract query would
     * come to disagree about what a handler is.
     */
    public static final Map<String, String> VERB_MAPPING = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "PatchMapping", "PATCH",
            "DeleteMapping", "DELETE");

    /** A mapping that does not name its verb in the annotation itself. */
    public static final String GENERIC_MAPPING = "RequestMapping";

    /** Requests bean validation of an annotated binding. */
    public static final Set<String> VALIDATION_TRIGGER = Set.of("Valid", "Validated");

    /** Declares a per-field validation constraint. */
    public static final Set<String> FIELD_CONSTRAINT = Set.of(
            "NotNull", "NotBlank", "NotEmpty", "Size", "Pattern", "Min", "Max",
            "DecimalMin", "DecimalMax", "Email", "Digits",
            "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
            "Past", "PastOrPresent", "Future", "FutureOrPresent", "AssertTrue", "AssertFalse");

    /**
     * The bean-validation meta-annotation that marks an annotation TYPE as a constraint. This is
     * the ecosystem's own extension marker: a project declaring {@code @interface ValidIsbn}
     * meta-annotated {@code @Constraint(validatedBy = …)} has extended the constraint vocabulary,
     * and the analyzers must see that extension or they report a false "no constraints" fact.
     */
    private static final String CONSTRAINT_META = "Constraint";

    private AnnotationVocabularies() {}

    /**
     * Constraint annotations the project declares itself, discovered from the supplied sources
     * by the {@code @Constraint} meta-annotation. Union these with {@link #FIELD_CONSTRAINT}
     * when asking "does this type declare a validation surface?".
     */
    public static Set<String> projectDeclaredConstraints(List<CompilationUnit> units) {
        Set<String> names = new LinkedHashSet<>();
        for (CompilationUnit u : units) {
            for (AnnotationDeclaration decl : u.findAll(AnnotationDeclaration.class)) {
                boolean isConstraint = decl.getAnnotations().stream()
                        .anyMatch(a -> CONSTRAINT_META.equals(a.getNameAsString()));
                if (isConstraint) names.add(decl.getNameAsString());
            }
        }
        return names;
    }
}
