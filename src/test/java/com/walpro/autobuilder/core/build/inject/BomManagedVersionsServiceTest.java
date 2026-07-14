package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.ProjectClasspathService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * T4-BM step 4: the project's own dependency-management chain (a fake local-repository layout in
 * a {@code @TempDir}) answers version questions — never a literal of ours. The chain fixture
 * mirrors the parent-BOM convention: project → parent pom (in the repo) → a BOM with
 * property-driven managed versions.
 */
class BomManagedVersionsServiceTest {

    @TempDir
    Path tmp;

    private Path projectRoot;
    private Path repoRoot;
    private BomManagedVersionsService service;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = tmp.resolve("project");
        Files.createDirectories(projectRoot);
        repoRoot = tmp.resolve("repository");
        Files.createDirectories(repoRoot);

        // The classpath names a jar under the fake repository so the service derives the repo
        // root from the project's own environment (no reliance on ~/.m2 in tests).
        Path anchorJar = repoRoot.resolve("com/anchor/anchor/1.0/anchor-1.0.jar");
        Files.createDirectories(anchorJar.getParent());
        Files.writeString(anchorJar, "");

        ProjectClasspathService classpathService = mock(ProjectClasspathService.class);
        lenient().when(classpathService.resolveClasspath(any(), anyLong()))
                .thenReturn(anchorJar + File.pathSeparator + "/opt/other.jar");

        service = new BomManagedVersionsService(classpathService);
    }

    private void repoPom(String groupId, String artifactId, String version, String body) throws IOException {
        Path dir = repoRoot.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(artifactId + "-" + version + ".pom"), body);
    }

    private void projectPom(String body) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), body);
    }

    @Test
    void managedVersion_resolvesThroughTheParentChainAndPropertyIndirection() throws IOException {
        // project -> com.base:starter-parent:2.0 -> com.base:platform-bom:2.0 (the managed table)
        projectPom("""
                <project>
                  <parent>
                    <groupId>com.base</groupId>
                    <artifactId>starter-parent</artifactId>
                    <version>2.0</version>
                  </parent>
                  <artifactId>demo</artifactId>
                  <dependencies><dependency>
                    <groupId>x</groupId><artifactId>y</artifactId>
                  </dependency></dependencies>
                </project>
                """);
        repoPom("com.base", "starter-parent", "2.0", """
                <project>
                  <parent>
                    <groupId>com.base</groupId>
                    <artifactId>platform-bom</artifactId>
                    <version>2.0</version>
                  </parent>
                  <artifactId>starter-parent</artifactId>
                </project>
                """);
        repoPom("com.base", "platform-bom", "2.0", """
                <project>
                  <artifactId>platform-bom</artifactId>
                  <version>2.0</version>
                  <properties>
                    <validation-api.version>3.0.2</validation-api.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>jakarta.validation</groupId>
                      <artifactId>jakarta.validation-api</artifactId>
                      <version>${validation-api.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>com.fixed</groupId>
                      <artifactId>fixed-lib</artifactId>
                      <version>1.2.3</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);

        assertThat(service.managedVersion(projectRoot, "jakarta.validation", "jakarta.validation-api"))
                .contains("3.0.2");
        assertThat(service.managedVersion(projectRoot, "com.fixed", "fixed-lib"))
                .contains("1.2.3");
        assertThat(service.managedVersion(projectRoot, "com.absent", "nope")).isEmpty();
    }

    @Test
    void managedVersion_importScopedBomEntriesAreFoldedIn() throws IOException {
        projectPom("""
                <project>
                  <artifactId>demo</artifactId>
                  <version>0.1</version>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.base</groupId>
                      <artifactId>platform-bom</artifactId>
                      <version>2.0</version>
                      <type>pom</type>
                      <scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        repoPom("com.base", "platform-bom", "2.0", """
                <project>
                  <artifactId>platform-bom</artifactId>
                  <version>2.0</version>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.managed</groupId>
                      <artifactId>via-import</artifactId>
                      <version>4.5</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);

        assertThat(service.managedVersion(projectRoot, "com.managed", "via-import")).contains("4.5");
    }

    @Test
    void managedVersion_nearestDeclarationWins() throws IOException {
        projectPom("""
                <project>
                  <parent>
                    <groupId>com.base</groupId>
                    <artifactId>starter-parent</artifactId>
                    <version>2.0</version>
                  </parent>
                  <artifactId>demo</artifactId>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.fixed</groupId>
                      <artifactId>fixed-lib</artifactId>
                      <version>9.9.9</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        repoPom("com.base", "starter-parent", "2.0", """
                <project>
                  <artifactId>starter-parent</artifactId>
                  <version>2.0</version>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.fixed</groupId>
                      <artifactId>fixed-lib</artifactId>
                      <version>1.2.3</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);

        assertThat(service.managedVersion(projectRoot, "com.fixed", "fixed-lib")).contains("9.9.9");
    }

    @Test
    void managedVersion_absentParentPomInTheRepository_degradesToTheReadableChain() throws IOException {
        projectPom("""
                <project>
                  <parent>
                    <groupId>com.missing</groupId>
                    <artifactId>never-downloaded</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>demo</artifactId>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.local</groupId>
                      <artifactId>local-lib</artifactId>
                      <version>0.7</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);

        assertThat(service.managedVersion(projectRoot, "com.local", "local-lib")).contains("0.7");
        assertThat(service.managedVersion(projectRoot, "com.remote", "unreachable")).isEmpty();
    }

    @Test
    void managedVersion_noPomOrNoManagement_isEmpty() throws IOException {
        assertThat(service.managedVersion(projectRoot, "g", "a")).isEmpty();
        projectPom("<project><artifactId>demo</artifactId></project>");
        service.__resetCacheForTest();
        assertThat(service.managedVersion(projectRoot, "g", "a")).isEmpty();
    }

    @Test
    void managedVersion_unresolvablePropertyPlaceholder_skipsTheEntry() throws IOException {
        projectPom("""
                <project>
                  <artifactId>demo</artifactId>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>com.ghost</groupId>
                      <artifactId>ghost-lib</artifactId>
                      <version>${undefined.property}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);

        assertThat(service.managedVersion(projectRoot, "com.ghost", "ghost-lib")).isEmpty();
    }
}
