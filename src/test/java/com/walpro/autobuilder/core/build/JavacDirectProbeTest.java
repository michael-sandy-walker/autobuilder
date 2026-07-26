package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T4-BN Phase 1: tests for {@link JavacDirectProbe}. All tests are gated on
 * {@code ToolProvider.getSystemJavaCompiler() != null} so they degrade gracefully on a JRE
 * (skip instead of fail). Autobuilder's own build runs on a JDK so this gate never fires in CI.
 */
class JavacDirectProbeTest {

    @TempDir Path tmp;

    private final JavacDirectProbe probe = new JavacDirectProbe();

    private void writeSource(String relPath, String content) throws IOException {
        Path p = tmp.resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void cleanSource_compileOk_emptyStderr() throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system JDK compiler");
        writeSource("src/main/java/com/example/Foo.java",
                "package com.example; public class Foo { public int bar() { return 42; } }");

        BuildOutcome outcome = probe.execute(tmp.toString(), "cycle-1", 60_000L);

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_OK);
        assertThat(outcome.stderrTail()).isEmpty();
        assertThat(outcome.command()).isEqualTo("javac-direct");
        assertThat(outcome.cycleId()).isEqualTo("cycle-1");
        assertThat(Files.exists(tmp.resolve("target/classes/com/example/Foo.class"))).isTrue();
    }

    @Test
    void brokenSource_compileError_diagnosticInStderrTail() throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system JDK compiler");
        writeSource("src/main/java/com/example/Broken.java",
                "package com.example; public class Broken { public int bad() { return undefinedSymbol; } }");

        BuildOutcome outcome = probe.execute(tmp.toString(), "cycle-2", 60_000L);

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(outcome.stderrTail())
                .contains("error:")
                .contains("Broken.java")
                .contains("cannot find symbol");
    }

    @Test
    void noSources_compileOk_zeroDuration() {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system JDK compiler");
        // No src/main/java directory at all — outcome is COMPILE_OK with no work done.
        BuildOutcome outcome = probe.execute(tmp.toString(), "cycle-3", 60_000L);
        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_OK);
    }

    @Test
    void missingWorkingDir_skipped() {
        BuildOutcome outcome = probe.execute(tmp.resolve("does-not-exist").toString(), "cycle-4", 60_000L);
        assertThat(outcome.result()).isEqualTo(BuildResult.SKIPPED);
        assertThat(outcome.stderrTail()).contains("does not exist");
    }

    @Test
    void blankWorkingDir_skipped() {
        BuildOutcome outcome = probe.execute("", "cycle-5", 60_000L);
        assertThat(outcome.result()).isEqualTo(BuildResult.SKIPPED);
    }

    @Test
    void classpathCache_hitAfterFirstResolve_evenWithMissingPom() throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system JDK compiler");
        // No pom.xml at all → resolveClasspath returns "" and caches the empty result.
        writeSource("src/main/java/com/example/A.java",
                "package com.example; public class A {}");

        BuildOutcome first = probe.execute(tmp.toString(), "c-1", 60_000L);
        BuildOutcome second = probe.execute(tmp.toString(), "c-2", 60_000L);

        assertThat(first.result()).isEqualTo(BuildResult.COMPILE_OK);
        assertThat(second.result()).isEqualTo(BuildResult.COMPILE_OK);
        // No exception, no mvn shell invocation on the second call (verified by the lack of
        // delay; we don't measure here, the absence of pom.xml means shellMvnBuildClasspath is
        // never even called).
    }

    @Test
    void resetCache_forcesFreshResolveOnNextCall() throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system JDK compiler");
        writeSource("src/main/java/com/example/B.java",
                "package com.example; public class B {}");

        BuildOutcome first = probe.execute(tmp.toString(), "c-1", 60_000L);
        probe.__resetCacheForTest();
        BuildOutcome second = probe.execute(tmp.toString(), "c-2", 60_000L);

        assertThat(first.result()).isEqualTo(BuildResult.COMPILE_OK);
        assertThat(second.result()).isEqualTo(BuildResult.COMPILE_OK);
    }

    @Test
    void brokenTestSource_compileError_whenTestCompileEnabled() throws Exception {
        // T4-BN-2: a clean main source plus a test that references a missing symbol. With
        // test-compile coverage on, the probe must catch the test break (the failure mode that
        // produced the 2026-06-17 root GIVEN_UP, where a test break only surfaced at the package gate).
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system JDK compiler");
        writeSource("src/main/java/com/example/Svc.java",
                "package com.example; public class Svc { public int v() { return 1; } }");
        writeSource("src/test/java/com/example/SvcTest.java",
                "package com.example; public class SvcTest { void t() { nonExistentAssert(new Svc().v()); } }");

        BuildOutcome outcome = probe.execute(tmp.toString(), "cycle-t1", 60_000L);

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_ERROR);
        assertThat(outcome.stderrTail()).contains("SvcTest.java").contains("cannot find symbol");
    }

    @Test
    void brokenTestSource_ignored_whenTestCompileDisabled() throws Exception {
        // Flag off ⇒ main-only probing; the broken test is invisible (the pre-T4-BN-2 behaviour).
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system JDK compiler");
        probe.setIncludeTestCompile(false);
        writeSource("src/main/java/com/example/Svc.java",
                "package com.example; public class Svc { public int v() { return 1; } }");
        writeSource("src/test/java/com/example/SvcTest.java",
                "package com.example; public class SvcTest { void t() { nonExistentAssert(); } }");

        BuildOutcome outcome = probe.execute(tmp.toString(), "cycle-t2", 60_000L);

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_OK);
    }

    @Test
    void validTestReferencingMain_compileOk_crossRefResolvesInOnePass() throws Exception {
        // Compiling main + test together resolves the test's reference to a main type with no
        // separate classpath wiring.
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system JDK compiler");
        writeSource("src/main/java/com/example/Svc.java",
                "package com.example; public class Svc { public int v() { return 7; } }");
        writeSource("src/test/java/com/example/SvcTest.java",
                "package com.example; public class SvcTest { int check() { return new Svc().v(); } }");

        BuildOutcome outcome = probe.execute(tmp.toString(), "cycle-t3", 60_000L);

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_OK);
    }

    @Test
    void compileError_stderrShapeMatchesBlockerExtractorPatterns() throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system JDK compiler");
        // Symbol-shape diagnostic the downstream BlockerExtractor is tuned to recognise —
        // verifies our javac output flows transparently through it.
        writeSource("src/main/java/com/example/SymbolRef.java",
                "package com.example; public class SymbolRef { public void m() { MissingType x = null; } }");

        BuildOutcome outcome = probe.execute(tmp.toString(), "cycle-x", 60_000L);

        assertThat(outcome.result()).isEqualTo(BuildResult.COMPILE_ERROR);
        // BlockerExtractor's ERROR_DIAGNOSTIC_LINE pattern: "(?:\\[ERROR]|error:)" — javac's
        // "error:" form must match.
        assertThat(outcome.stderrTail()).contains("error:");
        // The "symbol:   class MissingType" form is what symbol-extracting consumers read —
        // confirm javac produces it.
        assertThat(outcome.stderrTail().toLowerCase()).contains("missingtype");
    }
}
