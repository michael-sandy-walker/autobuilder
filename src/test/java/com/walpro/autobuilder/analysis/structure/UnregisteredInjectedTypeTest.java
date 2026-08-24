package com.walpro.autobuilder.analysis.structure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * T3-CA: the type that carries NOTHING. T3-BW's rule only considers types that already have an
 * annotation, so it is structurally blind to the case where a type has none while its peers do —
 * which is exactly the case where the type is never registered and the application cannot start.
 *
 * <p>The tree below is run 2026-08-22_06-19-55 reconstructed from its own renders: that run ended
 * boot-failed on "No qualifying bean of type … BookRepository", and every rule in this analyzer
 * returned empty on it.
 */
class UnregisteredInjectedTypeTest {

    private final WiringFactsAnalyzer analyzer = new WiringFactsAnalyzer();

    private static Map<String, String> tree(String... srcs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < srcs.length; i++) m.put("F" + i + ".java", srcs[i]);
        return m;
    }

    private static String repository(String name, boolean annotated) {
        return "package com.example.library.domain.repository;\n"
                + (annotated ? "@Repository\n" : "")
                + "public class " + name + " { }\n";
    }

    private static final String SERVICE = """
            package com.example.library.service;
            import com.example.library.domain.repository.BookRepository;
            @Service
            public class BookService {
                private final BookRepository bookRepository;
                public BookService(BookRepository bookRepository) { this.bookRepository = bookRepository; }
            }
            """;

    @Test
    void reportsTheInjectedTypeThatCarriesNoAnnotationWhileItsPeersDo() {
        List<String> obs = analyzer.peerAnnotationAnomalies(tree(
                repository("BookRepository", false),
                repository("MemberRepository", true),
                repository("LoanRepository", true),
                SERVICE));

        assertThat(obs).hasSize(1);
        assertThat(obs.get(0))
                .contains("BookRepository")
                .contains("BookService")
                .contains("Repository");
    }

    @Test
    void silentOnceTheTypeCarriesWhatItsPeersCarry() {
        assertThat(analyzer.peerAnnotationAnomalies(tree(
                repository("BookRepository", true),
                repository("MemberRepository", true),
                repository("LoanRepository", true),
                SERVICE))).isEmpty();
    }

    @Test
    void anUnannotatedTypeThatIsNeverInjectedIsNotAnAnomaly() {
        // Models, DTOs and exceptions legitimately carry nothing. Being constructor-injected into
        // an annotated type is what turns "unannotated" from a style choice into a contradiction.
        assertThat(analyzer.peerAnnotationAnomalies(tree(
                repository("BookRepository", false),
                repository("MemberRepository", true),
                repository("LoanRepository", true)))).isEmpty();
    }

    @Test
    void silentWhenTheInjectingTypeIsItselfPlain() {
        String plainCaller = """
                package com.example.library.service;
                import com.example.library.domain.repository.BookRepository;
                public class BookService {
                    public BookService(BookRepository bookRepository) { }
                }
                """;
        assertThat(analyzer.peerAnnotationAnomalies(tree(
                repository("BookRepository", false),
                repository("MemberRepository", true),
                repository("LoanRepository", true),
                plainCaller))).isEmpty();
    }

    @Test
    void silentWhenThePeersDoNotAgree() {
        // One dissenter makes it a style choice, not a norm — the same bar the sibling rules use.
        String odd = "package com.example.library.domain.repository;\n@Component\npublic class LoanRepository { }\n";
        assertThat(analyzer.peerAnnotationAnomalies(tree(
                repository("BookRepository", false),
                repository("MemberRepository", true),
                odd,
                SERVICE))).isEmpty();
    }

    @Test
    void silentOnAProjectThatAnnotatesNothing() {
        assertThat(analyzer.peerAnnotationAnomalies(tree(
                repository("BookRepository", false),
                repository("MemberRepository", false),
                repository("LoanRepository", false),
                """
                package com.example.library.service;
                public class BookService {
                    public BookService(BookRepository bookRepository) { }
                }
                """))).isEmpty();
    }

    // --- the two exemptions, both found by replaying real trees rather than by reasoning -----

    @Test
    void silentWhenAnAnnotatedTypeBuildsItItself() {
        // Run 2026-08-22_04-48-23 scored 23/23 with an unannotated LoanRepository, because a
        // configuration type constructed all three repositories. A factory hands the instance
        // over ready-made, so the type's own annotations are irrelevant. Without this exemption
        // the rule contradicts a working artifact.
        String factory = """
                package com.example.library.config;
                import com.example.library.domain.repository.BookRepository;
                @Configuration
                public class RepositoryConfig {
                    @Bean
                    public BookRepository bookRepository() { return new BookRepository(); }
                }
                """;
        assertThat(analyzer.peerAnnotationAnomalies(tree(
                repository("BookRepository", false),
                repository("MemberRepository", true),
                repository("LoanRepository", true),
                SERVICE,
                factory))).isEmpty();
    }

    @Test
    void silentOnAnInterfaceTheProjectNeverImplements() {
        // Run 2026-08-24_00-07-06: a bare interface extending a library supertype, beside two
        // annotated siblings. Its implementation comes from outside the project, so the project's
        // annotation habits say nothing about whether it is available.
        String iface = """
                package com.example.library.domain.repository;
                public interface BookRepository extends JpaRepository<Object, Long> { }
                """;
        assertThat(analyzer.peerAnnotationAnomalies(tree(
                iface,
                repository("MemberRepository", true),
                repository("LoanRepository", true),
                SERVICE))).isEmpty();
    }

    @Test
    void theFactNamesNoFrameworkOfItsOwn() {
        String fact = analyzer.peerAnnotationAnomalies(tree(
                repository("BookRepository", false),
                repository("MemberRepository", true),
                repository("LoanRepository", true),
                SERVICE)).get(0);

        // Every framework word in the sentence must have come from the artifact's own source.
        assertThat(fact).doesNotContain("Spring").doesNotContain("bean").doesNotContain("stereotype");
    }
}
