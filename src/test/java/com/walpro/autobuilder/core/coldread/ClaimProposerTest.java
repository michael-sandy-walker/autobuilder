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

    @Test
    void parsesLibraryAndCommandClaimsAndRefusesAKindTheTreeDoesNotHave() {
        String answer = """
                CLAIM: checkChar of NO00001 is N
                TEST:
                ```java
                assertEquals('N', Labeler.checkChar("NO00001"));
                ```
                END

                CLAIM: count of a two-line file prints 2
                FILE: data{u}.csv
                | a,b
                | 1,2
                RUN: count data{u}.csv "with space"
                EXPECT: exit 0
                OUTPUT: 2

                CLAIM: a request
                SEND: GET /x
                EXPECT: 200
                """;
        var both = ClaimProposer.parse(answer, "c1", 1, java.util.List.of(),
                java.util.Set.of(com.walpro.autobuilder.core.coldread.entry.EntryKind.LIBRARY_STYLE,
                        com.walpro.autobuilder.core.coldread.entry.EntryKind.COMMAND_STYLE));
        assertThat(both.claims()).hasSize(2);
        var lib = both.claims().get(0);
        assertThat(lib.test()).isEqualTo("assertEquals('N', Labeler.checkChar(\"NO00001\"));");
        var cmd = both.claims().get(1);
        assertThat(cmd.run().files()).containsEntry("data1000002.csv", "a,b\n1,2\n");
        assertThat(cmd.run().args()).containsExactly("count", "data1000002.csv", "with space");
        assertThat(cmd.expected()).isZero();
        assertThat(cmd.run().outputContains()).isEqualTo("2");
        assertThat(both.malformed()).containsExactly("a request");
    }

    @Test
    void aFileRunsToTheNextDirectiveSoAValueContinuedOnTheNextLineIsKept() {
        String answer = """
                CLAIM: a quoted value over two lines is one row
                FILE: multi.csv
                | name,note
                | ada,"line one
                line two"
                | grace,plain

                RUN: count multi.csv
                EXPECT: exit 0
                OUTPUT: 2

                CLAIM: an empty file
                FILE: empty.csv
                RUN: check empty.csv
                EXPECT: exit 2
                """;
        var p = ClaimProposer.parse(answer, "c1", 1, java.util.List.of(),
                java.util.Set.of(com.walpro.autobuilder.core.coldread.entry.EntryKind.COMMAND_STYLE));
        assertThat(p.claims()).hasSize(2);
        assertThat(p.claims().get(0).run().files().get("multi.csv"))
                .isEqualTo("name,note\nada,\"line one\nline two\"\ngrace,plain\n");
        assertThat(p.claims().get(1).run().files().get("empty.csv")).isEmpty();
    }
}
