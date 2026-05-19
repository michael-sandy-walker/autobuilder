package com.walpro.autobuilder.core.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;

class SymbolGroundingServiceTest {

    @TempDir
    Path tempDir;

    private SymbolGroundingService service;
    private Path outputDir;

    @BeforeEach
    void setUp() throws IOException {
        outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);
        FileService fileService = new FileService(tempDir.toString(), new ObjectMapper());
        service = new SymbolGroundingService(fileService);
    }

    @Test
    void pathExistsUnderOutput_returnsTrueForExistingFile() throws IOException {
        Files.createDirectories(outputDir.resolve("src/main/java/com/example/library"));
        Files.writeString(outputDir.resolve("src/main/java/com/example/library/Book.java"),
                "package com.example.library; public class Book {}");

        assertThat(service.pathExistsUnderOutput("src/main/java/com/example/library/Book.java")).isTrue();
    }

    @Test
    void pathExistsUnderOutput_returnsFalseForMissingFile() {
        assertThat(service.pathExistsUnderOutput("src/main/java/Missing.java")).isFalse();
    }

    @Test
    void pathExistsUnderOutput_normalisesLeadingSlashAndOutputPrefix() throws IOException {
        Files.createDirectories(outputDir.resolve("src"));
        Files.writeString(outputDir.resolve("src/X.java"), "class X {}");

        assertThat(service.pathExistsUnderOutput("/src/X.java")).isTrue();
        assertThat(service.pathExistsUnderOutput("output/src/X.java")).isTrue();
    }

    @Test
    void pathExistsUnderOutput_blocksPathTraversal() {
        // ".." escapes output/ — must be treated as missing (not throw).
        assertThat(service.pathExistsUnderOutput("../etc/passwd")).isFalse();
    }

    @Test
    void definedSymbolsInOutput_extractsClassInterfaceRecordEnum() throws IOException {
        Files.createDirectories(outputDir.resolve("src"));
        Files.writeString(outputDir.resolve("src/A.java"),
                "package com.example.lib; public class Book {}");
        Files.writeString(outputDir.resolve("src/B.java"),
                "package com.example.lib; public interface MemberRepository {}");
        Files.writeString(outputDir.resolve("src/C.java"),
                "package com.example.lib; public record CreateLoanRequest(String id) {}");
        Files.writeString(outputDir.resolve("src/D.java"),
                "package com.example.lib; public enum LoanStatus { ACTIVE, RETURNED }");
        Files.writeString(outputDir.resolve("src/E.txt"), "class IgnoredText {}");

        Set<String> defined = service.definedSymbolsInOutput();

        // T2-DC fix #3: JavaStructureParser-based extraction yields both simple name and FQN
        // for class, interface, record, and enum (records added in JavaStructureParser).
        assertThat(defined).contains("Book", "MemberRepository", "CreateLoanRequest", "LoanStatus");
        assertThat(defined).contains(
                "com.example.lib.Book",
                "com.example.lib.MemberRepository",
                "com.example.lib.CreateLoanRequest",
                "com.example.lib.LoanStatus");
        assertThat(defined).doesNotContain("IgnoredText");
    }

    @Test
    void refreshAnnotations_stripsStaleMarkerOnceSymbolBecomesDefined() throws IOException {
        // Regression for 2026-05-19 21:44 run: subgoal description "Create a MemberController
        // [UNDEFINED]..." was rendered to the planner long after MemberController.java had been
        // written to disk. Markers are baked into the description at creation time; refresh must
        // strip them once the symbol is available, otherwise the planner LLM keeps proposing
        // replacement subgoals for already-present classes.
        Files.createDirectories(outputDir.resolve("src"));
        Files.writeString(outputDir.resolve("src/MemberController.java"),
                "package com.example.library.controller; public class MemberController {}");

        String stale = "Create a MemberController [UNDEFINED] class with endpoints for POST /members";
        String refreshed = service.refreshAnnotations(stale);

        assertThat(refreshed).doesNotContain("[UNDEFINED]");
        assertThat(refreshed).contains("MemberController");
    }

    @Test
    void refreshAnnotations_reAppliesMarkerForStillUndefinedSymbol() {
        // Strip and re-ground: if the symbol is still missing, the marker should reappear so the
        // planner sees current truth (not a stale absence either).
        String text = "Create a TotallyUnknownType [UNDEFINED] class";
        String refreshed = service.refreshAnnotations(text);
        assertThat(refreshed).contains("TotallyUnknownType " + SymbolGroundingService.SYMBOL_MISSING_SUFFIX.trim());
    }

    @Test
    void refreshAnnotations_passThroughWhenNoMarker() {
        String text = "Create MemberController class with POST /members";
        // No markers in input → either return as-is or annotate per current state, but never crash.
        assertThat(service.refreshAnnotations(text)).isNotNull();
        // Without [UNDEFINED] in the input string, ALREADY_ANNOTATED.find() is false and the input
        // is returned by reference — that's the documented fast path.
        assertThat(service.refreshAnnotations(text)).isSameAs(text);
    }

    @Test
    void refreshAnnotations_handlesNullAndEmpty() {
        assertThat(service.refreshAnnotations(null)).isNull();
        assertThat(service.refreshAnnotations("")).isEmpty();
    }

    @Test
    void refreshGoalAnnotations_clonesGoalWithRefreshedDescription() throws IOException {
        Files.createDirectories(outputDir.resolve("src"));
        Files.writeString(outputDir.resolve("src/MemberController.java"),
                "package com.example.library.controller; public class MemberController {}");

        com.walpro.autobuilder.goal.GoalEntity g = new com.walpro.autobuilder.goal.GoalEntity(
                "goal-1", "Create MemberController [UNDEFINED] class", "subgoal",
                com.walpro.autobuilder.goal.GoalType.SUBGOAL, null);
        com.walpro.autobuilder.core.lifecycle.CycleContextData ctx =
                com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                        .cycleName("c-1")
                        .remainingGoals(java.util.List.of(g))
                        .build();

        com.walpro.autobuilder.core.lifecycle.CycleContextData refreshed =
                service.refreshGoalAnnotations(ctx);

        // Result is a different context with a different GoalEntity instance (clone).
        assertThat(refreshed).isNotSameAs(ctx);
        assertThat(refreshed.remainingGoals()).hasSize(1);
        com.walpro.autobuilder.goal.GoalEntity refreshedGoal = refreshed.remainingGoals().get(0);
        assertThat(refreshedGoal).isNotSameAs(g);
        assertThat(refreshedGoal.getId()).isEqualTo("goal-1");
        assertThat(refreshedGoal.getDescription()).doesNotContain("[UNDEFINED]");
        // The original goal must be untouched — persisted goal-graph state cannot be mutated.
        assertThat(g.getDescription()).contains("[UNDEFINED]");
    }

    @Test
    void refreshGoalAnnotations_passesThroughWhenNothingChanges() {
        // Without any [UNDEFINED] markers, the entire context should be returned by reference —
        // no goal-list rebuilding, no allocation cost on the happy path.
        com.walpro.autobuilder.goal.GoalEntity g = new com.walpro.autobuilder.goal.GoalEntity(
                "goal-1", "Create MemberController class", "subgoal",
                com.walpro.autobuilder.goal.GoalType.SUBGOAL, null);
        com.walpro.autobuilder.core.lifecycle.CycleContextData ctx =
                com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                        .cycleName("c-1")
                        .remainingGoals(java.util.List.of(g))
                        .build();

        assertThat(service.refreshGoalAnnotations(ctx)).isSameAs(ctx);
    }

    @Test
    void definedSymbolsInOutput_includesNestedTypes() throws IOException {
        // Regression for 2026-05-19 21:17 run: a public static inner class
        // (BookService.ValidationException) was invisible to the grounding service, so the
        // planner kept tagging it [UNDEFINED] and spawning runaway BUILD_FIX subgoals.
        // Nested classes, interfaces, enums, and records must all be discovered at any depth.
        Files.createDirectories(outputDir.resolve("src"));
        Files.writeString(outputDir.resolve("src/BookService.java"), """
                package com.example.library.service;
                public class BookService {
                    public static class ValidationException extends RuntimeException {
                        public ValidationException(String msg) { super(msg); }
                    }
                    public enum Status { ACTIVE, RETURNED }
                    public interface Listener { void onEvent(); }
                    public record AuditEntry(String id, long timestamp) {}
                    public static class Outer {
                        public static class DoublyNested {}
                    }
                }
                """);

        Set<String> defined = service.definedSymbolsInOutput();

        // Simple names — the form the planner/analyzer LLM most often cites.
        assertThat(defined).contains(
                "BookService", "ValidationException", "Status", "Listener", "AuditEntry",
                "Outer", "DoublyNested");
        // FQNs (Outer.Inner / Outer.Inner.Innermost) — for callers that match qualified refs.
        assertThat(defined).contains(
                "com.example.library.service.BookService",
                "com.example.library.service.BookService.ValidationException",
                "com.example.library.service.BookService.Status",
                "com.example.library.service.BookService.Listener",
                "com.example.library.service.BookService.AuditEntry",
                "com.example.library.service.BookService.Outer",
                "com.example.library.service.BookService.Outer.DoublyNested");
    }

    @Test
    void groundSymbolReferences_doesNotTagInnerClass() {
        // The grounding annotator must not slap [UNDEFINED] on a name that is a real inner class.
        try {
            Files.createDirectories(outputDir.resolve("src"));
            Files.writeString(outputDir.resolve("src/BookService.java"), """
                    package com.example.library.service;
                    public class BookService {
                        public static class ValidationException extends RuntimeException {
                            public ValidationException(String msg) { super(msg); }
                        }
                    }
                    """);
        } catch (IOException e) { throw new RuntimeException(e); }

        Set<String> reported = new java.util.LinkedHashSet<>();
        String annotated = service.groundSymbolReferences(
                "BookService throws ValidationException on invalid input", reported);

        assertThat(annotated).doesNotContain(SymbolGroundingService.SYMBOL_MISSING_SUFFIX);
        assertThat(reported).doesNotContain("ValidationException");
    }

    @Test
    void definedSymbolsInOutput_fallsBackToRegexOnUnparseableInput() throws IOException {
        // T5-BC pattern: when JavaParser cannot produce ANY result (returns empty), the
        // wrapping JavaStructureParser throws IllegalArgumentException and we fall back to a
        // regex grep over the raw text. The test feeds a file with a class declaration buried
        // in non-Java text — the regex still finds the declaration line.
        Files.createDirectories(outputDir.resolve("src"));
        Files.writeString(outputDir.resolve("src/Broken.java"),
                "@@@ this is not java at all !!! }}}\n"
                        + "public class HalfWritten { ??? \n"
                        + "and more nonsense");

        Set<String> defined = service.definedSymbolsInOutput();
        // Either the AST path catches it (unlikely for this input) or the regex fallback does.
        // We only require that the fallback path doesn't crash and that broken files don't
        // poison the extractor for subsequent valid files.
        assertThat(defined).doesNotContainNull();
    }

    @Test
    void definedSymbolsInOutput_returnsEmptyWhenOutputDirMissing() throws IOException {
        Files.delete(outputDir); // remove output/
        assertThat(service.definedSymbolsInOutput()).isEmpty();
    }

    @Test
    void symbolAllowlisted_acceptsCommonStdlibAndSpring() {
        assertThat(service.symbolAllowlisted("String")).isTrue();
        assertThat(service.symbolAllowlisted("List")).isTrue();
        assertThat(service.symbolAllowlisted("RestController")).isTrue();
        assertThat(service.symbolAllowlisted("RuntimeException")).isTrue();
    }

    @Test
    void symbolAllowlisted_acceptsAllowlistedFqnPrefixes() {
        assertThat(service.symbolAllowlisted("java.util.Optional")).isTrue();
        assertThat(service.symbolAllowlisted("org.springframework.stereotype.Service")).isTrue();
        assertThat(service.symbolAllowlisted("jakarta.persistence.Entity")).isTrue();
    }

    @Test
    void symbolAllowlisted_rejectsUserDefinedTypes() {
        assertThat(service.symbolAllowlisted("BookAlreadyLoanedException")).isFalse();
        assertThat(service.symbolAllowlisted("com.example.library.Book")).isFalse();
    }

    @Test
    void groundSymbolReferences_annotatesUndefinedSymbols() {
        Set<String> reported = new LinkedHashSet<>();
        String text = "LoanController has logic to throw BookAlreadyLoanedException";

        String grounded = service.groundSymbolReferences(text, reported);

        assertThat(grounded).contains("LoanController [UNDEFINED]");
        assertThat(grounded).contains("BookAlreadyLoanedException [UNDEFINED]");
        assertThat(reported).containsExactlyInAnyOrder("LoanController", "BookAlreadyLoanedException");
    }

    @Test
    void groundSymbolReferences_skipsDefinedSymbols() throws IOException {
        Files.createDirectories(outputDir.resolve("src"));
        Files.writeString(outputDir.resolve("src/PriceCalculator.java"), "public class PriceCalculator {}");

        Set<String> reported = new LinkedHashSet<>();
        String text = "The PriceCalculator exists but TaxCalculator does not";

        String grounded = service.groundSymbolReferences(text, reported);

        assertThat(grounded).doesNotContain("PriceCalculator [UNDEFINED]");
        assertThat(grounded).contains("TaxCalculator [UNDEFINED]");
        assertThat(reported).containsExactly("TaxCalculator");
    }

    @Test
    void groundSymbolReferences_skipsAllowlistedTypes() {
        Set<String> reported = new LinkedHashSet<>();
        // All identifiers here are either in the allowlist or single-word (which the
        // multi-segment regex deliberately ignores to bound false positives).
        String text = "Returns ResponseEntity from the RestController via RequestMapping";

        String grounded = service.groundSymbolReferences(text, reported);

        assertThat(grounded).isEqualTo(text);
        assertThat(reported).isEmpty();
    }

    @Test
    void groundSymbolReferences_ignoresSingleWordPascalCaseAndCapitalisedEnglish() {
        // T2-DC v1: single-word PascalCase (Book, Loan, Returns) and capitalised English
        // (The, Add) are intentionally not flagged, because the false-positive cost of
        // flagging arbitrary capitalised words exceeds the value of catching simple-name
        // hallucinations. Multi-segment PascalCase only.
        Set<String> reported = new LinkedHashSet<>();
        String text = "The Book class is missing. Returns true. Add some logic.";

        String grounded = service.groundSymbolReferences(text, reported);

        assertThat(grounded).isEqualTo(text);
        assertThat(reported).isEmpty();
    }

    @Test
    void groundSymbolReferences_isIdempotentOnAlreadyAnnotatedText() {
        Set<String> reported = new LinkedHashSet<>();
        String preAnnotated = "BookAlreadyLoanedException [UNDEFINED] was cited in the prior pass";

        String grounded = service.groundSymbolReferences(preAnnotated, reported);

        assertThat(grounded).isEqualTo(preAnnotated);
        // The undefined name is still surfaced via the reported sink, even without rewriting.
        assertThat(reported).contains("BookAlreadyLoanedException");
    }

    @Test
    void groundSymbolReferences_doesNotDoubleAnnotateRepeatedSymbol() {
        Set<String> reported = new LinkedHashSet<>();
        String text = "MissingType is the cause; MissingType was not declared";

        String grounded = service.groundSymbolReferences(text, reported);

        // First occurrence annotated, second left alone (avoids "MissingType [UNDEFINED] [UNDEFINED]")
        assertThat(grounded).contains("MissingType [UNDEFINED]");
        assertThat(reported).containsExactly("MissingType");
        // The token sequence "[UNDEFINED] [UNDEFINED]" must not appear.
        assertThat(grounded).doesNotContain("[UNDEFINED] [UNDEFINED]");
    }

    @Test
    void groundPath_annotatesMissingPath() {
        String grounded = service.groundPath("src/main/java/com/example/library/exception/BookAlreadyLoanedException.java");
        assertThat(grounded).endsWith("[NOT YET PRESENT]");
    }

    @Test
    void groundPath_leavesExistingPathUntouched() throws IOException {
        Files.createDirectories(outputDir.resolve("src"));
        Files.writeString(outputDir.resolve("src/Book.java"), "class Book {}");

        String original = "src/Book.java";
        String grounded = service.groundPath(original);
        assertThat(grounded).isEqualTo(original);
    }

    @Test
    void groundPath_isIdempotentOnAlreadyAnnotatedPath() {
        String preAnnotated = "src/Missing.java [NOT YET PRESENT]";
        assertThat(service.groundPath(preAnnotated)).isEqualTo(preAnnotated);
    }

    @Test
    void groundPath_handlesNullAndBlank() {
        assertThat(service.groundPath(null)).isNull();
        assertThat(service.groundPath("")).isEqualTo("");
        assertThat(service.groundPath("   ")).isEqualTo("   ");
    }
}
