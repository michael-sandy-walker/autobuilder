package com.walpro.autobuilder.core.build.inject;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.structure.UsageDerivedMemberSynthesizer;
import com.walpro.autobuilder.analysis.structure.UsageDerivedMemberSynthesizer.MemberSignature;
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
import java.util.HashMap;
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
    private final UsageDerivedMemberSynthesizer memberSynthesizer;
    private final Map<String, Pattern> compiledPatterns = new HashMap<>();

    public IdiomSkeletonResolver(SymbolGroundingService groundingService,
                                 IdiomSkeletonConfigProperties config,
                                 SymbolImportLookupService symbolImportLookup,
                                 FileService fileService,
                                 UsageDerivedMemberSynthesizer memberSynthesizer) {
        this.groundingService = groundingService;
        this.config = config;
        this.symbolImportLookup = symbolImportLookup;
        this.fileService = fileService;
        this.memberSynthesizer = memberSynthesizer;
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

        Set<String> defined = groundingService.definedSymbolsInOutput();

        if (defined.contains(missingToken)) {
            log.debug("IdiomSkeletonResolver: token {} already defined under output/ — deferring to LLM",
                    missingToken);
            return Optional.empty();
        }

        int threshold = config.getSimilarityAbortThreshold();
        for (String existing : defined) {
            if (StringDistances.levenshtein(existing, missingToken, threshold) <= threshold) {
                log.debug("IdiomSkeletonResolver: similarity-abort missing={} ≈ existing={}",
                        missingToken, existing);
                return Optional.empty();
            }
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

        // T4-BQ: populate the skeleton with the members its call sites prove must exist, so the
        // whole missing-type closure lands in one pass rather than one missing-member probe at a
        // time. Gated behind a flag; a synthesis failure leaves the plain skeleton untouched.
        int synthesized = 0;
        if (config.isSynthesizeMembers()) {
            try {
                Path javaRoot = fileService.resolveSecurePath("output/src/main/java");
                List<MemberSignature> members = memberSynthesizer.synthesize(missingToken, javaRoot);
                if (!members.isEmpty()) {
                    String augmented = injectMembers(body, members);
                    if (augmented != null) {
                        synthesized = members.size();
                        body = augmented;
                        description = description + " + " + synthesized + " usage-derived member(s)";
                    }
                }
            } catch (Exception e) {
                log.debug("IdiomSkeletonResolver: member synthesis skipped for {} ({})",
                        missingToken, e.getMessage());
            }
        }

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

    /**
     * T4-BQ: parses the freshly-rendered skeleton and adds the usage-derived members, skipping any
     * already declared (e.g. the exception template's {@code (String message)} constructor). Adds
     * stub bodies for classes ({@code return null;}) and bodiless abstract members for interfaces
     * (the repository case). Returns {@code null} if the skeleton cannot be parsed or carries no
     * type declaration, so the caller keeps the plain skeleton.
     */
    private String injectMembers(String skeleton, List<MemberSignature> members) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(skeleton);
        } catch (Exception e) {
            return null;
        }
        Optional<ClassOrInterfaceDeclaration> primary = cu.findFirst(ClassOrInterfaceDeclaration.class);
        if (primary.isEmpty()) return null;
        ClassOrInterfaceDeclaration type = primary.get();
        boolean isInterface = type.isInterface();

        for (MemberSignature m : members) {
            if (m.constructor()) {
                if (isInterface || hasConstructor(type, m.paramTypes().size())) continue;
                ConstructorDeclaration cd = type.addConstructor();
                cd.setPublic(true);
                addParams(cd::addParameter, m.paramTypes());
                cd.setBody(new BlockStmt());
            } else {
                if (hasMethod(type, m.name(), m.paramTypes().size())) continue;
                MethodDeclaration md = new MethodDeclaration();
                md.setName(m.name());
                md.setType(m.returnType());
                md.setPublic(true);
                addParams(md::addParameter, m.paramTypes());
                if (isInterface) {
                    md.setBody(null); // implicitly public abstract
                } else {
                    md.setBody(StaticJavaParser.parseBlock("{ return null; }"));
                }
                type.addMember(md);
            }
        }
        return cu.toString();
    }

    private static void addParams(java.util.function.BiConsumer<String, String> add, List<String> paramTypes) {
        for (int i = 0; i < paramTypes.size(); i++) {
            add.accept(paramTypes.get(i), "p" + i);
        }
    }

    private static boolean hasMethod(ClassOrInterfaceDeclaration type, String name, int arity) {
        return type.getMethods().stream()
                .anyMatch(md -> md.getNameAsString().equals(name) && md.getParameters().size() == arity);
    }

    private static boolean hasConstructor(ClassOrInterfaceDeclaration type, int arity) {
        return type.getConstructors().stream()
                .anyMatch(cd -> cd.getParameters().size() == arity);
    }
}
