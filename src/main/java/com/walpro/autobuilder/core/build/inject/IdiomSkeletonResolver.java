package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Category;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.inject.IdiomSkeletonConfigProperties.Idiom;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.core.text.StringDistances;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T4-AY (refactored 2026-05-21): single deterministic resolver that materialises a missing-class
 * skeleton when the compile probe names a symbol matching one of the configured idioms. Replaces
 * the four hand-written Repository/Exception/Service/Controller resolvers that preceded it; the
 * idiom table now lives in {@link IdiomSkeletonConfigProperties} so new conventions are a YAML
 * edit, not a new class.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Reject anything that isn't a {@link Category#SOURCE_FIXABLE} diagnosis.</li>
 *   <li>Find the first configured idiom whose {@code pattern} matches the missing token.</li>
 *   <li>Abort if the token already names a symbol under {@code output/} (LLM has an import/FQN
 *       issue, not a missing class).</li>
 *   <li>Abort if the token is within {@code similarityAbortThreshold} edits of an existing symbol
 *       (typo — T2-DC grounding will surface it to the LLM).</li>
 *   <li>Render the template with {@code {className}} and {@code {packageDecl}} substitutions and
 *       emit a {@link FixProposal} the injector pipeline writes to disk.</li>
 * </ol>
 *
 * <p>T4-AN-7: dispatches on the universal {@link Category#SOURCE_FIXABLE} field rather than
 * T2-CT's ecosystem-specific {@code Kind.CANNOT_FIND_SYMBOL} regex label, and prefers the
 * adaptive classifier's {@link Classification#missingSymbol()} when present, falling back to the
 * pattern-extracted token. On the classifier-disabled path the two are equivalent
 * ({@code SOURCE_FIXABLE == defaultCategoryFor(CANNOT_FIND_SYMBOL)}, {@code missingSymbol == null}),
 * so behaviour is unchanged by default; the adaptive path lets a non-regex-matchable error (e.g.
 * {@code kind == UNKNOWN}) still route here when the LLM tags it {@code SOURCE_FIXABLE}.
 */
@Slf4j
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "compiled patterns, constant for the lifetime of the process")
public class IdiomSkeletonResolver implements CompileFixResolver, Ordered {

    /** T3-BV: matches the diagnostic detail line naming a missing <i>class</i> (vs method/variable). */
    private static final String SYMBOL_CLASS_LINE = "symbol:\\s*class\\s+";

    private static final String PLAIN_CLASS_TEMPLATE = """
            package {packageDecl};

            public class {className} {
            }
            """;

    private final SymbolGroundingService groundingService;
    private final IdiomSkeletonConfigProperties config;
    private final SymbolImportLookupService symbolImportLookup;
    private final FileService fileService;
    private final Map<String, Pattern> compiledPatterns = new HashMap<>();

    /** T6-T: what the project's classpath declares; optional in narrow tests. */
    @lombok.Setter
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PackageDiscoveryService classpathDiscovery;

    public IdiomSkeletonResolver(SymbolGroundingService groundingService,
                                 IdiomSkeletonConfigProperties config,
                                 SymbolImportLookupService symbolImportLookup,
                                 FileService fileService) {
        this.groundingService = groundingService;
        this.config = config;
        this.symbolImportLookup = symbolImportLookup;
        this.fileService = fileService;
    }

    @Override
    public int getOrder() {
        // Sourced from properties so deployments can re-rank the resolver chain without code
        // changes. BootstrapPomResolver is 10, SymbolImportResolver is 30, default here is 50.
        return config != null ? config.getResolverOrder() : 50;
    }

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken, String stderr,
                                         CycleContextData context) {
        if (classification == null || classification.category() != Category.SOURCE_FIXABLE) {
            return Optional.empty();
        }
        // T4-AN-7: prefer the adaptive classifier's structured symbol; fall back to the
        // pattern-extracted token (the only source on the classifier-disabled path).
        if (classification.missingSymbol() != null && !classification.missingSymbol().isBlank()) {
            missingToken = classification.missingSymbol();
        }
        if (missingToken == null || missingToken.isBlank()) return Optional.empty();
        if (context == null) return Optional.empty();
        if (config == null || config.getIdioms() == null || config.getIdioms().isEmpty()) {
            return Optional.empty();
        }

        // T1-EN: if the symbol resolves as a framework/library import, this is not a missing-class
        // case — defer to SymbolImportResolver. Cheap method call: cache hit after the first
        // encounter, network-free for repeat invocations.
        if (symbolImportLookup != null && symbolImportLookup.lookup(missingToken).isPresent()) {
            log.debug("IdiomSkeletonResolver: token {} resolves as an import — deferring to SymbolImportResolver",
                    missingToken);
            return Optional.empty();
        }

        // T6-T: a name the project's classpath already declares is not a class the project lacks. Ladder-grind
        // run 2026-10-13_01-53-56: a candidate used the parser library's Node and ClassOrInterfaceDeclaration
        // without importing them, an empty class of each name was created beside the file that used them, and
        // from then on the project's own empty Node stood where the library's was meant.
        if (classpathDiscovery != null) {
            try {
                if (!classpathDiscovery.classpathTypes(missingToken, fileService.resolveSecurePath("output")).isEmpty()) {
                    log.info("IdiomSkeletonResolver: {} is declared on the project's classpath -- no class of that"
                            + " name is created", missingToken);
                    return Optional.empty();
                }
            } catch (java.io.IOException | RuntimeException e) {
                log.debug("IdiomSkeletonResolver: the classpath could not be asked about {}: {}", missingToken,
                        e.getMessage());
            }
        }

        Set<String> defined = groundingService.definedSymbolsInOutput();

        if (defined.contains(missingToken)) {
            log.debug("IdiomSkeletonResolver: token {} already defined under output/ — deferring to LLM",
                    missingToken);
            return Optional.empty();
        }

        // Duplicate-type guard: do not manufacture a skeleton for a name that duplicates a type the
        // project already declares. Two shapes: a typo-near name (Levenshtein) AND — the case the
        // edit-distance threshold misses — a semantic paraphrase the local model mints across files
        // (BookAlreadyLoanedException vs BookAlreadyOnLoanException; MemberMaxLoansException vs
        // MemberHasTooManyLoansException), caught by a CamelCase concept match (same head entity +
        // same role suffix + token-set overlap). Creating the skeleton here — or letting the
        // planner's BUILD_FIX_DIVERGENCE rule create the named file — would MANUFACTURE the
        // duplicate. Action is ABORT-create only: the unresolved reference is handed to the
        // reuse-first planning rule. The deterministic rewrite-to-existing is intentionally NOT done
        // here — a blind rename by name-similarity would corrupt genuinely distinct concepts
        // (BookCreatedException vs BookDeletedException); that judgment belongs to the semantic
        // reviewer (T4-AW).
        Optional<String> nearDuplicate = findExistingNearDuplicate(
                missingToken, defined, config.getSimilarityAbortThreshold());
        if (nearDuplicate.isPresent()) {
            log.info("IdiomSkeletonResolver: not creating skeleton for {} — near-duplicate of existing type {} "
                            + "(likely a minted synonym); deferring to reuse-first planning",
                    missingToken, nearDuplicate.get());
            return Optional.empty();
        }

        Idiom matched = firstMatching(missingToken);
        String rule;
        String template;
        String descriptionTemplate;
        String conventionPackage;
        String basePackage = pickBasePackage(context);
        if (matched != null) {
            rule = matched.getRule();
            template = matched.getTemplate();
            descriptionTemplate = matched.getDescription();
            conventionPackage = basePackage + "." + safeSegment(matched.getSubPackage());
        } else {
            // T3-BV: plain-class fallback. Model/entity classes match no naming idiom (`Loan`
            // starved the 18:16 run for 22 minutes with no resolver able to create it). Gated on
            // the diagnostic literally naming a missing *class* — a missing method or variable
            // token must never become a class file.
            if (stderr == null
                    || !Pattern.compile(SYMBOL_CLASS_LINE + Pattern.quote(missingToken) + "\\b").matcher(stderr).find()) {
                return Optional.empty();
            }
            rule = "skeleton-class";
            template = PLAIN_CLASS_TEMPLATE;
            descriptionTemplate = "Created {className} minimal class skeleton";
            conventionPackage = basePackage;
        }

        // T3-BV: the package the failing references expect beats any naming convention — a
        // skeleton the referencing code cannot see is a guaranteed wasted probe (observed
        // 2026-06-10 16:21: exception skeleton created in `.exception` while the reference
        // imported `.member`; identical re-probe failure 3 s later).
        String fqPackage = referenceDerivedPackage(missingToken).orElse(conventionPackage);
        String relPath = "src/main/java/"
                + fqPackage.replace('.', '/')
                + "/" + missingToken + ".java";
        String body = render(template, missingToken, fqPackage);
        String description = render(descriptionTemplate, missingToken, fqPackage);

        FileMutation mutation = new FileMutation(relPath, body);
        InjectorFixRecord record = new InjectorFixRecord(
                rule,
                classification.kind(),
                missingToken,
                List.of(relPath),
                description
        );
        log.info("CompileFixInjector: {} — created skeleton {} ({})",
                rule, relPath, description);
        return Optional.of(new FixProposal(List.of(mutation), record));
    }

    /**
     * T3-BV: derives the package the on-disk references expect for {@code token}. Precedence:
     * an explicit {@code import <pkg>.<token>;} anywhere under output/ (the reference says
     * exactly where it looks), then the declaring package of a file that uses the token
     * unqualified (same-package resolution). Empty when nothing references the token — the
     * caller falls back to its naming convention.
     */
    private Optional<String> referenceDerivedPackage(String token) {
        Pattern importLine = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\." + Pattern.quote(token) + "\\s*;");
        Pattern usage = Pattern.compile("\\b" + Pattern.quote(token) + "\\b");
        Pattern packageDecl = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

        Map<String, Integer> importPackages = new LinkedHashMap<>();
        Map<String, Integer> usagePackages = new LinkedHashMap<>();
        try {
            Path javaRoot = fileService.resolveSecurePath("output/src/main/java");
            if (!Files.isDirectory(javaRoot)) return Optional.empty();
            try (Stream<Path> walk = Files.walk(javaRoot)) {
                walk.filter(pth -> pth.toString().endsWith(".java"))
                        .filter(pth -> !pth.getFileName().toString().equals(token + ".java"))
                        .forEach(pth -> {
                            String content;
                            try {
                                content = Files.readString(pth);
                            } catch (Exception e) {
                                return;
                            }
                            Matcher imp = importLine.matcher(content);
                            if (imp.find()) {
                                importPackages.merge(imp.group(1), 1, Integer::sum);
                            } else if (usage.matcher(content).find()) {
                                Matcher pkg = packageDecl.matcher(content);
                                if (pkg.find()) usagePackages.merge(pkg.group(1), 1, Integer::sum);
                            }
                        });
            }
        } catch (Exception e) {
            log.debug("IdiomSkeletonResolver: reference scan failed ({}) — falling back to convention", e.getMessage());
            return Optional.empty();
        }

        Optional<String> fromImports = mostCommon(importPackages);
        if (fromImports.isPresent()) {
            log.debug("IdiomSkeletonResolver: placement for {} from import statements: {}", token, fromImports.get());
            return fromImports;
        }
        Optional<String> fromUsage = mostCommon(usagePackages);
        fromUsage.ifPresent(pkg ->
                log.debug("IdiomSkeletonResolver: placement for {} from unqualified usage in package {}", token, pkg));
        return fromUsage;
    }

    private static Optional<String> mostCommon(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    /** Min CamelCase token-set overlap (Jaccard) for two same-head/same-suffix names to count as one concept. */
    static final double CONCEPT_JACCARD_MIN = 0.4;

    /**
     * Returns an existing project type that {@code missing} duplicates, or empty. Matches a typo-near
     * name (Levenshtein within {@code levThreshold}) or a semantic paraphrase the model minted (same
     * leading entity word + same trailing role word + CamelCase token-set Jaccard ≥
     * {@link #CONCEPT_JACCARD_MIN}). Self-matches are skipped.
     */
    Optional<String> findExistingNearDuplicate(String missing, Set<String> defined, int levThreshold) {
        if (missing == null || defined == null) return Optional.empty();
        List<String> missTokens = camelTokens(missing);
        for (String existing : defined) {
            if (existing == null || existing.equals(missing)) continue;
            if (StringDistances.levenshtein(existing, missing, levThreshold) <= levThreshold) {
                return Optional.of(existing);
            }
            if (sameConceptStructure(missTokens, camelTokens(existing))) {
                return Optional.of(existing);
            }
        }
        return Optional.empty();
    }

    /** True when two CamelCase token lists name the same entity+role and overlap heavily — a minted synonym. */
    private static boolean sameConceptStructure(List<String> a, List<String> b) {
        if (a.size() < 2 || b.size() < 2) return false;
        if (!a.get(0).equalsIgnoreCase(b.get(0))) return false;                       // same head entity
        if (!a.get(a.size() - 1).equalsIgnoreCase(b.get(b.size() - 1))) return false; // same role suffix
        Set<String> sa = new HashSet<>();
        for (String t : a) sa.add(t.toLowerCase(java.util.Locale.ROOT));
        Set<String> sb = new HashSet<>();
        for (String t : b) sb.add(t.toLowerCase(java.util.Locale.ROOT));
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return !union.isEmpty() && (double) inter.size() / union.size() >= CONCEPT_JACCARD_MIN;
    }

    /** Splits an identifier on CamelCase boundaries and non-alphanumerics into its constituent words. */
    static List<String> camelTokens(String name) {
        if (name == null || name.isEmpty()) return List.of();
        String[] parts = name.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[^A-Za-z0-9]+");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p != null && !p.isBlank()) out.add(p);
        }
        return out;
    }

    private Idiom firstMatching(String token) {
        for (Idiom idiom : config.getIdioms()) {
            if (idiom == null || idiom.getPattern() == null || idiom.getRule() == null) continue;
            Pattern p = compiledPatterns.computeIfAbsent(idiom.getPattern(), Pattern::compile);
            if (p.matcher(token).matches()) return idiom;
        }
        return null;
    }

    private String pickBasePackage(CycleContextData context) {
        String base = context.establishedBasePackage();
        if (base != null && !base.isBlank()) return base;
        return config.getDefaultBasePackage() != null && !config.getDefaultBasePackage().isBlank()
                ? config.getDefaultBasePackage()
                : "com.example.demo";
    }

    private static String safeSegment(String s) {
        if (s == null) return "skeleton";
        String trimmed = s.trim();
        return trimmed.isEmpty() ? "skeleton" : trimmed;
    }

    private static String render(String template, String className, String packageDecl) {
        if (template == null) return "";
        return template
                .replace("{className}", className)
                .replace("{packageDecl}", packageDecl);
    }

}
