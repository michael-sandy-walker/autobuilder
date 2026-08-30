package com.walpro.autobuilder.core.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;

/**
 * What the mover refuses, and what it has to repair when it does move.
 *
 * <p>The refusals matter more than the moves. A relocation rewrites a type's package declaration
 * and every reference to it, so each condition here is a case where the project has not given
 * enough evidence to conclude where the type belongs — and the answer is to leave it alone rather
 * than approximate.
 */
class PeerPlacementRelocationTest {

    private final WiringFactsAnalyzer analyzer = new WiringFactsAnalyzer();

    private static final String SRC = "src/main/java/";

    /** A tree with the anomaly: three peers nominate three types, two of which agree on a home. */
    private static Map<String, String> anomalous(String base, String entryPointPackage) {
        Map<String, String> s = new LinkedHashMap<>();
        String dir = SRC + base.replace('.', '/') + "/";
        if (entryPointPackage != null) {
            s.put(SRC + entryPointPackage.replace('.', '/') + "/Application.java", """
                    package %s;

                    public class Application {
                        public static void main(String[] args) {}
                    }
                    """.formatted(entryPointPackage));
        }
        s.put(dir + "Book.java", "package " + base + ";\n\npublic class Book {}\n");
        s.put(dir + "model/Loan.java", "package " + base + ".model;\n\npublic class Loan {}\n");
        s.put(dir + "model/Member.java", "package " + base + ".model;\n\npublic class Member {}\n");
        s.put(dir + "repository/BookRepository.java",
                "package " + base + ".repository;\n\nimport " + base + ".Book;\n\n"
                        + "public class BookRepository { Book b; }\n");
        s.put(dir + "repository/LoanRepository.java",
                "package " + base + ".repository;\n\nimport " + base + ".model.Loan;\n\n"
                        + "public class LoanRepository { Loan l; }\n");
        s.put(dir + "repository/MemberRepository.java",
                "package " + base + ".repository;\n\nimport " + base + ".model.Member;\n\n"
                        + "public class MemberRepository { Member m; }\n");
        return s;
    }

    private java.util.List<PeerPlacementRelocation.Relocation> planFor(Map<String, String> sources) {
        return PeerPlacementRelocation.plan(sources, analyzer.peerPlacementFindings(sources));
    }

    @Test
    void aProjectWithNoEntryPointIsNeverMoved() {
        Map<String, String> sources = anomalous("com.example.domain", null);

        assertThat(analyzer.peerPlacementFindings(sources)).hasSize(1);
        assertThat(planFor(sources)).isEmpty();
    }

    @Test
    void aDestinationOutsideTheEntryPointsOwnSubtreeIsNeverMoved() {
        Map<String, String> sources = anomalous("com.other.domain", "com.example.app");

        assertThat(analyzer.peerPlacementFindings(sources)).hasSize(1);
        assertThat(planFor(sources)).isEmpty();
    }

    @Test
    void anOccupiedDestinationIsNeverOverwritten() {
        Map<String, String> sources = anomalous("com.example.domain", "com.example.domain");
        sources.put(SRC + "com/example/domain/model/Book.java",
                "package com.example.domain.model;\n\npublic class Book {}\n");

        assertThat(planFor(sources)).isEmpty();
    }

    @Test
    void aFileLeftBehindInTheOldPackageGainsTheImportTheMoveTakesAway() {
        Map<String, String> sources = anomalous("com.example.domain", "com.example.domain");
        // Same package as Book, so it never needed an import — until now.
        sources.put(SRC + "com/example/domain/Shelf.java",
                "package com.example.domain;\n\npublic class Shelf { Book held; }\n");

        var applied = PeerPlacementRelocation.apply(sources, planFor(sources).get(0)).orElseThrow();

        assertThat(applied.writes().get(SRC + "com/example/domain/Shelf.java"))
                .contains("import com.example.domain.model.Book;");
    }

    @Test
    void theMovedFileGainsImportsForWhatItLeavesBehind() {
        Map<String, String> sources = anomalous("com.example.domain", "com.example.domain");
        sources.put(SRC + "com/example/domain/Shelf.java",
                "package com.example.domain;\n\npublic class Shelf {}\n");
        sources.put(SRC + "com/example/domain/Book.java",
                "package com.example.domain;\n\npublic class Book { Shelf shelf; }\n");

        var applied = PeerPlacementRelocation.apply(sources, planFor(sources).get(0)).orElseThrow();

        assertThat(applied.writes().get(SRC + "com/example/domain/model/Book.java"))
                .contains("import com.example.domain.Shelf;");
    }

    @Test
    void aTypeWhoseNameMerelyStartsWithTheMovedOneIsNotDraggedAlong() {
        Map<String, String> sources = anomalous("com.example.domain", "com.example.domain");
        sources.put(SRC + "com/example/domain/BookKeeper.java",
                "package com.example.domain;\n\npublic class BookKeeper {}\n");
        // Names two project types, so it nominates nothing and the peer group is unchanged.
        sources.put(SRC + "com/example/domain/util/Registry.java",
                "package com.example.domain.util;\n\npublic class Registry {\n"
                        + "    com.example.domain.Book book;\n"
                        + "    com.example.domain.BookKeeper keeper;\n}\n");

        var applied = PeerPlacementRelocation.apply(sources, planFor(sources).get(0)).orElseThrow();

        String registry = applied.writes().get(SRC + "com/example/domain/util/Registry.java");
        assertThat(registry).contains("com.example.domain.model.Book book;");
        assertThat(registry).contains("com.example.domain.BookKeeper keeper;");
    }

    @Test
    void aPlanMadeBeforeTheTypeMovedDoesNothingWhenReapplied() {
        Map<String, String> sources = anomalous("com.example.domain", "com.example.domain");
        var relocation = planFor(sources).get(0);
        var applied = PeerPlacementRelocation.apply(sources, relocation).orElseThrow();
        Map<String, String> after = new LinkedHashMap<>(sources);
        after.remove(applied.vacatedPath());
        after.putAll(applied.writes());

        assertThat(PeerPlacementRelocation.apply(after, relocation)).isEmpty();
    }

    @Test
    void anImportThatBecomesSamePackageNoiseIsDropped() {
        Map<String, String> sources = anomalous("com.example.domain", "com.example.domain");
        sources.put(SRC + "com/example/domain/model/Catalogue.java",
                "package com.example.domain.model;\n\nimport com.example.domain.Book;\n\n"
                        + "public class Catalogue { Book b; }\n");

        var applied = PeerPlacementRelocation.apply(sources, planFor(sources).get(0)).orElseThrow();

        assertThat(applied.writes().get(SRC + "com/example/domain/model/Catalogue.java"))
                .doesNotContain("import com.example.domain.model.Book;")
                .contains("public class Catalogue { Book b; }");
    }
}
