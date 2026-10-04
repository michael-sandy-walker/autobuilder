package com.walpro.autobuilder.core.build.inject;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic verification of the T4-BN-3 test-dep slice: the pure core proves it resolves the
 * dominant real test-compile error (missing test deps) without needing a live run.
 */
class TestDependencyEnsurerTest {

    private static final String SB_POM = """
            <project>
              <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>3.3.0</version>
              </parent>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void springBootPom_withoutTestStarter_getsItAddedWithTestScope() {
        Optional<String> out = TestDependencyEnsurer.injectTestStarterIfNeeded(SB_POM);

        assertThat(out).isPresent();
        assertThat(out.get()).contains("spring-boot-starter-test");
        assertThat(out.get()).contains("<scope>test</scope>");
        // existing dependency preserved
        assertThat(out.get()).contains("spring-boot-starter-web");
        // no pinned version (managed by the parent the project already declares)
        assertThat(out.get()).doesNotContain("<artifactId>spring-boot-starter-test</artifactId>\n        <version>");
    }

    @Test
    void idempotent_whenTestStarterAlreadyPresent() {
        String withTest = SB_POM.replace("</dependencies>",
                "  <dependency><groupId>org.springframework.boot</groupId>"
                        + "<artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>\n</dependencies>");
        assertThat(TestDependencyEnsurer.injectTestStarterIfNeeded(withTest)).isEmpty();
    }

    @Test
    void noOp_whenNotSpringBoot() {
        String plain = """
                <project>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        assertThat(TestDependencyEnsurer.injectTestStarterIfNeeded(plain)).isEmpty();
    }

    @Test
    void noOp_whenNoDependenciesBlock() {
        String noDeps = "<project><parent><artifactId>spring-boot-starter-parent</artifactId></parent></project>";
        assertThat(TestDependencyEnsurer.injectTestStarterIfNeeded(noDeps)).isEmpty();
    }

    @Test
    void noOp_onBlankOrNull() {
        assertThat(TestDependencyEnsurer.injectTestStarterIfNeeded(null)).isEmpty();
        assertThat(TestDependencyEnsurer.injectTestStarterIfNeeded("  ")).isEmpty();
    }

    @Test
    void addedBlockIsWellFormedAndBeforeClosingTag() {
        String out = TestDependencyEnsurer.injectTestStarterIfNeeded(SB_POM).orElseThrow();
        int depIdx = out.indexOf("spring-boot-starter-test");
        int closeIdx = out.indexOf("</dependencies>");
        assertThat(depIdx).isLessThan(closeIdx);
        assertThat(out).contains("</project>");
    }

    @Test
    void component_readsAndWritesPomOnDisk_whenEnabled(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base) throws Exception {
        java.nio.file.Files.createDirectories(base.resolve("output"));
        java.nio.file.Files.writeString(base.resolve("output/pom.xml"), SB_POM);
        var fs = new com.walpro.autobuilder.core.service.FileService(
                base.toString(), new com.fasterxml.jackson.databind.ObjectMapper());
        TestDependencyEnsurer ensurer = new TestDependencyEnsurer(fs);

        assertThat(ensurer.ensure()).isTrue();
        String pom = java.nio.file.Files.readString(base.resolve("output/pom.xml"));
        assertThat(pom).contains("spring-boot-starter-test").contains("<scope>test</scope>");

        // idempotent: second call is a no-op
        assertThat(ensurer.ensure()).isFalse();
    }

}
