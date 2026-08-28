package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DO. The guarantee asks the BUILT JAR whether it can run, because that is the same question
 * {@code java -jar} asks and it needs no list of which build plugins produce executable artifacts —
 * such a list would be the ecosystem table T4-DI removed, and would be wrong anyway for a plugin
 * that auto-detects its entry point.
 *
 * <p>Verified end-to-end before these tests were written: a real project with a real dependency,
 * built with Maven, failed `java -jar` with "no main manifest attribute"; after the repair it
 * printed its expected output with the dependency resolved from {@code lib/}.
 */
class RunnableJarGuaranteeTest {

    @TempDir
    Path root;

    private static final Map<String, String> ONE_ENTRY_POINT = Map.of(
            "src/main/java/com/demo/Main.java",
            "package com.demo;\npublic class Main { public static void main(String[] args) {} }\n");

    private static final String POM_WITH_DEPENDENCY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <artifactId>demo</artifactId>
                <dependencies>
                    <dependency><groupId>org.apache.commons</groupId>
                    <artifactId>commons-lang3</artifactId><version>3.14.0</version></dependency>
                </dependencies>
            </project>
            """;

    private void writeJar(String name, String mainClass) throws IOException {
        Path target = Files.createDirectories(root.resolve("target"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target.resolve(name)), manifest)) {
            out.flush();
        }
    }

    @Test
    void aJarThatCannotRun_getsItsEntryPointDeclared() throws IOException {
        writeJar("demo.jar", null);

        Optional<String> repaired = RunnableJarGuarantee.repairedPom(root, POM_WITH_DEPENDENCY, ONE_ENTRY_POINT);

        assertThat(repaired).isPresent();
        assertThat(repaired.get()).contains("<mainClass>com.demo.Main</mainClass>");
    }

    @Test
    void aJarThatAlreadyDeclaresAnEntryPoint_isLeftAlone() throws IOException {
        // The project answered the question itself — however it did so, and whichever plugin it
        // used. Nothing here needs to know which.
        writeJar("demo.jar", "com.demo.Main");

        assertThat(RunnableJarGuarantee.repairedPom(root, POM_WITH_DEPENDENCY, ONE_ENTRY_POINT)).isEmpty();
    }

    @Test
    void sourcesNamingNoSingleEntryPoint_yieldNoOpinion() throws IOException {
        writeJar("demo.jar", null);
        Map<String, String> library = Map.of(
                "src/main/java/com/demo/Util.java", "package com.demo;\npublic class Util {}\n");

        // A library owes no entry point; two mains would be a choice this must not make.
        assertThat(RunnableJarGuarantee.repairedPom(root, POM_WITH_DEPENDENCY, library)).isEmpty();
    }

    @Test
    void noBuiltJarToInspect_yieldsNoOpinion() {
        assertThat(RunnableJarGuarantee.repairedPom(root, POM_WITH_DEPENDENCY, ONE_ENTRY_POINT)).isEmpty();
    }

    @Test
    void declaredDependenciesArePlacedBesideTheJar_soAPlainJarCanStillFindThem() throws IOException {
        writeJar("demo.jar", null);

        String repaired = RunnableJarGuarantee.repairedPom(root, POM_WITH_DEPENDENCY, ONE_ENTRY_POINT).orElseThrow();

        assertThat(repaired).contains("maven-dependency-plugin").contains("copy-dependencies");
        assertThat(repaired).contains("<addClasspath>true</addClasspath>");
    }

    @Test
    void aProjectWithNoDependencies_needsNoBundling() throws IOException {
        writeJar("demo.jar", null);
        String bare = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>demo</artifactId>
                </project>
                """;

        String repaired = RunnableJarGuarantee.repairedPom(root, bare, ONE_ENTRY_POINT).orElseThrow();

        assertThat(repaired).contains("<mainClass>com.demo.Main</mainClass>");
        assertThat(repaired).doesNotContain("maven-dependency-plugin");
    }

    @Test
    void theRepairNamesNoFramework() throws IOException {
        writeJar("demo.jar", null);

        String repaired = RunnableJarGuarantee.repairedPom(root, POM_WITH_DEPENDENCY, ONE_ENTRY_POINT).orElseThrow();

        assertThat(repaired).doesNotContain("spring").doesNotContain("Spring")
                            .doesNotContain("starter").doesNotContain("shade");
    }

    @Test
    void existingBuildConfigurationSurvivesTheRepair() throws IOException {
        writeJar("demo.jar", null);
        String withBuild = POM_WITH_DEPENDENCY.replace("</project>",
                "    <build>\n        <plugins>\n            <plugin>"
                + "<artifactId>maven-surefire-plugin</artifactId></plugin>\n"
                + "        </plugins>\n    </build>\n</project>");

        String repaired = RunnableJarGuarantee.repairedPom(root, withBuild, ONE_ENTRY_POINT).orElseThrow();

        assertThat(repaired).contains("maven-surefire-plugin");   // the project's own config kept
        assertThat(repaired).contains("<mainClass>com.demo.Main</mainClass>");
    }
}
