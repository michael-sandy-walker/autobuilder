package com.walpro.autobuilder.core.persistence;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CV: the entry-point scan over an in-memory output map — the form the closure-edge coverage
 * pass needs. Ground truth for the whole mechanism is run {@code 2026-07-27_19-37-42}: 19 compiling
 * sources, every spec resource represented, and no {@code main} anywhere, so packaging died at
 * {@code Unable to find main class} after 65 cycles.
 */
class EntryPointScanMapTest {

    private static Map<String, String> files(String... pathsAndBodies) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndBodies.length; i += 2) {
            out.put(pathsAndBodies[i], pathsAndBodies[i + 1]);
        }
        return out;
    }

    @Test
    void findsAnEntryPointInMainSources() {
        assertThat(EntryPointScan.hasJavaEntryPointInSources(files(
                "src/main/java/com/example/App.java",
                "public class App { public static void main(String[] args) {} }")))
                .isTrue();
    }

    @Test
    void toleratesTheDeclarationSpacingVariants() {
        assertThat(EntryPointScan.hasJavaEntryPointInSources(files(
                "src/main/java/com/example/App.java",
                "public class App { public static  void   main ( String[] a ) {} }")))
                .isTrue();
    }

    @Test
    void aMainInTestSourcesIsNotAPackagedEntryPoint() {
        // A directory scan prefers src/main/java by walking it; a flat map cannot, so this
        // exclusion is what keeps a test harness main from reading as the application's.
        assertThat(EntryPointScan.hasJavaEntryPointInSources(files(
                "src/test/java/com/example/AppTest.java",
                "class AppTest { public static void main(String[] args) {} }")))
                .isFalse();
    }

    @Test
    void theRun19_37_42Shape_compilingSourcesWithNoEntryPoint() {
        assertThat(EntryPointScan.hasJavaEntryPointInSources(files(
                "src/main/java/com/example/demo/model/BookController.java",
                "public class BookController { public Book create(Book b) { return b; } }",
                "src/main/java/com/example/demo/model/BookService.java",
                "public class BookService { }",
                "src/main/java/com/example/demo/model/Book.java",
                "public class Book { }")))
                .isFalse();
    }

    @Test
    void abstainsOnNothingToLookAt() {
        assertThat(EntryPointScan.hasJavaEntryPointInSources(null)).isFalse();
        assertThat(EntryPointScan.hasJavaEntryPointInSources(Map.of())).isFalse();
        assertThat(EntryPointScan.hasJavaEntryPointInSources(files("pom.xml", "<project/>"))).isFalse();
    }

    @Test
    void aNullSourceBodyIsNotAnEntryPoint() {
        Map<String, String> withNull = new LinkedHashMap<>();
        withNull.put("src/main/java/com/example/App.java", null);
        assertThat(EntryPointScan.hasJavaEntryPointInSources(withNull)).isFalse();
    }

    @Test
    void theWorkItemStatesTheCapability_notTheDefect() {
        // A description embedding "no main exists" becomes unconfirmable once the work is done.
        assertThat(EntryPointScan.MISSING_ENTRY_POINT_WORK_ITEM).startsWith("Provide a runnable");
        assertThat(EntryPointScan.ENTRY_POINT_CRITERION).doesNotContain("no ");
    }
}
