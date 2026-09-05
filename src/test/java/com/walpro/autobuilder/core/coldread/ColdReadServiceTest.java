package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first thing the loop does with a project it did not write: say what is there.
 *
 * <p>The fixture is shaped like a real imported project rather than like a tree the loop built —
 * a wrapper directory, a binary, a script, a context path in configuration, and tests someone else
 * wrote. Each of those defeated the existing whole-tree reader when the first real project arrived.
 */
class ColdReadServiceTest {

    @TempDir
    Path tmp;

    private ColdReadService service;

    @BeforeEach
    void setUp() {
        service = new ColdReadService(new EndpointSurfaceQuery());
    }

    @Test
    @DisplayName("it reports the build, the surface, the prose and someone else's tests")
    void itReportsWhatIsThere() throws IOException {
        importedProject();

        ColdRead r = service.read(tmp);

        assertThat(r.build().tool()).isEqualTo("maven");
        assertThat(r.build().artifact())
                .as("the project's own artifactId, not the parent's")
                .isEqualTo("calculator");
        assertThat(r.build().javaVersion()).isEqualTo("11");
        assertThat(r.build().framework()).contains("Spring Boot 2.2.0.RELEASE");
        assertThat(r.build().dependencies()).contains("org.springframework.boot:spring-boot-starter-web");
        assertThat(r.runtime().contextPath()).isEqualTo("/calculator");
        assertThat(r.prose()).as("quoted, never summarised").contains("A calculator over HTTP.");
        assertThat(r.surface()).containsExactlyInAnyOrder("GET /basic/add", "GET /basic/divide");
        assertThat(r.surfaceComplete()).isTrue();
        assertThat(r.types()).anyMatch(t -> t.contains("BasicController") && t.contains("@RestController"));
        assertThat(r.tests()).containsExactly("CalculatorTest.addsTwoNumbers");
        assertThat(r.actionable()).isTrue();
    }

    @Test
    @DisplayName("build tooling and binaries are skipped and SAID to be skipped, not read as source")
    void toolingIsSkippedOutLoud() throws IOException {
        importedProject();
        Files.createDirectories(tmp.resolve(".mvn/wrapper"));
        Files.write(tmp.resolve(".mvn/wrapper/maven-wrapper.jar"), new byte[]{0x50, 0x4b, 0x03, 0x04, 0x00, 0x01});
        Files.writeString(tmp.resolve(".mvn/wrapper/MavenWrapperDownloader.java"), "public class MavenWrapperDownloader {}");
        Files.writeString(tmp.resolve("mvnw.cmd"), "@REM windows launcher\r\nset ERROR_CODE=0\r\n");

        ColdRead r = service.read(tmp);

        assertThat(r.types())
                .as("the wrapper's downloader is not one of the project's types")
                .noneMatch(t -> t.contains("MavenWrapperDownloader"));
        assertThat(r.skipped())
                .anyMatch(s -> s.startsWith(".mvn/wrapper/maven-wrapper.jar"))
                .anyMatch(s -> s.startsWith("mvnw.cmd"));
        assertThat(r.skipped()).allMatch(s -> s.contains("("),
                "a skipped file names its reason, so the reader can judge the omission");
    }

    @Test
    @DisplayName("a test source does not inflate the surface the project serves")
    void testsDoNotCountTowardsTheSurface() throws IOException {
        importedProject();
        Files.writeString(tmp.resolve("src/test/java/StubController.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController class StubController {
                  @GetMapping("/only-in-a-test") String x() { return "x"; }
                }
                """);

        ColdRead r = service.read(tmp);

        assertThat(r.surface()).noneMatch(s -> s.contains("only-in-a-test"));
    }

    @Test
    @DisplayName("a context path is reported as BOTH forms, because they are two different facts")
    void bothFormsOfEveryRouteAreReported() throws IOException {
        // Reporting only the reachable form put that form into a specification, the loop wrote
        // MockMvc tests with it, and MockMvc applies no context path: ten of eleven answered 404.
        importedProject();

        String report = ColdReadReport.render(service.read(tmp));

        assertThat(report).contains("as the controllers declare it").contains("as it answers over HTTP");
        assertThat(report).contains("GET /basic/add").contains("GET /calculator/basic/add");
        assertThat(report).contains("prefix is configuration, not code");
    }

    @Test
    @DisplayName("an empty directory is an account too, and says what it could not establish")
    void nothingThereIsStillAnAccount() {
        ColdRead r = service.read(tmp);

        assertThat(r.actionable()).isFalse();
        assertThat(r.uncertainties())
                .anyMatch(u -> u.contains("no build file"))
                .anyMatch(u -> u.contains("no tests"))
                .anyMatch(u -> u.contains("says nothing about itself"));
        assertThat(ColdReadReport.render(r)).contains("ACTIONABLE: no");
    }

    @Test
    @DisplayName("the rendered report leads with what it could not establish, not with a footnote")
    void theReportSaysWhatItCouldNotEstablishFirst() {
        String report = ColdReadReport.render(service.read(tmp));

        assertThat(report.indexOf("COULD NOT ESTABLISH"))
                .as("a reader judges an account of their own project by its caveats")
                .isLessThan(report.indexOf("BUILD"));
    }

    /** A tree shaped like a project the loop did NOT write. */
    private void importedProject() throws IOException {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.createDirectories(tmp.resolve("src/main/resources"));
        Files.createDirectories(tmp.resolve("src/test/java"));
        Files.writeString(tmp.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.2.0.RELEASE</version>
                  </parent>
                  <artifactId>calculator</artifactId>
                  <properties><java.version>11</java.version></properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Files.writeString(tmp.resolve("README.md"), "# calculator\n\nA calculator over HTTP.\n");
        Files.writeString(tmp.resolve("src/main/resources/application.properties"),
                "server.servlet.context-path=/calculator\n");
        Files.writeString(tmp.resolve("src/main/java/BasicController.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/basic")
                class BasicController {
                  @GetMapping("/add") String add() { return "0"; }
                  @GetMapping("/divide") String divide() { return "0"; }
                }
                """);
        Files.writeString(tmp.resolve("src/test/java/CalculatorTest.java"), """
                import org.junit.jupiter.api.Test;
                class CalculatorTest {
                  @Test void addsTwoNumbers() {}
                }
                """);
    }
}
