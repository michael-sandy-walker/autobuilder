package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Category;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.inject.IdiomSkeletonConfigProperties.Idiom;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.text.StringDistances;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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

    private final SymbolGroundingService groundingService;
    private final IdiomSkeletonConfigProperties config;
    private final SymbolImportLookupService symbolImportLookup;
    private final Map<String, Pattern> compiledPatterns = new HashMap<>();

    public IdiomSkeletonResolver(SymbolGroundingService groundingService,
                                 IdiomSkeletonConfigProperties config,
                                 SymbolImportLookupService symbolImportLookup) {
        this.groundingService = groundingService;
        this.config = config;
        this.symbolImportLookup = symbolImportLookup;
    }

    @Override
    public int getOrder() {
        // Sourced from properties so deployments can re-rank the resolver chain without code
        // changes. BootstrapPomResolver is 10, SymbolImportResolver is 30, default here is 50.
        return config != null ? config.getResolverOrder() : 50;
    }

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken, CycleContextData context) {
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

        Idiom matched = firstMatching(missingToken);
        if (matched == null) return Optional.empty();

        Set<String> defined = groundingService.definedSymbolsInOutput();

        if (defined.contains(missingToken)) {
            log.debug("{}: token {} already defined under output/ — deferring to LLM",
                    matched.getRule(), missingToken);
            return Optional.empty();
        }

        int threshold = config.getSimilarityAbortThreshold();
        for (String existing : defined) {
            if (StringDistances.levenshtein(existing, missingToken, threshold) <= threshold) {
                log.debug("{}: similarity-abort missing={} ≈ existing={}",
                        matched.getRule(), missingToken, existing);
                return Optional.empty();
            }
        }

        String basePackage = pickBasePackage(context);
        String fqPackage = basePackage + "." + safeSegment(matched.getSubPackage());
        String relPath = "src/main/java/"
                + fqPackage.replace('.', '/')
                + "/" + missingToken + ".java";
        String body = render(matched.getTemplate(), missingToken, fqPackage);
        String description = render(matched.getDescription(), missingToken, fqPackage);

        FileMutation mutation = new FileMutation(relPath, body);
        InjectorFixRecord record = new InjectorFixRecord(
                matched.getRule(),
                classification.kind(),
                missingToken,
                List.of(relPath),
                description
        );
        log.info("CompileFixInjector: {} — created skeleton {} ({})",
                matched.getRule(), relPath, description);
        return Optional.of(new FixProposal(List.of(mutation), record));
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
