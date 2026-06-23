package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestOnlyCompileFailureTest {

    private static BuildOutcome err(String stderr) {
        return new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 100L, "mvn test-compile", "c");
    }

    @Test
    void testOnly_whenErrorsAreUnderSrcTestAndNoneUnderSrcMain() {
        BuildOutcome o = err("/proj/output/src/test/java/app/LoanServiceTest.java:[18,2] cannot find symbol\n  symbol: class Test");
        assertThat(TestOnlyCompileFailure.isTestOnly(o)).isTrue();
    }

    @Test
    void notTestOnly_whenAnyErrorIsUnderSrcMain() {
        BuildOutcome o = err("""
                /proj/output/src/main/java/app/LoanService.java:[10,5] cannot find symbol
                /proj/output/src/test/java/app/LoanServiceTest.java:[18,2] cannot find symbol
                """);
        assertThat(TestOnlyCompileFailure.isTestOnly(o)).isFalse();
    }

    @Test
    void notTestOnly_whenUnclassifiable_noSourcePaths() {
        assertThat(TestOnlyCompileFailure.isTestOnly(err("Some maven plugin error with no source path"))).isFalse();
        assertThat(TestOnlyCompileFailure.isTestOnly(err(""))).isFalse();
    }

    @Test
    void notTestOnly_forNonCompileErrorOutcomes() {
        assertThat(TestOnlyCompileFailure.isTestOnly(
                new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "c", "c"))).isFalse();
        assertThat(TestOnlyCompileFailure.isTestOnly(
                new BuildOutcome(BuildResult.TIMEOUT, "/proj/src/test/x.java err", 1L, "c", "c"))).isFalse();
        assertThat(TestOnlyCompileFailure.isTestOnly(null)).isFalse();
    }

    @Test
    void handlesWindowsStylePaths() {
        BuildOutcome o = err("C:\\proj\\output\\src\\test\\java\\app\\FooTest.java:[3,1] cannot find symbol");
        assertThat(TestOnlyCompileFailure.isTestOnly(o)).isTrue();
    }
}
