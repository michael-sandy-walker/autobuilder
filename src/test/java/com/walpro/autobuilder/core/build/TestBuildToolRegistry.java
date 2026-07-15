package com.walpro.autobuilder.core.build;

import java.util.List;

/**
 * T4-AU test fixture: a {@link BuildToolProbeProperties} populated with the same registry data
 * {@code application.yml} ships, so unit tests exercise the classifier without a Spring context.
 * Ecosystem literals are fine here — test fixtures keep specifics per the memory carve-out.
 */
public final class TestBuildToolRegistry {

    private TestBuildToolRegistry() {}

    public static BuildToolProbeProperties props() {
        BuildToolProbeProperties p = new BuildToolProbeProperties();
        p.getToolDetection().put("pom.xml", "maven");
        p.getToolDetection().put("build.gradle", "gradle");
        p.getToolDetection().put("build.gradle.kts", "gradle");
        p.getToolDetection().put("settings.gradle", "gradle");
        p.getToolDetection().put("settings.gradle.kts", "gradle");
        p.getOutputDirectories().put("maven", List.of("target"));
        p.getOutputDirectories().put("gradle", List.of("build", ".gradle"));
        p.getTestDirectories().put("maven", List.of("src/test"));
        p.getTestDirectories().put("gradle", List.of("src/test"));
        p.getResourceDirectories().put("maven", List.of("src/main/resources", "src/test/resources"));
        p.getResourceDirectories().put("gradle", List.of("src/main/resources", "src/test/resources"));
        p.setSourceExtensions(List.of(".java"));
        return p;
    }

    public static FileRoleClassifier classifier() {
        return new FileRoleClassifier(props());
    }
}
