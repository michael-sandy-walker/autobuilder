package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.inject.IdiomSkeletonConfigProperties.Idiom;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-AY refactor: covers the single config-driven resolver. Each bundled idiom is exercised, plus
 * the abort guards (already-defined + Levenshtein) and the custom-config extension path.
 */
class IdiomSkeletonResolverTest {

    private final SymbolGroundingService grounding = mock(SymbolGroundingService.class);

    private static CycleContextData ctx(String basePackage) {
        return CycleContextData.builder()
                .cycleName("c-1")
                .outputPath("output")
                .establishedBasePackage(basePackage)
                .build();
    }

    private IdiomSkeletonResolver resolver() {
        return new IdiomSkeletonResolver(grounding, new IdiomSkeletonConfigProperties());
    }

    @Test
    void repository_missingToken_emitsJpaRepositorySkeleton() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        Optional<FixProposal> out = resolver().resolve(
                Kind.CANNOT_FIND_SYMBOL, "BookRepository", ctx("com.example.library"));

        assertThat(out).isPresent();
        FixProposal p = out.get();
        assertThat(p.mutations()).hasSize(1);
        assertThat(p.mutations().get(0).path())
                .isEqualTo("src/main/java/com/example/library/repository/BookRepository.java");
        assertThat(p.mutations().get(0).content())
                .contains("package com.example.library.repository;")
                .contains("extends JpaRepository<Object, Long>")
                .contains("public interface BookRepository");
        assertThat(p.record().rule()).isEqualTo("skeleton-repository");
        assertThat(p.record().missingToken()).isEqualTo("BookRepository");
    }

    @Test
    void exception_missingToken_emitsRuntimeExceptionSubclass() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        Optional<FixProposal> out = resolver().resolve(
                Kind.CANNOT_FIND_SYMBOL, "BookNotFoundException", ctx("com.example.library"));

        assertThat(out).isPresent();
        assertThat(out.get().mutations().get(0).path())
                .isEqualTo("src/main/java/com/example/library/exception/BookNotFoundException.java");
        assertThat(out.get().mutations().get(0).content())
                .contains("public class BookNotFoundException extends RuntimeException")
                .contains("public BookNotFoundException(String message)");
        assertThat(out.get().record().rule()).isEqualTo("skeleton-exception");
    }

    @Test
    void service_missingToken_emitsServiceAnnotatedClass() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        Optional<FixProposal> out = resolver().resolve(
                Kind.CANNOT_FIND_SYMBOL, "LoanService", ctx("com.example.library"));

        assertThat(out).isPresent();
        assertThat(out.get().mutations().get(0).path())
                .isEqualTo("src/main/java/com/example/library/service/LoanService.java");
        assertThat(out.get().mutations().get(0).content())
                .contains("@Service")
                .contains("public class LoanService");
    }

    @Test
    void controller_missingToken_emitsRestControllerAnnotatedClass() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        Optional<FixProposal> out = resolver().resolve(
                Kind.CANNOT_FIND_SYMBOL, "LoanController", ctx("com.example.library"));

        assertThat(out).isPresent();
        assertThat(out.get().mutations().get(0).path())
                .isEqualTo("src/main/java/com/example/library/controller/LoanController.java");
        assertThat(out.get().mutations().get(0).content())
                .contains("@RestController")
                .contains("public class LoanController");
    }

    @Test
    void abortsWhenSymbolAlreadyDefined() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of("BookRepository"));

        assertThat(resolver().resolve(
                Kind.CANNOT_FIND_SYMBOL, "BookRepository", ctx("com.example.library")))
                .as("already-defined symbol must not be re-created")
                .isEmpty();
    }

    @Test
    void abortsOnLevenshteinSimilarityWithExistingSymbol() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of("BookRepository"));

        // 'BookRepostiory' = transposition of 'BookRepository' → distance 2.
        assertThat(resolver().resolve(
                Kind.CANNOT_FIND_SYMBOL, "BookRepostiory", ctx("com.example.library")))
                .as("typo within Levenshtein distance must defer to LLM via T2-DC grounding")
                .isEmpty();
    }

    @Test
    void ignoresNonMatchingTokenAndOtherErrorKinds() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        IdiomSkeletonResolver r = resolver();
        // Non-matching token shape.
        assertThat(r.resolve(Kind.CANNOT_FIND_SYMBOL, "Book", ctx("com.example.library"))).isEmpty();
        // Wrong error kind.
        assertThat(r.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "BookRepository", ctx("com.example.library"))).isEmpty();
        assertThat(r.resolve(Kind.MISSING_POM, null, ctx("com.example.library"))).isEmpty();
        assertThat(r.resolve(Kind.UNKNOWN, null, ctx("com.example.library"))).isEmpty();
    }

    @Test
    void fallsBackToDefaultBasePackage_whenNoneEstablished() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        Optional<FixProposal> out = resolver().resolve(
                Kind.CANNOT_FIND_SYMBOL, "MissingException", ctx(null));

        assertThat(out).isPresent();
        assertThat(out.get().mutations().get(0).path())
                .startsWith("src/main/java/com/example/demo/exception/")
                .endsWith("MissingException.java");
    }

    @Test
    void customConfiguredIdiom_emitsSkeletonForNewSuffix() {
        // Demonstrates that the four bundled idioms are not the limit — a deployment can append
        // its own idiom (here: *Mapper) by setting autobuilder.build.skeletons.idioms.
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        IdiomSkeletonConfigProperties props = new IdiomSkeletonConfigProperties();
        props.setIdioms(List.of(new Idiom(
                "skeleton-mapper",
                "\\w+Mapper",
                "mapper",
                "Created {className} skeleton (MapStruct)",
                "package {packageDecl};\n@org.mapstruct.Mapper\npublic interface {className} {}\n"
        )));
        IdiomSkeletonResolver custom = new IdiomSkeletonResolver(grounding, props);

        Optional<FixProposal> out = custom.resolve(
                Kind.CANNOT_FIND_SYMBOL, "BookMapper", ctx("com.example.library"));

        assertThat(out).isPresent();
        FixProposal p = out.get();
        assertThat(p.mutations().get(0).path())
                .isEqualTo("src/main/java/com/example/library/mapper/BookMapper.java");
        assertThat(p.mutations().get(0).content()).contains("@org.mapstruct.Mapper")
                .contains("public interface BookMapper");
        assertThat(p.record().rule()).isEqualTo("skeleton-mapper");
        assertThat(p.record().description()).isEqualTo("Created BookMapper skeleton (MapStruct)");
    }

    @Test
    void resolverOrder_isReadFromConfigurationProperties() {
        IdiomSkeletonConfigProperties props = new IdiomSkeletonConfigProperties();
        props.setResolverOrder(99);
        IdiomSkeletonResolver custom = new IdiomSkeletonResolver(grounding, props);
        assertThat(custom.getOrder()).isEqualTo(99);
    }
}
