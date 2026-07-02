package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-AN-8: the runnable-artifact gate runs the package-level build once at the completion edge
 * and reports a blocker summary on failure. Ground truth (2026-06-12, run-2 artifact): an
 * entry-point-less Spring Boot artifact fails {@code mvn package} with "Unable to find main
 * class" — the build tool, not autobuilder, owns the definition of "packageable".
 */
class PackageGateTest {

    private final BuildExecutor buildExecutor = mock(BuildExecutor.class);

    private PackageGate gate(boolean enabled) {
        PackageGate g = new PackageGate(enabled, "mvn package -DskipTests", buildExecutor);
        g.setFileBasePath("/tmp/project");
        return g;
    }

    @Test
    void packagingFailure_returnsBlockerSummary_andEmitsAttemptEvent() {
        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(eq("/tmp/project"), eq("cycle-9"), eq("mvn package -DskipTests")))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_ERROR,
                        "[INFO] Building demo\n\u001B[1;31m[ERROR]\u001B[m Failed to execute goal repackage: Unable to find main class\n[ERROR] noise",
                        1234L, "mvn package -DskipTests", "cycle-9"));
        PackageGate g = gate(true);
        g.setBehaviourLogger(behaviourLogger);

        Optional<String> blocker = g.verifyPackageable("cycle-9");

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("Unable to find main class");
        // ANSI colour codes are stripped — the summary lands in a goal description.
        assertThat(blocker.get()).doesNotContain("\u001B");
        verify(behaviourLogger).buildFixAttempt(eq("cycle-9"), eq("package-gate"), eq(0), eq(null),
                any(), eq(null), eq(List.of()), eq("COMPILE_ERROR"), eq(1234L));
    }

    @Test
    void packagingClean_returnsEmpty() {
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any(), any()))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_OK, "", 900L, "mvn package -DskipTests", "c"));

        assertThat(gate(true).verifyPackageable("c")).isEmpty();
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    private PackageGate gateOverTempDir() {
        PackageGate g = new PackageGate(true, "mvn package -DskipTests", buildExecutor);
        g.setFileBasePath(tempDir.toString());
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.resolveWorkingDir(tempDir.toString())).thenReturn(tempDir.toString());
        when(buildExecutor.execute(any(), any(), any()))
                .thenReturn(new BuildOutcome(BuildResult.COMPILE_OK, "", 900L, "mvn package -DskipTests", "c"));
        return g;
    }

    @Test
    void packagingClean_butDeclaredEntryPointMissing_returnsBlocker() throws Exception {
        // 2026-07-02 false completion #2: the loop "resolved" `Unable to find main class` by
        // pinning a nonexistent <mainClass> in the pom — repackage then writes the manifest
        // unverified and the jar dies at launch. The gate must check the declaration.
        java.nio.file.Files.writeString(tempDir.resolve("pom.xml"), """
                <project><build><plugins><plugin>
                  <artifactId>x-maven-plugin</artifactId>
                  <configuration><mainClass>com.example.library.LibraryApplication</mainClass></configuration>
                </plugin></plugins></build></project>
                """);

        Optional<String> blocker = gateOverTempDir().verifyPackageable("c");

        assertThat(blocker).isPresent();
        assertThat(blocker.get()).contains("com.example.library.LibraryApplication");
        assertThat(blocker.get()).contains("no such class exists");
    }

    @Test
    void packagingClean_declaredEntryPointPresent_returnsEmpty() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("pom.xml"), """
                <project><build><plugins><plugin>
                  <configuration><mainClass> com.example.App </mainClass></configuration>
                </plugin></plugins></build></project>
                """);
        java.nio.file.Path classFile = tempDir.resolve("target/classes/com/example/App.class");
        java.nio.file.Files.createDirectories(classFile.getParent());
        java.nio.file.Files.writeString(classFile, "x");

        assertThat(gateOverTempDir().verifyPackageable("c")).isEmpty();
    }

    @Test
    void packagingClean_noEntryPointDeclaration_returnsEmpty() throws Exception {
        // No declaration = the packaging step's own entry-point search already ran (it fails
        // loudly when nothing is found), so there is nothing extra to verify.
        java.nio.file.Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");

        assertThat(gateOverTempDir().verifyPackageable("c")).isEmpty();
    }

    @Test
    void noVerdictIsNotFailure_timeoutAndDisabledReturnEmpty() {
        // Packaging evidence hardens closure; an environment hiccup must not wedge an
        // otherwise-clean completion. TIMEOUT/SKIPPED → no blocker.
        when(buildExecutor.isEnabled()).thenReturn(true);
        when(buildExecutor.execute(any(), any(), any()))
                .thenReturn(new BuildOutcome(BuildResult.TIMEOUT, "", 180000L, "mvn package -DskipTests", "c"));
        assertThat(gate(true).verifyPackageable("c")).isEmpty();

        // Gate disabled → no build invocation at all.
        assertThat(gate(false).verifyPackageable("c")).isEmpty();
        verify(buildExecutor, never()).execute(any(), any(), eq(null));
    }
}
