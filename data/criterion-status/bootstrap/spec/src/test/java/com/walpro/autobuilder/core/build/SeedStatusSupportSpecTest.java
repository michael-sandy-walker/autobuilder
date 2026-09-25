package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.SeedStatusSupport.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The specification of {@link SeedStatusSupport#of}.
 *
 * <p>The criteria are not written here. {@code pattern-criteria.tsv} is every distinct criterion in
 * the orchestration graph whose endpoint names its input as a pattern, exported with its label by
 * {@code scripts/pattern_criteria_corpus.py}: {@code success} (asserts a status in 200-299),
 * {@code error} (asserts one outside it), {@code none} (asserts no status), and whether the criterion
 * parser reads exactly one endpoint and one status from it. {@code library-seed.txt} is the
 * specification those criteria were written against.
 *
 * <p>Against that specification, which names the endpoint and states no status for it, a success
 * criterion is supported and an error criterion is not. The variants change only the endpoint's own
 * clause in the specification, and the same criteria must follow the specification.
 */
class SeedStatusSupportSpecTest {

    private static final String CLAUSE = "(list all loans for a member, active and returned)";

    private static String seed() {
        String s = resource("library-seed.txt");
        if (!s.contains(CLAUSE)) throw new IllegalStateException("the library seed no longer carries " + CLAUSE);
        return s;
    }

    /** The library seed, with the pattern endpoint's clause stating {@code extra} as well. */
    private static String seedStating(String extra) {
        return seed().replace(CLAUSE, CLAUSE.substring(0, CLAUSE.length() - 1) + "; " + extra + ")");
    }

    private record Labelled(String label, String status, boolean parses, String text) {
    }

    private static List<Labelled> corpus() {
        return resource("pattern-criteria.tsv").lines().filter(l -> !l.isBlank()).map(l -> {
            String[] f = l.split("\t", 4);
            return new Labelled(f[0], f[1], f[2].equals("yes"), f[3]);
        }).toList();
    }

    private static String resource(String name) {
        try (InputStream in = SeedStatusSupportSpecTest.class.getResourceAsStream("/seed-status/" + name)) {
            if (in == null) throw new IllegalStateException("missing test resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Stream<Arguments> labelled() {
        return corpus().stream().map(c -> Arguments.of(c.label() + " " + c.status(), c.text(),
                !c.parses() ? Verdict.NO_OPINION
                        : c.label().equals("success") ? Verdict.SUPPORTED
                        : c.label().equals("error") ? Verdict.UNSUPPORTED
                        : Verdict.NO_OPINION));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("labelled")
    void theLibrarySeed_supportsSuccessAndNotAnErrorItNeverStates(String label, String criterion, Verdict expected) {
        assertThat(SeedStatusSupport.of(criterion, seed())).isEqualTo(expected);
    }

    /** The clause states 404: a 404 criterion now follows from the seed, a 400 still does not. */
    private static Stream<Arguments> withA404Stated() {
        return corpus().stream().filter(Labelled::parses).map(c -> Arguments.of(c.label() + " " + c.status(), c.text(),
                c.status().equals("404") || c.label().equals("success") ? Verdict.SUPPORTED : Verdict.UNSUPPORTED));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("withA404Stated")
    void aStatedErrorStatus_isSupported_andOnlyThatOne(String label, String criterion, Verdict expected) {
        assertThat(SeedStatusSupport.of(criterion, seedStating("404 if the member does not exist"))).isEqualTo(expected);
    }

    /** The clause states a success status of its own: a criterion asserting a different one does not follow. */
    private static Stream<Arguments> withA206Stated() {
        return corpus().stream().filter(Labelled::parses).map(c -> Arguments.of(c.label() + " " + c.status(), c.text(),
                Verdict.UNSUPPORTED));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("withA206Stated")
    void aStatedSuccessStatus_excludesAnotherSuccessStatus(String label, String criterion, Verdict expected) {
        assertThat(SeedStatusSupport.of(criterion, seedStating("206 with a partial list"))).isEqualTo(expected);
    }

    // The rule names the specification, never a status, a verb or a domain term: the same rule over
    // an invented vocabulary.
    private static final String COILS = """
            Coils: POST /coils (register a coil; emitterId required; 423 if the emitter is sealed),
            GET /coils?emitterId={id} (list the coils on an emitter).
            """;

    @Test
    void anInventedVocabulary_followsTheSameRule() {
        assertThat(SeedStatusSupport.of("GET /coils?emitterId={id} returns HTTP 200 with the coils on the emitter", COILS))
                .isEqualTo(Verdict.SUPPORTED);
        assertThat(SeedStatusSupport.of("GET /coils?emitterId={id} returns HTTP 404 when the emitter does not exist", COILS))
                .isEqualTo(Verdict.UNSUPPORTED);
        assertThat(SeedStatusSupport.of("POST /coils returns HTTP 423 when the emitter is sealed", COILS))
                .isEqualTo(Verdict.SUPPORTED);
        assertThat(SeedStatusSupport.of("POST /coils returns HTTP 409 when the coil is already registered", COILS))
                .isEqualTo(Verdict.UNSUPPORTED);
    }

    @Test
    void anEndpointTheSpecificationNeverNames_isUnsupported() {
        assertThat(SeedStatusSupport.of("GET /emitters/{id} returns HTTP 200 with the emitter", COILS))
                .isEqualTo(Verdict.UNSUPPORTED);
    }

    @Test
    void aCriterionWithoutOneEndpointAndOneStatus_hasNoOpinion() {
        assertThat(SeedStatusSupport.of("The coils are listed in registration order", COILS)).isEqualTo(Verdict.NO_OPINION);
        assertThat(SeedStatusSupport.of("GET /coils?emitterId={id} lists the coils in registration order", COILS))
                .isEqualTo(Verdict.NO_OPINION);
        assertThat(SeedStatusSupport.of("POST /coils returns HTTP 201 and GET /coils?emitterId={id} returns HTTP 200", COILS))
                .isEqualTo(Verdict.NO_OPINION);
    }

    @Test
    void noSpecificationOrNoCriterion_hasNoOpinion() {
        assertThat(SeedStatusSupport.of("POST /coils returns HTTP 423 when the emitter is sealed", "")).isEqualTo(Verdict.NO_OPINION);
        assertThat(SeedStatusSupport.of("POST /coils returns HTTP 423 when the emitter is sealed", null)).isEqualTo(Verdict.NO_OPINION);
        assertThat(SeedStatusSupport.of(null, COILS)).isEqualTo(Verdict.NO_OPINION);
        assertThat(SeedStatusSupport.of("   ", COILS)).isEqualTo(Verdict.NO_OPINION);
    }
}
