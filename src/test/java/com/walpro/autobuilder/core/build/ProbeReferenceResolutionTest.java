package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CU slice 2: grounding {@code $N.field} against recorded responses.
 *
 * <p>A criterion about a resource that must EXIST cannot name its id in advance — the server
 * assigns it. Everything here exists so that an unresolvable reference produces an ABSTAIN rather
 * than a guess: inventing an id would manufacture a contradiction against a correct artifact,
 * which is the more damaging error of the two.
 */
class ProbeReferenceResolutionTest {

    private static CriterionProbeSynthesizer.ProbeRequest req(String method, String path, String body) {
        return new CriterionProbeSynthesizer.ProbeRequest(method, path, body);
    }

    @Test
    void resolvesAnIdFromAnEarlierResponseIntoThePath() {
        var resolved = BootGate.resolveReferences(
                req("GET", "/books/$1.id", null),
                List.of("{\"id\":42,\"title\":\"Dune\"}"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().path()).isEqualTo("/books/42");
    }

    @Test
    void resolvesReferencesInsideABody() {
        var resolved = BootGate.resolveReferences(
                req("POST", "/loans", "{\"bookId\":$1.id,\"memberId\":$2.id}"),
                List.of("{\"id\":7}", "{\"id\":9}"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().body()).isEqualTo("{\"bookId\":7,\"memberId\":9}");
    }

    @Test
    void aRequestWithoutReferencesPassesThroughUnchanged() {
        var resolved = BootGate.resolveReferences(req("POST", "/books", "{\"title\":\"x\"}"), List.of());
        assertThat(resolved).isPresent();
        assertThat(resolved.get().path()).isEqualTo("/books");
        assertThat(resolved.get().body()).isEqualTo("{\"title\":\"x\"}");
    }

    @Test
    void anUnresolvableReferenceAbstains_ratherThanGuessing() {
        // Field absent from the response: the setup did not produce what the probe expected.
        assertThat(BootGate.resolveReferences(
                req("GET", "/books/$1.id", null), List.of("{\"title\":\"no id here\"}"))).isEmpty();

        // Response index out of range: the setup request never ran.
        assertThat(BootGate.resolveReferences(
                req("GET", "/books/$2.id", null), List.of("{\"id\":1}"))).isEmpty();

        // The setup response was not JSON at all (an error page, say).
        assertThat(BootGate.resolveReferences(
                req("GET", "/books/$1.id", null), List.of("<html>500</html>"))).isEmpty();

        // Nothing recorded yet.
        assertThat(BootGate.resolveReferences(
                req("GET", "/books/$1.id", null), List.of())).isEmpty();
    }

    @Test
    void aNullFieldIsNotAUsableId() {
        assertThat(BootGate.resolveReferences(
                req("GET", "/books/$1.id", null), List.of("{\"id\":null}"))).isEmpty();
    }

    @Test
    void readsTopLevelFieldsOfSeveralTypes() {
        assertThat(BootGate.jsonField("{\"id\":42}", "id")).contains("42");
        assertThat(BootGate.jsonField("{\"id\":\"abc\"}", "id")).contains("abc");
        assertThat(BootGate.jsonField("{\"nested\":{\"id\":1}}", "id")).isEmpty();
        assertThat(BootGate.jsonField("", "id")).isEmpty();
        assertThat(BootGate.jsonField(null, "id")).isEmpty();
    }
}
