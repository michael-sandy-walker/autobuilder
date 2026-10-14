package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-T, ladder-grind 2026-10-14_00-22-30: 15 of 17 late candidates failed because they added a second copy of a helper
 * the file already had. The copy the compiler names -- the one the change wrote -- stays; the earlier one goes.
 */
class DuplicateMethodResolverTest {

    private static final String QUILL = """
            package zeta;

            import java.util.List;

            public class Quill {

                /** The old way. */
                private static boolean isBlotted(java.util.List<String> marks, int nib) {
                    return false;
                }

                public int ink() { return 1; }

                /** The way the change wants. */
                private static boolean isBlotted(List<String> marks, int nib) {
                    return marks.isEmpty();
                }
            }
            """;

    @Test
    void theEarlierCopyGoes_theCopyTheCompilerNamesStays(@TempDir Path root) throws Exception {
        Path file = Files.createDirectories(root.resolve("src/main/java/zeta")).resolve("Quill.java");
        Files.writeString(file, QUILL);
        int later = 15;
        String stderr = "[ERROR] " + file + ":[" + later + ",28] method isBlotted(java.util.List<java.lang.String>,int)"
                + " is already defined in class zeta.Quill\n";
        CycleContextData ctx = CycleContextData.builder().cycleName("c-1").outputPath(root.toString()).build();

        Optional<FixProposal> fix = new DuplicateMethodResolver().resolve(new Classification(Kind.UNKNOWN, List.of()), null, stderr, ctx);

        assertThat(fix).isPresent();
        String once = fix.get().mutations().get(0).content();
        assertThat(once).contains("return marks.isEmpty();", "The way the change wants.", "public int ink()")
                .doesNotContain("The old way.", "return false;");
        assertThat(fix.get().mutations().get(0).path()).isEqualTo("src/main/java/zeta/Quill.java");
    }

    @Test
    void overloadsAreNotDuplicates_andAVariableDeclaredTwiceIsLeftToTheModel(@TempDir Path root) throws Exception {
        Path file = Files.createDirectories(root.resolve("src/main/java/zeta")).resolve("Quill.java");
        Files.writeString(file, "package zeta;\nclass Quill {\n  int nib(int a) { return a; }\n  int nib(String a) { return 1; }\n}\n");
        CycleContextData ctx = CycleContextData.builder().cycleName("c-1").outputPath(root.toString()).build();
        String overload = "[ERROR] " + file + ":[4,7] method nib(int) is already defined in class zeta.Quill\n";
        String variable = "[ERROR] " + file + ":[3,7] variable a is already defined in method nib(int)\n";

        assertThat(new DuplicateMethodResolver().resolve(null, null, overload, ctx)).isEmpty();
        assertThat(new DuplicateMethodResolver().resolve(null, null, variable, ctx)).isEmpty();
    }

    @Test
    void javacsParameterListIsReadAsSimpleNames() {
        assertThat(DuplicateMethodResolver.simpleTypes("com.github.javaparser.ast.body.MethodDeclaration,java.util.Map<java.lang.String,java.lang.Integer>,int..."))
                .containsExactly("MethodDeclaration", "Map", "int...");
    }

    /** The change wrote the helper three times; the compiler reports each later copy. The last one is kept. */
    @Test
    void ofSeveralCopiesTheLastIsKept(@TempDir Path root) throws Exception {
        Path file = Files.createDirectories(root.resolve("src/main/java/zeta")).resolve("Quill.java");
        Files.writeString(file, "package zeta;\nclass Quill {\n  int nib() { return 0; }\n  int nib() { return 1; }\n"
                + "  int nib() { return 2; }\n  int nib() { return 3; }\n}\n");
        CycleContextData ctx = CycleContextData.builder().cycleName("c-1").outputPath(root.toString()).build();
        String stderr = "[ERROR] " + file + ":[4,7] method nib() is already defined in class zeta.Quill\n"
                + "[ERROR] " + file + ":[5,7] method nib() is already defined in class zeta.Quill\n"
                + "[ERROR] " + file + ":[6,7] method nib() is already defined in class zeta.Quill\n";

        String once = new DuplicateMethodResolver().resolve(null, null, stderr, ctx).orElseThrow().mutations().get(0).content();

        assertThat(once).contains("return 3;").doesNotContain("return 0;", "return 1;", "return 2;");
    }
}
