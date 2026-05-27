package com.walpro.autobuilder.core.mutation;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompileAuthorErrorsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jacksonRoundTripPreservesPayloadAndIgnoresIsEmptyGetter() throws Exception {
        // Regression guard for the Jackson round-trip bug observed in run 2026-05-27_22-30-18:
        // isEmpty() was treated as a bean-style getter for a JSON property "empty", which
        // serialised on write but blew up the canonical record constructor on read with
        // UnrecognizedPropertyException. @JsonIgnore on isEmpty() must keep "empty" out of
        // the serialised form entirely.
        CompileAuthorErrors original = new CompileAuthorErrors(
                "sg-1",
                List.of(new BlockerExtractor.ClassifiedDiagnostic(
                        "src/main/java/Foo.java", 12, 5, "cannot find symbol"))
        );

        String json = mapper.writeValueAsString(original);
        assertThat(json).doesNotContain("\"empty\"");

        CompileAuthorErrors roundTripped = mapper.readValue(json, CompileAuthorErrors.class);
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void jacksonRoundTripOnEmptyDiagnosticsList() throws Exception {
        CompileAuthorErrors original = new CompileAuthorErrors("sg-2", List.of());

        String json = mapper.writeValueAsString(original);
        assertThat(json).doesNotContain("\"empty\"");

        CompileAuthorErrors roundTripped = mapper.readValue(json, CompileAuthorErrors.class);
        assertThat(roundTripped).isEqualTo(original);
        assertThat(roundTripped.isEmpty()).isTrue();
    }
}
