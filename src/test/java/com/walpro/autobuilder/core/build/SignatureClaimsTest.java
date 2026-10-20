package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** "No existing public method was renamed, removed or changed its signature", answered by the declarations as adopted and now. */
class SignatureClaimsTest {

    private static final String PATH = "src/main/java/org/warp/Coil.java";
    private static final String ADOPTED = """
            package org.warp;
            record Turn(int n) { }
            public class Coil {
                public Coil(int turns) { }
                public int turns() { return 1; }
                public Coil wind(int n, String dir) { return this; }
                int hidden() { return 0; }
            }
            """;
    private static final String CLAIM = "No existing public method in Coil.java has been renamed, removed, or had its signature changed";

    @Test
    void everyAdoptedPublicMethodStillDeclaredIsConfirmed() {
        String grown = ADOPTED.replace("int hidden()", "public double strength() { return 0; }\n    int hidden()");
        assertThat(SignatureClaims.answer(CLAIM, Map.of(PATH, ADOPTED), Map.of(PATH, grown))).isTrue();
        assertThat(SignatureClaims.answer("The existing public methods of `Coil` keep their signatures",
                Map.of(PATH, ADOPTED), Map.of(PATH, grown))).isTrue();
    }

    @Test
    void aRemovedOrRetypedMethodContradictsIt() {
        assertThat(SignatureClaims.answer(CLAIM, Map.of(PATH, ADOPTED),
                Map.of(PATH, ADOPTED.replace("public int turns() { return 1; }", "")))).isFalse();
        assertThat(SignatureClaims.answer(CLAIM, Map.of(PATH, ADOPTED),
                Map.of(PATH, ADOPTED.replace("wind(int n, String dir)", "wind(long n, String dir)")))).isFalse();
        assertThat(SignatureClaims.answer(CLAIM, Map.of(PATH, ADOPTED),
                Map.of(PATH, ADOPTED.replace("public int turns()", "int turns()")))).isFalse();
    }

    @Test
    void withoutTheAdoptedTextOrWithABehaviourHalfItIsLeftToTheReader() {
        assertThat(SignatureClaims.answer(CLAIM, Map.of(), Map.of(PATH, ADOPTED))).isNull();
        assertThat(SignatureClaims.answer("Existing public methods of Coil remain unchanged in signature and behavior",
                Map.of(PATH, ADOPTED), Map.of(PATH, ADOPTED))).isNull();
        assertThat(SignatureClaims.answer("Coil.wind returns the coil", Map.of(PATH, ADOPTED), Map.of(PATH, ADOPTED))).isNull();
    }
}
