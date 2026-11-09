package com.walpro.autobuilder.core.coldread;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimProposerTest {

    @Test
    void parsesClaimsWithSetupReferencesAndUniqueValues() {
        String answer = """
                CLAIM: "POST /members with a duplicate email returns HTTP 409"
                SEND: POST /members {"name":"a","email":"a{u}@x.io"}
                SEND: POST /members {"name":"b","email":"a{u}@x.io"}
                EXPECT: 409

                CLAIM: GET /members/{id} returns HTTP 200 for an existing member
                SEND: POST /members {"name":"c","email":"c{u}@x.io"}
                SEND: GET /members/$1.id
                EXPECT: 200 OK

                CLAIM: something with no expectation
                SEND: GET /x
                """;
        ClaimProposer.Proposal p = ClaimProposer.parse(answer, "c2", 5, List.of());

        assertThat(p.claims()).hasSize(2);
        var first = p.claims().get(0);
        assertThat(first.id()).isEqualTo("k5");
        assertThat(first.text()).isEqualTo("POST /members with a duplicate email returns HTTP 409");
        assertThat(first.expected()).isEqualTo(409);
        assertThat(first.sequence().get(0).body()).isEqualTo("{\"name\":\"a\",\"email\":\"a1000005@x.io\"}");
        assertThat(first.sequence().get(1).body()).isEqualTo(first.sequence().get(0).body().replace("\"a\"", "\"b\""));
        var second = p.claims().get(1);
        assertThat(second.id()).isEqualTo("k6");
        assertThat(second.sequence().get(1).method()).isEqualTo("GET");
        assertThat(second.sequence().get(1).path()).isEqualTo("/members/$1.id");
        assertThat(second.sequence().get(1).body()).isNull();
        assertThat(p.malformed()).containsExactly("something with no expectation");
    }
}
