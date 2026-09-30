package com.walpro.autobuilder.protocol;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.protocol.evolution.EditHunks;
import com.walpro.autobuilder.protocol.evolution.EvolutionProtocolContract;
import com.walpro.autobuilder.protocol.evolution.EvolutionResponseParser;
import com.walpro.autobuilder.protocol.merge.MergeProtocolContract;
import com.walpro.autobuilder.protocol.merge.MergeResponseParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fenced block ends at three backticks on a line of their own, so a source file that contains three
 * backticks inside a line -- a pattern that matches fences, a Markdown string -- is carried whole.
 */
class FencedBlocksTest {

    private static final String PATH = "src/main/java/org/ink/Fences.java";
    /** What edit-hunks run 2026-09-30_18-25 kept writing and the parsers kept cutting at the string. */
    private static final String FENCES = """
            package org.ink;

            import java.util.regex.Pattern;

            class Fences {
                static final Pattern BLOCK = Pattern.compile("(?s)```(\\\\w*) \\\\[([^\\\\]]+)\\\\]\\\\n(.*?)\\\\n```");
                static String open(String tag) { return "```" + tag; }
            }""";

    @Test
    void aClosingFenceIsALineOfItsOwn() {
        String answer = "```java [" + PATH + "]\n" + FENCES + "\n```\nafter";
        int body = answer.indexOf('\n') + 1;
        assertThat(FencedBlocks.closeAfter(answer, body)).isEqualTo(answer.lastIndexOf("```"));
        assertThat(FencedBlocks.closeAfter("``` inline ``` only", 3)).isEqualTo(-1);
        assertThat(FencedBlocks.closeAfter("x\n  ```  \n", 0)).as("indented, trailing blanks").isEqualTo(2);
    }

    @Test
    void theEvolutionParserCarriesAFileThatContainsBackticksWhole() {
        String answer = "Here it is:\n```java [" + PATH + "]\n" + FENCES + "\n```\n";

        assertThat(new EvolutionResponseParser().parse(answer).addedOrChangedFiles())
                .singleElement().extracting(FileMutation::content).isEqualTo(FENCES);
        assertThat(EvolutionProtocolContract.isValidResponse(answer)).isTrue();
    }

    @Test
    void anEditWhoseReplacementContainsBackticksAppliesWhole() {
        String before = "package org.ink;\nclass Fences {\n}";
        String answer = "```edit [" + PATH + "]\n<<<<<<< SEARCH\nclass Fences {\n=======\nclass Fences {\n"
                + "    static String open(String tag) { return \"```\" + tag; }\n>>>>>>> REPLACE\n```\n";

        var applied = EditHunks.apply(answer, Map.of(PATH, before));

        assertThat(applied.problems()).isEmpty();
        assertThat(applied.files().get(PATH)).contains("return \"```\" + tag;").endsWith("}");
    }

    @Test
    void theMergeParserCarriesItWholeToo() {
        String answer = "```java [" + PATH + "]\n" + FENCES + "\n```";

        assertThat(new MergeResponseParser().parse(answer).mergedContent()).isEqualTo(FENCES.strip());
        assertThat(MergeProtocolContract.isValidResponse(answer)).isTrue();
    }
}
