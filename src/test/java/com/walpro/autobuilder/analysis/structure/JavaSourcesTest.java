package com.walpro.autobuilder.analysis.structure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.RecordDeclaration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T6-T: the framework's parser reads what a target may write -- a record and a text block among it. */
class JavaSourcesTest {

    private static final String SOURCE = """
            package zeta;
            public record Quill(String ink, int nib) {
                static final String LABEL = \"\"\"
                        a quill
                        \"\"\";
            }
            """;

    @Test
    void aRecordAndATextBlockAreRead() {
        var result = JavaSources.parser().parse(SOURCE);

        assertThat(result.isSuccessful()).as(result.getProblems().toString()).isTrue();
        assertThat(result.getResult().orElseThrow().findFirst(RecordDeclaration.class)).isPresent();
    }

    /** The library's own default, which every caller had before: the reason this class exists. */
    @Test
    void theLibrarysDefaultLevelReadsNeither() {
        assertThat(new JavaParser().parse(SOURCE).isSuccessful()).isFalse();
    }
}
