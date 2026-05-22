package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-BB: unit tests for {@link MissingMemberExtractor}. */
class MissingMemberExtractorTest {

    @Test
    void extract_blank_returnsEmpty() {
        assertThat(MissingMemberExtractor.extract(null)).isEmpty();
        assertThat(MissingMemberExtractor.extract("")).isEmpty();
        assertThat(MissingMemberExtractor.extract("   ")).isEmpty();
    }

    @Test
    void extract_constructor_extractsSimpleTypeAndArgs() {
        String stderr = "[ERROR] /src/main/java/com/example/LoanService.java:[42,17] " +
                "no suitable constructor found for Book(long,java.lang.String,java.lang.String)";

        List<MissingMemberExtractor.Entry> out = MissingMemberExtractor.extract(stderr);

        assertThat(out).hasSize(1);
        MissingMemberExtractor.Entry e = out.get(0);
        assertThat(e.kind()).isEqualTo(MissingMemberExtractor.Kind.CONSTRUCTOR);
        assertThat(e.targetType()).isEqualTo("Book");
        assertThat(e.memberName()).isEqualTo("Book");
        assertThat(e.argTypes()).isEqualTo("long,java.lang.String,java.lang.String");
    }

    @Test
    void extract_method_pairsSymbolWithLocation() {
        String stderr = String.join("\n",
                "[ERROR] /src/main/java/com/example/LoanController.java:[30,29] cannot find symbol",
                "[ERROR]   symbol:   method createLoan(java.lang.Long,java.lang.Long)",
                "[ERROR]   location: class com.example.LoanService");

        List<MissingMemberExtractor.Entry> out = MissingMemberExtractor.extract(stderr);

        assertThat(out).hasSize(1);
        MissingMemberExtractor.Entry e = out.get(0);
        assertThat(e.kind()).isEqualTo(MissingMemberExtractor.Kind.METHOD);
        assertThat(e.targetType()).isEqualTo("LoanService");
        assertThat(e.memberName()).isEqualTo("createLoan");
        assertThat(e.argTypes()).isEqualTo("java.lang.Long,java.lang.Long");
    }

    @Test
    void extract_methodWithoutLocation_isSkipped() {
        // Without `location: class T`, the entry would have no target — defer to T4-AZ (the
        // bare missing-symbol resolver handles missing free identifiers).
        String stderr = String.join("\n",
                "[ERROR] /src/main/java/Foo.java:[1,1] cannot find symbol",
                "[ERROR]   symbol:   method foo()");

        assertThat(MissingMemberExtractor.extract(stderr)).isEmpty();
    }

    @Test
    void extract_multipleEntries_deduplicatesByKindTypeNameArgs() {
        String stderr = String.join("\n",
                "[ERROR] /a.java:[1,1] no suitable constructor found for Book(long,java.lang.String,java.lang.String)",
                "[ERROR] /b.java:[2,2] no suitable constructor found for Book(long,java.lang.String,java.lang.String)",
                "[ERROR] /c.java:[3,3] no suitable constructor found for Member(long,java.lang.String,java.lang.String)",
                "[ERROR] /d.java:[4,4] cannot find symbol",
                "[ERROR]   symbol:   method createLoan(java.lang.Long,java.lang.Long)",
                "[ERROR]   location: class com.example.LoanService");

        List<MissingMemberExtractor.Entry> out = MissingMemberExtractor.extract(stderr);

        assertThat(out).hasSize(3);
        assertThat(out).extracting(MissingMemberExtractor.Entry::targetType)
                .containsExactly("Book", "Member", "LoanService");
    }

    @Test
    void extract_constructorWithFqType_extractsSimpleName() {
        String stderr = "[ERROR] no suitable constructor found for com.example.Book(long,java.lang.String)";

        List<MissingMemberExtractor.Entry> out = MissingMemberExtractor.extract(stderr);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).targetType()).isEqualTo("Book");
        assertThat(out.get(0).argTypes()).isEqualTo("long,java.lang.String");
    }
}
