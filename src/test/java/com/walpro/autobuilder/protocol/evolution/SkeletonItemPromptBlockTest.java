package com.walpro.autobuilder.protocol.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T6-T: a skeleton item's prompt names its bodies still to write and what the types around them offer. */
class SkeletonItemPromptBlockTest {

    @Test
    void theStubsStillToWriteAndTheMembersOfTheTypesUsed_namedFirstWhenTheTestsNameThem(@TempDir Path project) throws Exception {
        Path main = Files.createDirectories(project.resolve("src/main/java/org/ink"));
        Files.writeString(main.resolve("Quill.java"), """
                package org.ink;
                public class Quill {
                    public int written() { return 1; }
                    public String line(Outcome o) { throw new UnsupportedOperationException("skeleton"); }
                }
                """);
        Files.writeString(main.resolve("Outcome.java"), "package org.ink;\npublic record Outcome(String stderrTail, int code) {\n  public boolean failed() { return code != 0; }\n}\n");
        Path test = Files.createDirectories(project.resolve("src/test/java/org/ink"));
        Files.writeString(test.resolve("QuillTest.java"), "package org.ink;\nclass QuillTest { void t() { new Quill().line(new Outcome(\"x\", 1)); o.failed(); } }\n");

        String block = SkeletonItemPromptBlock.render(project, "org.ink.Quill", List.of("QuillTest"), 2_000);

        assertThat(block).contains("THE BODIES TO WRITE IN Quill", "  - public String line(Outcome o)", "Outcome: failed(), stderrTail(), code()")
                .doesNotContain("written()");
        Files.writeString(main.resolve("Quill.java"), "package org.ink;\npublic class Quill {\n  public String line(Outcome o) { return o.stderrTail(); }\n}\n");
        assertThat(SkeletonItemPromptBlock.render(project, "org.ink.Quill", List.of("QuillTest"), 2_000)).as("every body written").isNull();
    }
}
