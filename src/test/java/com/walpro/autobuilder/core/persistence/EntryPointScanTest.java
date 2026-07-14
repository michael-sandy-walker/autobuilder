package com.walpro.autobuilder.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EntryPointScanTest {

    @TempDir
    Path tempDir;

    private void write(String relative, String content) throws IOException {
        Path p = tempDir.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void findsEntryPointUnderMainSources() throws IOException {
        write("src/main/java/com/example/App.java",
                "public class App { public static void main(String[] args) {} }");
        assertThat(EntryPointScan.hasJavaEntryPoint(tempDir)).isTrue();
    }

    @Test
    void ignoresTestSourcesWhenMainSourceRootExists() throws IOException {
        write("src/main/java/com/example/Service.java", "public class Service {}");
        write("src/test/java/com/example/AppTest.java",
                "public class AppTest { public static void main(String[] args) {} }");
        assertThat(EntryPointScan.hasJavaEntryPoint(tempDir)).isFalse();
    }

    @Test
    void scansWholeTreeWithoutConventionalLayout() throws IOException {
        write("anywhere/Main.java",
                "class Main { static void main(String[] a) {} }");
        assertThat(EntryPointScan.hasJavaEntryPoint(tempDir)).isTrue();
    }

    @Test
    void absentWithoutMainMethod() throws IOException {
        write("src/main/java/com/example/Service.java",
                "public class Service { void mainly() {} }");
        assertThat(EntryPointScan.hasJavaEntryPoint(tempDir)).isFalse();
    }

    @Test
    void absentOnMissingOrNullRoot() {
        assertThat(EntryPointScan.hasJavaEntryPoint(null)).isFalse();
        assertThat(EntryPointScan.hasJavaEntryPoint(tempDir.resolve("nope"))).isFalse();
    }
}
