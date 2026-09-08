package com.walpro.autobuilder.analysis.structure;

import com.walpro.autobuilder.analysis.structure.RequestBodyContractQuery.KeyedValues;
import com.walpro.autobuilder.analysis.structure.RequestBodyContractQuery.NamedType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-FV: what the interface ACCEPTS, read from its own declaration.
 *
 * <p>The shape under test is verbatim from run {@code 2026-08-28_21-47-39}, whose artifact scored
 * 23/23 and was refused closure by a single contradiction this reading removes: the handler
 * declares keyed {@code Long} values and the entity carries a {@code boolean}, so the derived body
 * was refused before the requirement was reached.
 */
class RequestBodyContractQueryTest {

    @TempDir
    Path artifact;

    private void source(String name, String content) throws IOException {
        Path p = artifact.resolve("src/main/java/com/example/" + name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    /** The observed case: a class-level prefix, a bare @PostMapping, and a keyed-value body. */
    @Test
    void readsAKeyedValueDeclarationFromTheHandlerItself() throws IOException {
        source("LoanController.java", """
                @RestController
                @RequestMapping("/loans")
                public class LoanController {
                    @PostMapping
                    public ResponseEntity<Loan> create(@RequestBody Map<String, Long> body) {
                        return null;
                    }
                }
                """);

        assertThat(RequestBodyContractQuery.acceptedBodyFor("/loans", artifact))
                .contains(new KeyedValues("Long"));
    }

    /** A declared request object names its own components, and it need not be the entity. */
    @Test
    void readsANamedRequestType() throws IOException {
        source("BookController.java", """
                @RestController
                public class BookController {
                    @PostMapping("/books")
                    public ResponseEntity<Book> create(@RequestBody CreateBookRequest request) {
                        return null;
                    }
                }
                """);

        assertThat(RequestBodyContractQuery.acceptedBodyFor("/books", artifact))
                .contains(new NamedType("CreateBookRequest"));
    }

    /**
     * The body parameter is found by its marker, not by position -- a handler takes path variables
     * and query parameters too, and either can sit first.
     */
    @Test
    void findsTheBodyParameterWhateverItsPosition() throws IOException {
        source("ItemController.java", """
                @RestController
                public class ItemController {
                    @PutMapping("/items/{id}")
                    public ResponseEntity<Item> update(@PathVariable Long id,
                                                       @RequestParam String mode,
                                                       @RequestBody ItemPatch patch) {
                        return null;
                    }
                }
                """);

        assertThat(RequestBodyContractQuery.acceptedBodyFor("/items/{id}", artifact))
                .contains(new NamedType("ItemPatch"));
    }

    /** A handler taking no body says nothing about bodies -- silence, not a guess. */
    @Test
    void aHandlerWithNoBodyParameterYieldsNoOpinion() throws IOException {
        source("ReportController.java", """
                @RestController
                public class ReportController {
                    @GetMapping("/reports")
                    public ResponseEntity<List<Report>> all() { return null; }
                }
                """);

        assertThat(RequestBodyContractQuery.acceptedBodyFor("/reports", artifact)).isEmpty();
    }

    /**
     * Two handlers on one path declaring DIFFERENT bodies: nothing here can say which the probe
     * will reach, so it declines rather than picking the first one it walked into.
     */
    @Test
    void disagreeingDeclarationsOnOnePathYieldNoOpinion() throws IOException {
        source("OneController.java", """
                @RestController
                public class OneController {
                    @PostMapping("/things")
                    public String create(@RequestBody ThingRequest r) { return null; }
                }
                """);
        source("TwoController.java", """
                @RestController
                public class TwoController {
                    @PutMapping("/things")
                    public String replace(@RequestBody Map<String, String> body) { return null; }
                }
                """);

        assertThat(RequestBodyContractQuery.acceptedBodyFor("/things", artifact)).isEmpty();
    }

    /**
     * A mapping path that is not a literal cannot be grounded, so the unit it appears in is not
     * trusted to answer -- the same three-way reading {@link EndpointSurfaceQuery} makes, for the
     * same reason: an incomplete surface makes every comparison against it unsafe.
     */
    @Test
    void anUngroundableMappingPathYieldsNoOpinion() throws IOException {
        source("ConstController.java", """
                @RestController
                @RequestMapping(BASE_PATH)
                public class ConstController {
                    @PostMapping
                    public String create(@RequestBody Map<String, Long> body) { return null; }
                }
                """);

        assertThat(RequestBodyContractQuery.acceptedBodyFor("/loans", artifact)).isEmpty();
    }

    /**
     * A single type argument is a SEQUENCE -- a different body shape altogether, and one this
     * class has no opinion on. Pinned so the two-argument reading is never widened by accident
     * into "take the last type argument of anything".
     */
    @Test
    void aSequenceBodyYieldsNoOpinion() throws IOException {
        source("BulkController.java", """
                @RestController
                public class BulkController {
                    @PostMapping("/bulk")
                    public String create(@RequestBody List<Item> items) { return null; }
                }
                """);

        assertThat(RequestBodyContractQuery.acceptedBodyFor("/bulk", artifact)).isEmpty();
    }
}
