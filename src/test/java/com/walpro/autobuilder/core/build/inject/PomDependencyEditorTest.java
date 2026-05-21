package com.walpro.autobuilder.core.build.inject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PomDependencyEditorTest {

    private static final String BASE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;

    @Test
    void appendDependency_splicesBeforeClosingTag() {
        String out = PomDependencyEditor.appendDependency(BASE_POM, "g", "a", null);
        assertThat(out).isNotNull();
        int closeIdx = out.indexOf("</dependencies>");
        int newDepIdx = out.indexOf("<artifactId>a</artifactId>");
        int oldDepIdx = out.indexOf("<artifactId>spring-boot-starter-web</artifactId>");
        assertThat(oldDepIdx).isLessThan(newDepIdx);
        assertThat(newDepIdx).isLessThan(closeIdx);
    }

    @Test
    void appendDependency_includesVersionWhenProvided() {
        String out = PomDependencyEditor.appendDependency(BASE_POM, "g", "a", "1.0.0");
        assertThat(out).contains("<version>1.0.0</version>");
    }

    @Test
    void appendDependency_blankVersionOmitsElement() {
        String out = PomDependencyEditor.appendDependency(BASE_POM, "g", "a", "  ");
        assertThat(out).doesNotContain("<version>");
    }

    @Test
    void appendDependency_noClosingTag_returnsNull() {
        assertThat(PomDependencyEditor.appendDependency("<project></project>", "g", "a", null)).isNull();
    }

    @Test
    void containsDependency_truePresent_falseAbsent_falseOnNullsOrBlanks() {
        assertThat(PomDependencyEditor.containsDependency(BASE_POM, "org.springframework.boot", "spring-boot-starter-web")).isTrue();
        assertThat(PomDependencyEditor.containsDependency(BASE_POM, "g", "not-there")).isFalse();
        assertThat(PomDependencyEditor.containsDependency(null, "g", "a")).isFalse();
        assertThat(PomDependencyEditor.containsDependency(BASE_POM, "g", null)).isFalse();
        assertThat(PomDependencyEditor.containsDependency(BASE_POM, "", "a")).isFalse();
        assertThat(PomDependencyEditor.containsDependency(BASE_POM, "g", " ")).isFalse();
    }

    @Test
    void containsDependency_sameArtifactIdDifferentGroup_isFalse() {
        // Real collision: commons-logging artifactId exists in two unrelated groups.
        assertThat(PomDependencyEditor.containsDependency(BASE_POM, "org.unrelated", "spring-boot-starter-web"))
                .as("same artifactId but different groupId must NOT count as present")
                .isFalse();
    }

    @Test
    void containsDependency_orderAgnostic_artifactIdBeforeGroupId() {
        String pom = """
                <project><dependencies>
                <dependency>
                    <artifactId>my-art</artifactId>
                    <groupId>my-grp</groupId>
                </dependency>
                </dependencies></project>
                """;
        assertThat(PomDependencyEditor.containsDependency(pom, "my-grp", "my-art")).isTrue();
    }

    @Test
    void containsDependency_doesNotConflateAcrossSeparateBlocks() {
        // groupId of dep A + artifactId of dep B must not be reported as present.
        String pom = """
                <project><dependencies>
                <dependency>
                    <groupId>g.one</groupId>
                    <artifactId>a.one</artifactId>
                </dependency>
                <dependency>
                    <groupId>g.two</groupId>
                    <artifactId>a.two</artifactId>
                </dependency>
                </dependencies></project>
                """;
        assertThat(PomDependencyEditor.containsDependency(pom, "g.one", "a.two")).isFalse();
        assertThat(PomDependencyEditor.containsDependency(pom, "g.one", "a.one")).isTrue();
        assertThat(PomDependencyEditor.containsDependency(pom, "g.two", "a.two")).isTrue();
    }
}
