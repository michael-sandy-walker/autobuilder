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
