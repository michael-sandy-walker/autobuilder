package com.walpro.autobuilder.core.grounding;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T2-DC: verifies that file paths and Java identifiers cited by analysis/planner LLM output
 * actually exist on disk under {@code <file.base-path>/output}. When a citation cannot be
 * grounded, the caller annotates it with {@code [NOT YET PRESENT]} or {@code [UNDEFINED]} so
 * downstream subgoals are framed as creation tasks rather than as references to fabricated state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SymbolGroundingService {

    public static final String PATH_MISSING_SUFFIX = " [NOT YET PRESENT]";
    public static final String SYMBOL_MISSING_SUFFIX = " [UNDEFINED]";
    private static final String OUTPUT_DIR = "output";

    /**
     * Multi-segment PascalCase identifier — requires at least one lowercase→uppercase
     * transition inside the token (e.g. {@code BookAlreadyLoanedException},
     * {@code LoanController}, {@code RestController}). Excludes:
     *   <ul>
     *     <li>All-caps acronyms ({@code URL}, {@code JSON}, {@code HTTP}).</li>
     *     <li>Capitalised English words ({@code The}, {@code Returns}, {@code Add}).</li>
     *     <li>Single-word PascalCase ({@code Book}, {@code Loan}) — too ambiguous to flag
     *         reliably; misses are tolerated to keep false-positive rate low.</li>
     *   </ul>
     */
    private static final Pattern PASCAL_CASE_IDENT = Pattern.compile(
            "\\b([A-Z][a-z]+[A-Z][a-zA-Z0-9_]*)\\b");

    /**
     * Regex fallback used only when {@link JavaStructureParser} fails (T5-BC: source files can be
     * mid-mutation and syntactically invalid). The AST path is preferred because it handles
     * multi-line declarations, annotations, and yields the FQN.
     */
    private static final Pattern TYPE_DECLARATION_FALLBACK = Pattern.compile(
            "(?:public\\s+|protected\\s+|private\\s+|static\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(?:class|interface|record|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");

    /** Skip annotation already applied — keeps grounding idempotent across cycles. */
    private static final Pattern ALREADY_ANNOTATED = Pattern.compile(
            "\\[(?:UNDEFINED|NOT YET PRESENT)\\]");

    /**
     * Used by {@link #refreshAnnotations(String)} to strip existing markers (and the single
     * separator space) so the text can be re-grounded against current state.
     */
    private static final Pattern STRIP_MARKERS = Pattern.compile(
            "\\s*\\[(?:UNDEFINED|NOT YET PRESENT)\\]");

    private static final Set<String> ALLOWLIST_PACKAGE_PREFIXES = Set.of(
            "java.", "javax.", "jakarta.",
            "org.springframework.", "org.springframework.boot.",
            "org.junit.", "org.slf4j.", "org.apache.",
            "lombok.", "com.fasterxml.jackson.", "org.hibernate.",
            "io.swagger.", "org.mapstruct.");

    /** Common simple names cited without FQN. Conservative — broaden as false positives surface. */
    private static final Set<String> ALLOWLIST_SIMPLE_NAMES = Set.of(
            // Primitives boxed + Object
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
            "Character", "Number", "Object", "Math", "System", "Class", "Void",
            // Collections
            "List", "ArrayList", "LinkedList", "Map", "HashMap", "LinkedHashMap", "TreeMap",
            "Set", "HashSet", "LinkedHashSet", "TreeSet", "Collection", "Iterator", "Iterable",
            "Optional", "Stream", "Collectors", "Arrays", "Collections", "Comparator",
            // Util
            "UUID", "Date", "Instant", "LocalDate", "LocalDateTime", "LocalTime", "Duration",
            "Period", "ZonedDateTime", "ZoneId", "OffsetDateTime", "ChronoUnit",
            // Exceptions
            "Exception", "RuntimeException", "IllegalArgumentException", "IllegalStateException",
            "NullPointerException", "IndexOutOfBoundsException", "UnsupportedOperationException",
            "IOException", "UncheckedIOException", "FileNotFoundException", "Throwable", "Error",
            "NumberFormatException", "ClassCastException", "ClassNotFoundException",
            // IO / NIO
            "Path", "Paths", "Files", "InputStream", "OutputStream", "Reader", "Writer",
            "BufferedReader", "BufferedWriter",
            // Spring stereotypes / web
            "RestController", "Service", "Repository", "Component", "Controller", "Configuration",
            "Bean", "Autowired", "Value", "Qualifier", "Primary", "Lazy", "Order",
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping",
            "PatchMapping", "RequestBody", "RequestParam", "PathVariable", "RequestHeader",
            "ResponseBody", "ResponseEntity", "HttpStatus", "MediaType", "RestControllerAdvice",
            "ControllerAdvice", "ExceptionHandler", "ResponseStatus", "Valid", "Validated",
            "SpringBootApplication", "EnableJpaRepositories", "EnableScheduling",
            "ConfigurationProperties", "EnableConfigurationProperties",
            // JPA / Jakarta
            "Entity", "Table", "Column", "Id", "GeneratedValue", "GenerationType", "Embeddable",
            "EmbeddedId", "OneToMany", "ManyToOne", "OneToOne", "ManyToMany", "JoinColumn",
            "JoinTable", "MappedSuperclass", "Inheritance", "DiscriminatorColumn",
            "DiscriminatorValue", "Lob", "Temporal", "TemporalType", "Transient", "Version",
            "FetchType", "CascadeType",
            // Validation
            "NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max", "Email", "Pattern", "Past",
            "Future", "Digits", "Constraint", "ConstraintValidator", "ConstraintValidatorContext",
            "Payload",
            // Lombok
            "Data", "Getter", "Setter", "RequiredArgsConstructor", "NoArgsConstructor",
            "AllArgsConstructor", "Slf4j", "Builder", "EqualsAndHashCode", "ToString", "With",
            "NonNull", "Synchronized",
            // Testing
            "Test", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll", "Disabled", "DisplayName",
            "ParameterizedTest", "Mock", "InjectMocks", "Spy", "Captor", "ExtendWith",
            // Java keywords / annotations that look PascalCase
            "Override", "SuppressWarnings", "Deprecated", "FunctionalInterface", "SafeVarargs",
            // Generics conventions sometimes capitalised mid-text
            "T", "E", "K", "V", "R");

    private final FileService fileService;

    /** True when {@code rel} resolves to an existing file/directory under {@code output/}. */
    public boolean pathExistsUnderOutput(String rel) {
        if (rel == null || rel.isBlank()) return false;
        String normalised = normalisePath(rel);
        if (normalised.isEmpty()) return false;
        try {
            return fileService.exists(OUTPUT_DIR + "/" + normalised);
        } catch (IOException e) {
            log.debug("pathExistsUnderOutput({}) failed: {}", rel, e.getMessage());
            return false;
        } catch (SecurityException e) {
            // Path tries to escape the root — treat as missing.
            return false;
        }
    }

    /**
     * Set of simple type names declared by any {@code .java} file under {@code output/}.
     * Recomputed each call: grounding fires after every analysis/planner LLM result, which is
     * infrequent compared to evolution/evaluation, so the walk cost is acceptable.
     */
    public Set<String> definedSymbolsInOutput() {
        Set<String> defined = new LinkedHashSet<>();
        Path outputRoot;
        try {
            outputRoot = fileService.resolveSecurePath(OUTPUT_DIR);
        } catch (IOException | SecurityException e) {
            log.debug("definedSymbolsInOutput: cannot resolve output dir: {}", e.getMessage());
            return defined;
        }
        if (!Files.isDirectory(outputRoot)) {
            return defined;
        }
        try (Stream<Path> stream = Files.walk(outputRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> collectDeclarations(p, defined));
        } catch (IOException e) {
            log.debug("definedSymbolsInOutput walk failed: {}", e.getMessage());
        }
        return defined;
    }

    /** True when {@code name} is a JDK / framework symbol that should never be flagged undefined. */
    public boolean symbolAllowlisted(String name) {
        if (name == null || name.isEmpty()) return false;
        if (ALLOWLIST_SIMPLE_NAMES.contains(name)) return true;
        for (String prefix : ALLOWLIST_PACKAGE_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        // FQN: keep the simple-name part, check allowlist once more.
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0 && lastDot < name.length() - 1) {
            return ALLOWLIST_SIMPLE_NAMES.contains(name.substring(lastDot + 1));
        }
        return false;
    }

    /**
     * Annotate {@code text} by wrapping each undefined PascalCase identifier with
     * {@code [UNDEFINED]}. Already-annotated text is returned unchanged so grounding is
     * idempotent across cycles. Discovered undefined names are added to {@code reportedSink}.
     */
    public String groundSymbolReferences(String text, Set<String> reportedSink) {
        if (text == null || text.isEmpty()) return text;
        if (ALREADY_ANNOTATED.matcher(text).find()) {
            // Best-effort: still extract and report names that look undefined, but don't rewrite.
            collectUndefinedSymbols(text, reportedSink);
            return text;
        }
        Set<String> defined = definedSymbolsInOutput();
        Set<String> undefinedHere = new LinkedHashSet<>();
        Matcher m = PASCAL_CASE_IDENT.matcher(text);
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (m.find()) {
            sb.append(text, idx, m.end());
            idx = m.end();
            String ident = m.group(1);
            if (defined.contains(ident) || symbolAllowlisted(ident)) continue;
            if (undefinedHere.add(ident)) {
                sb.append(SYMBOL_MISSING_SUFFIX);
                reportedSink.add(ident);
            } else {
                // Already annotated this identifier earlier in the text; skip duplicate suffix.
                continue;
            }
        }
        if (idx == 0) return text; // nothing matched
        sb.append(text, idx, text.length());
        return sb.toString();
    }

    /** Annotate {@code rel} with {@code [NOT YET PRESENT]} when no file exists at that path. */
    public String groundPath(String rel) {
        if (rel == null || rel.isBlank()) return rel;
        if (ALREADY_ANNOTATED.matcher(rel).find()) return rel;
        // Strip trailing punctuation that LLMs sometimes append before checking existence.
        String candidate = rel.trim();
        while (candidate.endsWith(".") || candidate.endsWith(",") || candidate.endsWith(";")) {
            candidate = candidate.substring(0, candidate.length() - 1).trim();
        }
        if (candidate.isEmpty()) return rel;
        if (pathExistsUnderOutput(candidate)) return rel;
        return rel + PATH_MISSING_SUFFIX;
    }

    /** Convenience — collect undefined symbol names in {@code text} without rewriting. */
    public void collectUndefinedSymbols(String text, Set<String> sink) {
        if (text == null || text.isEmpty()) return;
        Set<String> defined = definedSymbolsInOutput();
        Matcher m = PASCAL_CASE_IDENT.matcher(text);
        while (m.find()) {
            String ident = m.group(1);
            if (defined.contains(ident) || symbolAllowlisted(ident)) continue;
            sink.add(ident);
        }
    }

    /**
     * Strip stale {@code [UNDEFINED]} / {@code [NOT YET PRESENT]} markers and re-apply against
     * current ground truth. Use case: a subgoal description was annotated when its target symbol
     * didn't exist, then the symbol was created in a later cycle. The persisted description still
     * carries the original {@code [UNDEFINED]} tag and the planner LLM keeps proposing replacement
     * subgoals as if the symbol were still missing. Calling this method at prompt-render time
     * resolves the tag against the current state.
     */
    public String refreshAnnotations(String text) {
        if (text == null || text.isEmpty()) return text;
        if (!ALREADY_ANNOTATED.matcher(text).find()) return text;
        String stripped = STRIP_MARKERS.matcher(text).replaceAll("");
        Set<String> sink = new LinkedHashSet<>();
        return groundSymbolReferences(stripped, sink);
    }

    /**
     * Returns a {@link CycleContextData} whose {@code remainingGoals}, {@code completedGoals}, and
     * {@code abandonedGoals} have their descriptions re-grounded against current output state.
     * Goals whose descriptions don't change are passed through by reference; only the ones whose
     * markers actually shift trigger a {@link GoalEntity} clone. Persisted goal-graph state is
     * never mutated — the returned context wraps transient render-only copies.
     *
     * <p>Wire this into LLM adapters (planner, analysis) before they call
     * {@code getUserPrompt(input)} so the prompt reflects current ground truth even when markers
     * were baked into the description at creation time.
     */
    public CycleContextData refreshGoalAnnotations(CycleContextData ctx) {
        if (ctx == null) return ctx;
        List<GoalEntity> remaining = refreshGoalList(ctx.remainingGoals());
        List<GoalEntity> completed = refreshGoalList(ctx.completedGoals());
        List<GoalEntity> abandoned = refreshGoalList(ctx.abandonedGoals());
        if (remaining == ctx.remainingGoals()
                && completed == ctx.completedGoals()
                && abandoned == ctx.abandonedGoals()) {
            return ctx;
        }
        return ctx.toBuilder()
                .remainingGoals(remaining)
                .completedGoals(completed)
                .abandonedGoals(abandoned)
                .build();
    }

    private List<GoalEntity> refreshGoalList(List<GoalEntity> in) {
        if (in == null || in.isEmpty()) return in;
        List<GoalEntity> out = null; // lazy-allocated only if any goal actually changes
        for (int idx = 0; idx < in.size(); idx++) {
            GoalEntity g = in.get(idx);
            if (g == null) {
                if (out != null) out.add(null);
                continue;
            }
            String desc = g.getDescription();
            String refreshed = refreshAnnotations(desc);
            if (refreshed != null && !refreshed.equals(desc)) {
                if (out == null) {
                    out = new ArrayList<>(in.size());
                    for (int j = 0; j < idx; j++) out.add(in.get(j));
                }
                out.add(cloneWithDescription(g, refreshed));
            } else if (out != null) {
                out.add(g);
            }
        }
        return out == null ? in : out;
    }

    /** Shallow-copy a {@link GoalEntity} with a replaced description. Used for render-only views. */
    private static GoalEntity cloneWithDescription(GoalEntity g, String newDescription) {
        GoalEntity copy = new GoalEntity();
        copy.setId(g.getId());
        copy.setDescription(newDescription);
        copy.setRole(g.getRole());
        copy.setType(g.getType());
        copy.setParentId(g.getParentId());
        copy.setStatus(g.getStatus());
        copy.setCreated(g.getCreated());
        copy.setCompleted(g.getCompleted());
        copy.setGivenUp(g.getGivenUp());
        copy.setResolution(g.getResolution());
        // Metadata is rendered (mergeCount, mergedFiles, givenUpReason); share the map by reference
        // — this view is read-only and we don't want to deep-copy maps every render.
        copy.setMetadata(g.getMetadata());
        copy.setKind(g.getKind());
        return copy;
    }

    private static String normalisePath(String rel) {
        String s = rel.trim();
        // Drop annotation markers if the path was already grounded.
        s = ALREADY_ANNOTATED.matcher(s).replaceAll("").trim();
        // Tolerate leading slashes (LLM sometimes writes /src/...).
        while (s.startsWith("/")) s = s.substring(1);
        // Tolerate "output/" prefix in the citation itself.
        if (s.startsWith(OUTPUT_DIR + "/")) s = s.substring(OUTPUT_DIR.length() + 1);
        return s;
    }

    /**
     * Extract type declarations directly via JavaParser, descending into nested types
     * (inner classes/interfaces, nested enums, nested records). Falls back to a regex grep when
     * the file is syntactically invalid (T5-BC pattern: output files can be mid-mutation).
     *
     * <p>Both the FQN and the simple name are added at every depth. For
     * {@code BookService.ValidationException} (a public static inner class of BookService in
     * package {@code com.example.library.service}) the sink receives:
     * {@code com.example.library.service.BookService.ValidationException} and
     * {@code ValidationException}. Without this descent, callers would tag
     * {@code ValidationException} as {@code [UNDEFINED]} despite it being a real symbol the
     * compiler can resolve — observed in the 2026-05-19 21:17 run, where the planner spawned
     * runaway {@code [BUILD_FIX] Create ValidationException.java} subgoals and the LLM rewrote
     * the import to a non-existent {@code com.example.library.exception.ValidationException},
     * actually breaking the build.
     */
    private static void collectDeclarations(Path javaFile, Set<String> sink) {
        String content;
        try {
            content = Files.readString(javaFile);
        } catch (IOException e) {
            log.debug("collectDeclarations({}) read failed: {}", javaFile, e.getMessage());
            return;
        }
        try {
            CompilationUnit cu = new JavaParser().parse(content).getResult().orElse(null);
            if (cu == null) {
                throw new IllegalArgumentException("JavaParser returned no result");
            }
            // findAll(TypeDeclaration) returns top-level + every nested type at any depth.
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                String fqn = td.getFullyQualifiedName().orElse(null);
                if (fqn != null && !fqn.isBlank()) sink.add(fqn);
                String simple = td.getNameAsString();
                if (!simple.isBlank()) sink.add(simple);
            }
        } catch (RuntimeException e) {
            // T5-BC: file is syntactically invalid (in-flux mutation). Regex fallback indexes
            // every `class|interface|record|enum X` token in the source, including nested ones,
            // since the pattern is no longer line-anchored.
            log.debug("collectDeclarations({}) AST parse failed, falling back to regex: {}",
                    javaFile, e.getMessage());
            Matcher m = TYPE_DECLARATION_FALLBACK.matcher(content);
            while (m.find()) {
                sink.add(m.group(1));
            }
        }
    }
}
