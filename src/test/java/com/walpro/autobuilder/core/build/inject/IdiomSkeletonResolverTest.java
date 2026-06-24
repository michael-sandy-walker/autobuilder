package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Category;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.inject.IdiomSkeletonConfigProperties.Idiom;
import com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.Resolution;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
    private final SymbolImportLookupService lookup = mock(SymbolImportLookupService.class);

    private static CycleContextData ctx(String basePackage) {
        return CycleContextData.builder()
                .cycleName("c-1")
                .outputPath("output")
                .establishedBasePackage(basePackage)
                .build();
    }

    private IdiomSkeletonResolver resolver() {
        return new IdiomSkeletonResolver(grounding, new IdiomSkeletonConfigProperties(), lookup,
                mock(com.walpro.autobuilder.core.service.FileService.class),
                new com.walpro.autobuilder.analysis.structure.UsageDerivedMemberSynthesizer());
    }

    @Test
    void repository_missingToken_emitsJpaRepositorySkeleton() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        Optional<FixProposal> out = resolver().resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "BookRepository", null, ctx("com.example.library"));

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
                cls(Kind.CANNOT_FIND_SYMBOL), "BookNotFoundException", null, ctx("com.example.library"));

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
                cls(Kind.CANNOT_FIND_SYMBOL), "LoanService", null, ctx("com.example.library"));

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
                cls(Kind.CANNOT_FIND_SYMBOL), "LoanController", null, ctx("com.example.library"));

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
                cls(Kind.CANNOT_FIND_SYMBOL), "BookRepository", null, ctx("com.example.library")))
                .as("already-defined symbol must not be re-created")
                .isEmpty();
    }

    @Test
    void abortsOnLevenshteinSimilarityWithExistingSymbol() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of("BookRepository"));

        // 'BookRepostiory' = transposition of 'BookRepository' → distance 2.
        assertThat(resolver().resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "BookRepostiory", null, ctx("com.example.library")))
                .as("typo within Levenshtein distance must defer to LLM via T2-DC grounding")
                .isEmpty();
    }

    @Test
    void abortsOnMintedSynonym_paraphrasedException_beyondEditDistance() {
        // The real 2026-06-24 artifact: the model minted two names for one concept across files.
        // These are 4-6 edits apart so the Levenshtein guard misses them; the CamelCase concept
        // match must catch them so the resolver does NOT manufacture the duplicate skeleton.
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of("BookAlreadyOnLoanException"));
        assertThat(resolver().resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "BookAlreadyLoanedException", null, ctx("com.example.demo")))
                .as("paraphrased synonym of an existing type must not be re-created")
                .isEmpty();

        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of("MemberHasTooManyLoansException"));
        assertThat(resolver().resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "MemberMaxLoansException", null, ctx("com.example.demo")))
                .isEmpty();
    }

    @Test
    void doesNotAbortForDistinctEntity_sameSuffix() {
        // BookNotFoundException is a genuinely new type even though MemberNotFoundException exists —
        // different head entity ⇒ create it, do not treat as a duplicate.
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of("MemberNotFoundException"));
        assertThat(resolver().resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "BookNotFoundException", null, ctx("com.example.demo")))
                .as("distinct entity with shared suffix is not a synonym")
                .isPresent();
    }

    @Test
    void nearDuplicateMatcher_unit() {
        IdiomSkeletonResolver r = resolver();
        // minted synonyms (semantic paraphrase, beyond edit distance)
        assertThat(r.findExistingNearDuplicate("BookAlreadyLoanedException",
                Set.of("BookAlreadyOnLoanException"), 2)).contains("BookAlreadyOnLoanException");
        assertThat(r.findExistingNearDuplicate("MemberMaxLoansException",
                Set.of("MemberHasTooManyLoansException"), 2)).contains("MemberHasTooManyLoansException");
        // typo (Levenshtein)
        assertThat(r.findExistingNearDuplicate("BookRepostiory",
                Set.of("BookRepository"), 2)).contains("BookRepository");
        // distinct concepts must NOT match
        assertThat(r.findExistingNearDuplicate("BookNotFoundException",
                Set.of("MemberNotFoundException"), 2)).isEmpty();
        assertThat(r.findExistingNearDuplicate("BookController",
                Set.of("MemberService"), 2)).isEmpty();
        // self is not a duplicate
        assertThat(r.findExistingNearDuplicate("BookService",
                Set.of("BookService"), 2)).isEmpty();
    }

    @Test
    void camelTokens_splitsOnBoundaries() {
        assertThat(IdiomSkeletonResolver.camelTokens("BookAlreadyOnLoanException"))
                .containsExactly("Book", "Already", "On", "Loan", "Exception");
        assertThat(IdiomSkeletonResolver.camelTokens("MemberHasTooManyLoansException"))
                .containsExactly("Member", "Has", "Too", "Many", "Loans", "Exception");
    }

    @Test
    void ignoresNonMatchingTokenAndOtherErrorKinds() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        IdiomSkeletonResolver r = resolver();
        // Non-matching token shape.
        assertThat(r.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "Book", null, ctx("com.example.library"))).isEmpty();
        // Wrong error kind.
        assertThat(r.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "BookRepository", null, ctx("com.example.library"))).isEmpty();
        assertThat(r.resolve(cls(Kind.MISSING_POM), null, null, ctx("com.example.library"))).isEmpty();
        assertThat(r.resolve(cls(Kind.UNKNOWN), null, null, ctx("com.example.library"))).isEmpty();
    }

    @Test
    void fallsBackToDefaultBasePackage_whenNoneEstablished() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        Optional<FixProposal> out = resolver().resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "MissingException", null, ctx(null));

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
        IdiomSkeletonResolver custom = new IdiomSkeletonResolver(grounding, props, lookup,
                mock(com.walpro.autobuilder.core.service.FileService.class), synthesizer());

        Optional<FixProposal> out = custom.resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "BookMapper", null, ctx("com.example.library"));

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
    void defersWhenSymbolResolvesAsImport() {
        // T1-EN: when SymbolImportLookupService resolves the token as a known framework symbol,
        // the idiom resolver must NOT fabricate a stub — it defers so SymbolImportResolver can
        // emit the import edit instead.
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        when(lookup.lookup("RestController"))
                .thenReturn(Optional.of(new Resolution(
                        "org.springframework.web.bind.annotation.RestController",
                        "org.springframework.boot",
                        "spring-boot-starter-web",
                        null)));

        assertThat(resolver().resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "RestController", null, ctx("com.example.library")))
                .as("symbol resolvable as an import must not be stubbed")
                .isEmpty();
    }

    @Test
    void resolverOrder_isReadFromConfigurationProperties() {
        IdiomSkeletonConfigProperties props = new IdiomSkeletonConfigProperties();
        props.setResolverOrder(99);
        IdiomSkeletonResolver custom = new IdiomSkeletonResolver(grounding, props, lookup,
                mock(com.walpro.autobuilder.core.service.FileService.class), synthesizer());
        assertThat(custom.getOrder()).isEqualTo(99);
    }

    @Test
    void adaptivePath_dispatchesOnCategory_andReadsMissingSymbolOverToken() {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());

        // Adaptive classifier result: kind=UNKNOWN (the static matcher could not derive it) but the
        // LLM tagged category=SOURCE_FIXABLE and extracted missingSymbol — a state the pattern path
        // can never produce. Proves dispatch is category-driven, and the symbol is preferred over
        // the (here deliberately wrong) passed token.
        Classification adaptive = new Classification(
                Kind.UNKNOWN, List.of(), Category.SOURCE_FIXABLE,
                "missing repository type", null, "BookRepository", null);

        Optional<FixProposal> out = resolver().resolve(adaptive, "IgnoredToken", null, ctx("com.example.library"));

        assertThat(out).isPresent();
        assertThat(out.get().mutations().get(0).path())
                .isEqualTo("src/main/java/com/example/library/repository/BookRepository.java");
        assertThat(out.get().record().missingToken()).isEqualTo("BookRepository");
        // The recorded kind mirrors the classification, not a hardcoded CANNOT_FIND_SYMBOL literal.
        assertThat(out.get().record().kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void configFixableCategory_isRejected() {
        // A CONFIG_FIXABLE diagnosis (e.g. a missing package) must not produce a class skeleton.
        Classification config = new Classification(
                Kind.PACKAGE_DOES_NOT_EXIST, List.of(), Category.CONFIG_FIXABLE,
                null, "jakarta.persistence", null, null);
        assertThat(resolver().resolve(config, "BookRepository", null, ctx("com.example.library"))).isEmpty();
    }

    /** T4-AN-7: builds a pattern-path Classification from a Kind (category derived via defaultCategoryFor). */
    private static Classification cls(Kind kind) {
        return new Classification(kind, List.of());
    }

    // ── T3-BV: reference-derived placement + plain-class fallback ──

    @TempDir
    Path tmp;

    private IdiomSkeletonResolver resolverWithProject(String relPath, String content) throws Exception {
        return resolverWithProject(relPath, content, new IdiomSkeletonConfigProperties());
    }

    private IdiomSkeletonResolver resolverWithProject(String relPath, String content,
                                                      IdiomSkeletonConfigProperties props) throws Exception {
        Path file = tmp.resolve("output/src/main/java/" + relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return new IdiomSkeletonResolver(grounding, props, lookup,
                new FileService(tmp.toString(), new ObjectMapper()), synthesizer());
    }

    private static com.walpro.autobuilder.analysis.structure.UsageDerivedMemberSynthesizer synthesizer() {
        return new com.walpro.autobuilder.analysis.structure.UsageDerivedMemberSynthesizer();
    }

    @Test
    void placement_followsImportStatement_overIdiomConvention() throws Exception {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        // The reference imports the exception from `.member` — the skeleton must land there,
        // not in the idiom's `.exception` convention package (2026-06-10 16:21 failure shape).
        IdiomSkeletonResolver r = resolverWithProject("com/example/library/MemberService.java", """
                package com.example.library;
                import com.example.library.member.MemberAlreadyExistsException;
                public class MemberService {
                    void add() { throw new MemberAlreadyExistsException("x"); }
                }
                """);

        Optional<FixProposal> out = r.resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "MemberAlreadyExistsException", null, ctx("com.example.library"));

        assertThat(out).isPresent();
        assertThat(out.get().mutations().get(0).path())
                .isEqualTo("src/main/java/com/example/library/member/MemberAlreadyExistsException.java");
        assertThat(out.get().mutations().get(0).content())
                .contains("package com.example.library.member;");
        assertThat(out.get().record().rule()).isEqualTo("skeleton-exception");
    }

    @Test
    void placement_followsUnqualifiedUsage_referencingFilesOwnPackage() throws Exception {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        // No import — an unqualified reference resolves same-package, so the skeleton must land
        // in the referencing file's package.
        IdiomSkeletonResolver r = resolverWithProject("com/example/library/web/BookService.java", """
                package com.example.library.web;
                public class BookService {
                    void get() { throw new BookNotFoundException("x"); }
                }
                """);

        Optional<FixProposal> out = r.resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "BookNotFoundException", null, ctx("com.example.library"));

        assertThat(out).isPresent();
        assertThat(out.get().mutations().get(0).path())
                .isEqualTo("src/main/java/com/example/library/web/BookNotFoundException.java");
        assertThat(out.get().mutations().get(0).content())
                .contains("package com.example.library.web;");
    }

    @Test
    void plainClass_createdOnClassKindDiagnostic_atReferencedPackage() throws Exception {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        // `Loan` matches no naming idiom — the 18:16 run starved 22 minutes on exactly this.
        // The plain-class step fires only because the diagnostic names a missing *class*.
        IdiomSkeletonResolver r = resolverWithProject("com/example/library/LoanController.java", """
                package com.example.library;
                import com.example.library.model.Loan;
                public class LoanController {
                    Loan current;
                }
                """);
        String stderr = """
                [ERROR] /x/LoanController.java:[3,1] cannot find symbol
                [ERROR]   symbol:   class Loan
                """;

        Optional<FixProposal> out = r.resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "Loan", stderr, ctx("com.example.library"));

        assertThat(out).isPresent();
        assertThat(out.get().record().rule()).isEqualTo("skeleton-class");
        assertThat(out.get().mutations().get(0).path())
                .isEqualTo("src/main/java/com/example/library/model/Loan.java");
        assertThat(out.get().mutations().get(0).content())
                .contains("package com.example.library.model;")
                .contains("public class Loan");
    }

    @Test
    void plainClass_notCreatedForMethodOrVariableDiagnostics() throws Exception {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        IdiomSkeletonResolver r = resolverWithProject("com/example/library/LoanService.java", """
                package com.example.library;
                public class LoanService {
                    void f() { findAll(); }
                }
                """);

        assertThat(r.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "findAll",
                "[ERROR]   symbol:   method findAll()", ctx("com.example.library")))
                .as("a missing method must never become a class file")
                .isEmpty();
        assertThat(r.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "Loan", null, ctx("com.example.library")))
                .as("no stderr, no idiom match — nothing to gate the class fallback on")
                .isEmpty();
    }

    // ── T4-BQ: usage-derived member synthesis (flag on) ──

    @Test
    void synthesisOn_repositorySkeletonGainsCallSiteMethod() throws Exception {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        IdiomSkeletonConfigProperties props = new IdiomSkeletonConfigProperties();
        props.setSynthesizeMembers(true);
        // A service injects the missing repository and calls findByBookId on it — the closure the
        // 2026-06-14 run dribbled out one missing-member probe at a time.
        IdiomSkeletonResolver r = resolverWithProject("com/example/library/service/LoanService.java", """
                package com.example.library.service;
                import com.example.library.repository.LoanRepository;
                public class LoanService {
                    private final LoanRepository loanRepository;
                    public LoanService(LoanRepository loanRepository) { this.loanRepository = loanRepository; }
                    public Object find(Long bookId) { return loanRepository.findByBookId(bookId); }
                }
                """, props);

        Optional<FixProposal> out = r.resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "LoanRepository", null, ctx("com.example.library"));

        assertThat(out).isPresent();
        String content = out.get().mutations().get(0).content();
        assertThat(content)
                .contains("public interface LoanRepository")
                .contains("findByBookId");
        assertThat(out.get().record().description()).contains("usage-derived member");
    }

    @Test
    void synthesisOff_leavesSkeletonEmpty() throws Exception {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        // Same project, flag off (the default) — the skeleton stays the bare interface.
        IdiomSkeletonResolver r = resolverWithProject("com/example/library/service/LoanService.java", """
                package com.example.library.service;
                import com.example.library.repository.LoanRepository;
                public class LoanService {
                    private final LoanRepository loanRepository;
                    public Object find(Long bookId) { return loanRepository.findByBookId(bookId); }
                }
                """);

        Optional<FixProposal> out = r.resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "LoanRepository", null, ctx("com.example.library"));

        assertThat(out).isPresent();
        assertThat(out.get().mutations().get(0).content()).doesNotContain("findByBookId");
    }

    @Test
    void synthesisOn_skipsConstructorAlreadyInTemplate() throws Exception {
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        IdiomSkeletonConfigProperties props = new IdiomSkeletonConfigProperties();
        props.setSynthesizeMembers(true);
        // The exception template already declares (String message); a `new X(msg)` call site must
        // not add a duplicate 1-arg constructor.
        IdiomSkeletonResolver r = resolverWithProject("com/example/library/BookService.java", """
                package com.example.library;
                import com.example.library.exception.BookMissingException;
                public class BookService {
                    void f(String m) { throw new BookMissingException(m); }
                }
                """, props);

        Optional<FixProposal> out = r.resolve(
                cls(Kind.CANNOT_FIND_SYMBOL), "BookMissingException", null, ctx("com.example.library"));

        assertThat(out).isPresent();
        String content = out.get().mutations().get(0).content();
        int ctorCount = content.split("public BookMissingException\\(", -1).length - 1;
        assertThat(ctorCount).as("no duplicate single-arg constructor").isEqualTo(1);
    }
}
