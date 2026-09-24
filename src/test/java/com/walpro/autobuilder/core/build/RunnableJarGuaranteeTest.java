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

    /**
     * T4-DO (routing, 2026-09-21). Between writing the pom and the next package the JAR still
     * carries no {@code Main-Class} — the jar is the authority, and it has not been rebuilt yet —
     * so every caller in that window gets the same non-empty answer. At the closure edge that
     * never showed, because its one call site returned immediately. On the per-cycle route it
     * does: the guard fired FIVE times in run 2026-09-21_00-32-59, and freshness does not stop it
     * because {@code artifactOlderThanSources} walks {@code src/} and never reads {@code pom.xml}.
     * Without the idempotence check this appends the plugin block once per firing.
     */
    @Test
    void theRepairIsNotWrittenTwiceWhileTheJarAwaitsItsRebuild() throws IOException {
        writeJar("demo.jar", null);
        writeProjectOnDisk(POM_WITH_DEPENDENCY);

        assertThat(RunnableJarGuarantee.repairOnDisk(root)).isTrue();
        String afterFirst = Files.readString(root.resolve("pom.xml"));

        // The jar is untouched — exactly the state the next four firings of the gate would see.
        assertThat(RunnableJarGuarantee.repairOnDisk(root)).isFalse();

        assertThat(Files.readString(root.resolve("pom.xml"))).isEqualTo(afterFirst);
        assertThat(afterFirst.split("<mainClass>", -1)).hasSize(2);
        assertThat(afterFirst.split("maven-jar-plugin", -1)).hasSize(2);
    }

    /** The disk form decides nothing the in-memory form does not: a library is still left alone. */
    @Test
    void repairOnDisk_leavesAProjectThatOwesNoEntryPointAlone() throws IOException {
        writeJar("demo.jar", null);
        Path src = root.resolve("src/main/java/com/demo/Library.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com.demo;\npublic class Library { public int add(int a) { return a; } }\n");
        Files.writeString(root.resolve("pom.xml"), POM_WITH_DEPENDENCY);

        assertThat(RunnableJarGuarantee.repairOnDisk(root)).isFalse();
        assertThat(Files.readString(root.resolve("pom.xml"))).isEqualTo(POM_WITH_DEPENDENCY);
    }

    /** No pom is not a project this can repair, and it must not throw looking for one. */
    @Test
    void repairOnDisk_withNoPom_abstains() throws IOException {
        writeJar("demo.jar", null);
        writeProjectSources();

        assertThat(RunnableJarGuarantee.repairOnDisk(root)).isFalse();
    }

    private void writeProjectOnDisk(String pom) throws IOException {
        writeProjectSources();
        Files.writeString(root.resolve("pom.xml"), pom);
    }

    private void writeProjectSources() throws IOException {
        for (Map.Entry<String, String> e : ONE_ENTRY_POINT.entrySet()) {
            Path f = root.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
    }

    // ── T4-DW: evidence that retires the archive-entry-point item ────────────────────────────

    /** The item's own instruction is "declare the entry point the sources provide": done in the pom. */
    @Test
    void entryPointDeclared_whenThePomDeclaresTheOneTheSourcesName_evenBeforeARebuild() throws IOException {
        writeJar("demo.jar", null);
        writeProjectOnDisk(POM_WITH_DEPENDENCY);
        assertThat(RunnableJarGuarantee.entryPointDeclared(root)).isFalse();

        assertThat(RunnableJarGuarantee.repairOnDisk(root)).isTrue();

        // The jar still carries no Main-Class; the declaration alone answers the item.
        assertThat(RunnableJarGuarantee.entryPointDeclared(root)).isTrue();
    }

    @Test
    void entryPointDeclared_whenTheArchiveAlreadyNamesAMainClass() throws IOException {
        writeJar("demo.jar", "com.demo.Main");

        assertThat(RunnableJarGuarantee.entryPointDeclared(root)).isTrue();
    }

    /** A declaration of some OTHER class is not the instruction carried out. */
    @Test
    void entryPointNotDeclared_whenThePomNamesADifferentClass() throws IOException {
        writeJar("demo.jar", null);
        writeProjectOnDisk(POM_WITH_DEPENDENCY.replace("</project>",
                "<build><plugins><plugin><configuration><mainClass>com.demo.Other</mainClass>"
                        + "</configuration></plugin></plugins></build></project>"));

        assertThat(RunnableJarGuarantee.entryPointDeclared(root)).isFalse();
    }

    /** An abstention is not a pass: no pom, no archive, nothing to read answers false. */
    @Test
    void entryPointNotDeclared_whenNothingCanBeEstablished() {
        assertThat(RunnableJarGuarantee.entryPointDeclared(root)).isFalse();
        assertThat(RunnableJarGuarantee.entryPointDeclared(null)).isFalse();
    }
}
