package com.walpro.autobuilder.analysis.structure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultProjectStructureSummarizerTest {

    private Path tempDir;
    private DefaultProjectStructureSummarizer summarizer;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("test-output");
        FileService fileService = new FileService(tempDir.toString(), new ObjectMapper());
        summarizer = new DefaultProjectStructureSummarizer(fileService);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void detectBasePackage_returnsNull_whenNoJavaFiles() {
        assertThat(summarizer.detectBasePackage(tempDir)).isNull();
    }

    @Test
    void detectBasePackage_returnsSinglePackage_whenOneJavaFile() throws IOException {
        Files.writeString(tempDir.resolve("Foo.java"),
                "package com.example.library;\npublic class Foo {}");

        assertThat(summarizer.detectBasePackage(tempDir)).isEqualTo("com.example.library");
    }

    @Test
    void detectBasePackage_returnsCommonPrefix_whenFilesHaveDifferentSubpackages() throws IOException {
        Files.writeString(tempDir.resolve("Foo.java"),
                "package com.example.library;\npublic class Foo {}");
        Path serviceDir = Files.createDirectories(tempDir.resolve("service"));
        Files.writeString(serviceDir.resolve("FooService.java"),
                "package com.example.library.service;\npublic class FooService {}");

        assertThat(summarizer.detectBasePackage(tempDir)).isEqualTo("com.example.library");
    }

    @Test
    void detectBasePackage_returnsNull_whenPackagesShareNoCommonSegment() throws IOException {
        Files.writeString(tempDir.resolve("Foo.java"),
                "package com.example.library;\npublic class Foo {}");
        Files.writeString(tempDir.resolve("Bar.java"),
                "package org.different;\npublic class Bar {}");

        assertThat(summarizer.detectBasePackage(tempDir)).isNull();
    }

    @Test
    void detectBasePackage_returnsNull_whenJavaFilesHaveNoPackageDeclaration() throws IOException {
        Files.writeString(tempDir.resolve("Unpackaged.java"),
                "public class Unpackaged {}");

        assertThat(summarizer.detectBasePackage(tempDir)).isNull();
    }

    @Test
    void detectBasePackage_ignoresNonJavaFiles() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "some readme");
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        assertThat(summarizer.detectBasePackage(tempDir)).isNull();
    }

    @Test
    void detectBasePackage_returnsCommonPrefix_acrossMultipleDepthLevels() throws IOException {
        Path controllerDir = Files.createDirectories(tempDir.resolve("controller"));
        Path modelDir = Files.createDirectories(tempDir.resolve("model"));
        Files.writeString(controllerDir.resolve("TaskController.java"),
                "package com.example.library.controller;\npublic class TaskController {}");
        Files.writeString(modelDir.resolve("Task.java"),
                "package com.example.library.model;\npublic class Task {}");
        Files.writeString(tempDir.resolve("LibraryApp.java"),
                "package com.example.library;\npublic class LibraryApp {}");

        assertThat(summarizer.detectBasePackage(tempDir)).isEqualTo("com.example.library");
    }

    // ------------------------------------------------------------------ //
    // T2-BX: summarizeContent — annotation-aware in-memory summary        //
    // ------------------------------------------------------------------ //

    @Test
    void summarizeContent_exceptionHandlerMethod_prefixedWithAnnotation() {
        String content = """
                @RestControllerAdvice
                public class GlobalExceptionHandler {

                    @ExceptionHandler(ResourceNotFoundException.class)
                    public ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex) {
                        return ResponseEntity.notFound().build();
                    }
                }
                """;

        List<String> result = DefaultProjectStructureSummarizer.summarizeContent(content);

        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .contains("@ExceptionHandler(ResourceNotFoundException.class)")
                .contains("→ handleNotFound")
                .contains(": ResponseEntity");
    }

    @Test
    void summarizeContent_getMapping_prefixedWithAnnotation() {
        String content = """
                @RestController
                public class LoanController {

                    @GetMapping("/loans")
                    public List<LoanEntity> getAllLoans() {
                        return loanService.findAll();
                    }
                }
                """;

        List<String> result = DefaultProjectStructureSummarizer.summarizeContent(content);

        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .contains("@GetMapping(\"/loans\")")
                .contains("→ getAllLoans")
                .contains(": List");
    }

    @Test
    void summarizeContent_httpMappingWithoutParams_prefixedWithAnnotationNoParens() {
        String content = """
                @RestController
                public class LoanController {

                    @PostMapping
                    public LoanEntity createLoan(String memberId) {
                        return null;
                    }
                }
                """;

        List<String> result = DefaultProjectStructureSummarizer.summarizeContent(content);

        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .contains("@PostMapping")
                .contains("→ createLoan");
    }

    @Test
    void summarizeContent_plainPublicMethod_noPrefixAnnotation() {
        String content = """
                public class LoanService {

                    public LoanEntity createLoan(String memberId, String bookId) {
                        return new LoanEntity();
                    }

                    public void returnLoan(String loanId) {
                    }
                }
                """;

        List<String> result = DefaultProjectStructureSummarizer.summarizeContent(content);

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(l -> assertThat(l).contains("createLoan").contains(": LoanEntity").doesNotContain("→"));
        assertThat(result).anySatisfy(l -> assertThat(l).contains("returnLoan").contains(": void").doesNotContain("→"));
    }

    @Test
    void summarizeContent_constructor_excluded() {
        String content = """
                public class LoanService {

                    public LoanService(LoanRepository loanRepository) {
                        this.repo = loanRepository;
                    }

                    public LoanEntity createLoan(String memberId) {
                        return null;
                    }
                }
                """;

        List<String> result = DefaultProjectStructureSummarizer.summarizeContent(content);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("createLoan");
        assertThat(result).noneMatch(l -> l.contains("LoanService("));
    }

    @Test
    void summarizeContent_emptyOrBlank_returnsEmptyList() {
        assertThat(DefaultProjectStructureSummarizer.summarizeContent("")).isEmpty();
        assertThat(DefaultProjectStructureSummarizer.summarizeContent(null)).isEmpty();
        assertThat(DefaultProjectStructureSummarizer.summarizeContent("   ")).isEmpty();
    }

    @Test
    void summarizeContent_multipleAnnotatedMethods_allCaptured() {
        String content = """
                @RestControllerAdvice
                public class GlobalExceptionHandler {

                    @ExceptionHandler(ResourceNotFoundException.class)
                    public ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex) {
                        return ResponseEntity.notFound().build();
                    }

                    @ExceptionHandler(BadRequestException.class)
                    public ResponseEntity<Object> handleBadRequest(BadRequestException ex) {
                        return ResponseEntity.badRequest().build();
                    }
                }
                """;

        List<String> result = DefaultProjectStructureSummarizer.summarizeContent(content);

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(l -> assertThat(l).contains("handleNotFound"));
        assertThat(result).anySatisfy(l -> assertThat(l).contains("handleBadRequest"));
    }

    @Test
    void summarizeContent_duplicateMethodName_appearsOnce() {
        String content = """
                public class FooService {
                    public Foo findById(String id) { return null; }
                    public Foo findById(Long id) { return null; }
                }
                """;

        List<String> result = DefaultProjectStructureSummarizer.summarizeContent(content);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("findById");
    }

    @Test
    void summarizeContent_staticPublicMethod_captured() {
        String content = """
                public class Utils {
                    public static String format(String input) { return input; }
                }
                """;

        List<String> result = DefaultProjectStructureSummarizer.summarizeContent(content);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("format").contains(": String");
    }
}
