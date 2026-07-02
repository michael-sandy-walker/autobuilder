package com.walpro.autobuilder.analysis.structure;

import static org.assertj.core.api.Assertions.assertThat;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery.Endpoint;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EndpointSurfaceQueryTest {

    private final EndpointSurfaceQuery query = new EndpointSurfaceQuery();

    private static final String SPEC = """
            Build a library REST service.
            Endpoints: POST /books creates a book, GET /books lists, GET /books/{id} fetches one.
            POST /loans creates a loan; POST /loans/{id}/return returns it.
            GET /loans?memberId=42 lists a member's loans.
            """;

    private static final String BOOK_CONTROLLER = """
            package a;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/books")
            public class BookController {
                @PostMapping
                public String create(@RequestBody String b) { return b; }
                @GetMapping
                public String list() { return "[]"; }
                @GetMapping("/{id}")
                public String one(@PathVariable Long id) { return "b"; }
            }
            """;

    @Test
    void declaredInSpec_parsesVerbQualifiedPathsNormalized() {
        var declared = query.declaredInSpec(SPEC);
        assertThat(declared).contains(
                new Endpoint("POST", "/books"),
                new Endpoint("GET", "/books"),
                new Endpoint("GET", "/books/{}"),
                new Endpoint("POST", "/loans"),
                new Endpoint("POST", "/loans/{}/return"),
                // query string stripped
                new Endpoint("GET", "/loans"));
    }

    @Test
    void builtInOutput_combinesClassPrefixWithMethodMappings() {
        var built = query.builtInOutput(Map.of("src/main/java/BookController.java", BOOK_CONTROLLER));
        assertThat(built).isPresent();
        assertThat(built.get()).contains(
                new Endpoint("POST", "/books"),
                new Endpoint("GET", "/books"),
                new Endpoint("GET", "/books/{}"));
    }

    @Test
    void missingEndpoints_reportsSpecNamedEndpointsWithoutMappings() {
        List<Endpoint> missing = query.missingEndpoints(SPEC,
                Map.of("src/main/java/BookController.java", BOOK_CONTROLLER));
        assertThat(missing).contains(
                new Endpoint("POST", "/loans"),
                new Endpoint("POST", "/loans/{}/return"),
                new Endpoint("GET", "/loans"));
        assertThat(missing).doesNotContain(new Endpoint("POST", "/books"));
    }

    @Test
    void missingEndpoints_emptyWhenAllCovered() {
        String loanController = """
                package a;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/loans")
                public class LoanController {
                    @PostMapping
                    public String create(@RequestBody String l) { return l; }
                    @PostMapping("/{id}/return")
                    public String ret(@PathVariable Long id) { return "ok"; }
                    @GetMapping
                    public String list(@RequestParam Long memberId) { return "[]"; }
                }
                """;
        List<Endpoint> missing = query.missingEndpoints(SPEC, Map.of(
                "src/main/java/BookController.java", BOOK_CONTROLLER,
                "src/main/java/LoanController.java", loanController));
        assertThat(missing).isEmpty();
    }

    @Test
    void genericRequestMappingOnMethod_countsAsAnyVerb() {
        String controller = """
                package a;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class C {
                    @RequestMapping("/books")
                    public String any() { return "x"; }
                }
                """;
        List<Endpoint> missing = query.missingEndpoints(
                "POST /books and GET /books", Map.of("C.java", controller));
        assertThat(missing).isEmpty();
    }

    @Test
    void nonLiteralMappingPath_makesTheQueryAbstain() {
        String controller = """
                package a;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class C {
                    static final String PATH = "/books";
                    @GetMapping(C.PATH)
                    public String list() { return "[]"; }
                }
                """;
        assertThat(query.missingEndpoints("GET /books and POST /loans", Map.of("C.java", controller)))
                .isEmpty();
    }

    @Test
    void specWithoutVerbQualifiedEndpoints_selfGates() {
        assertThat(query.missingEndpoints("Build a calculator library with add and subtract.",
                Map.of("A.java", "public class A {}"))).isEmpty();
    }

    @Test
    void unavailableOutput_yieldsNoOpinion() {
        assertThat(query.missingEndpoints(SPEC, null)).isEmpty();
        assertThat(query.missingEndpoints(SPEC, Map.of())).isEmpty();
    }

    @Test
    void workItems_renderVerbAndPath() {
        List<String> items = query.missingEndpointWorkItems(
                "POST /loans creates a loan", Map.of("A.java", "public class A {}"));
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).contains("POST /loans").contains("no request mapping");
    }

    @Test
    void normalizePath_handlesVariablesQueryStringsAndSlashes() {
        assertThat(EndpointSurfaceQuery.normalizePath("/loans?memberId=42")).isEqualTo("/loans");
        assertThat(EndpointSurfaceQuery.normalizePath("/books/{bookId}")).isEqualTo("/books/{}");
        assertThat(EndpointSurfaceQuery.normalizePath("books//list/")).isEqualTo("/books/list");
        assertThat(EndpointSurfaceQuery.normalizePath("/")).isNull();
        assertThat(EndpointSurfaceQuery.normalizePath("  ")).isNull();
    }
}
