package com.walpro.autobuilder.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.walpro.autobuilder.core.build.BootGate;
import com.walpro.autobuilder.core.build.BuildExecutor;
import com.walpro.autobuilder.core.build.PackageGate;
import com.walpro.autobuilder.core.build.TestRunGate;
import org.junit.jupiter.api.Test;

/** T1-GH: the manifest says what the configured checks do, and nothing they do not. */
class SystemCapabilityManifestTest {

    private final BuildExecutor buildExecutor = mock(BuildExecutor.class);
    private final PackageGate packageGate = mock(PackageGate.class);
    private final TestRunGate testRunGate = mock(TestRunGate.class);
    private final BootGate bootGate = mock(BootGate.class);

    private String render(boolean build, boolean pkg, boolean tests, boolean boot) {
        when(buildExecutor.isEnabled()).thenReturn(build);
        when(packageGate.isLive()).thenReturn(pkg);
        when(testRunGate.isLive()).thenReturn(tests);
        when(bootGate.isLive()).thenReturn(boot);
        return new SystemCapabilityManifest(buildExecutor, packageGate, testRunGate, bootGate).render();
    }

    @Test
    void everyCheckOn_namesEachCheck_andNeverClaimsTheSystemCannotBuild() {
        String text = render(true, true, true, true);

        assertThat(text).contains("compiles every proposed change", "declares it in the build file",
                "packages the project", "runs the project's own tests", "starts the packaged program",
                "a completion check that fails becomes a new work item",
                "\"the tests pass\" or \"the program starts\" is checked by the system");
        assertThat(text.toLowerCase()).doesNotContain("cannot compile", "no ability", "cannot be satisfied",
                "do not propose");
    }

    @Test
    void buildOff_saysNothingIsCompiledTestedOrStarted_andOffersNoCheckedCriterion() {
        String text = render(false, true, true, true);

        assertThat(text).contains("no change is compiled, tested or started", "judged by reading it");
        assertThat(text).doesNotContain("compiles every", "packages the project", "runs the project's own tests",
                "starts the packaged program", "is checked by the system", "becomes a new work item");
    }

    @Test
    void eachCompletionCheckOff_dropsOnlyItsOwnLine() {
        assertThat(render(true, false, true, true))
                .doesNotContain("packages the project").contains("runs the project's own tests", "starts the packaged program");
        assertThat(render(true, true, false, true))
                .doesNotContain("runs the project's own tests", "\"the tests pass\"").contains("packages the project", "\"the program starts\"");
        assertThat(render(true, true, true, false))
                .doesNotContain("starts the packaged program", "\"the program starts\"").contains("runs the project's own tests");
    }

    @Test
    void compileOnly_offersCompilingAsTheExample_andNoCompletionWorkItem() {
        String text = render(true, false, false, false);

        assertThat(text).contains("compiles every proposed change", "\"the project compiles\" is checked by the system");
        assertThat(text).doesNotContain("becomes a new work item", "before the goal is declared complete");
    }
}
