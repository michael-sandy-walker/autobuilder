package com.walpro.autobuilder.core.coldread.entry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryAndCommandAdapterTest {

    private static final String ZONE = """
            package org.x.label;
            public enum Zone {
                NORTH, EAST;
                public static Zone of(String postcode) { return NORTH; }
            }
            """;
    private static final String LABELER = """
            package org.x.label;
            public class Labeler {
                private int n;
                public Labeler() { }
                public static char checkChar(String body) { return 'A'; }
                public String next(String postcode) { return Zone.of(postcode) + "-" + (++n); }
                public static boolean isValid(String code) { return code != null; }
                String hidden() { return ""; }
            }
            """;
    private static final String CLI = """
            package org.x.cli;
            public class Main { public static void main(String[] args) { System.exit(0); } }
            """;
    private final Map<String, String> library = Map.of(
            "src/main/java/org/x/label/Zone.java", ZONE, "src/main/java/org/x/label/Labeler.java", LABELER);
    private final LibraryStyleAdapter called = new LibraryStyleAdapter(null, "test", "junit-jupiter");
    private final ProgramAdapter started = new ProgramAdapter(null, null, "package", 1000);

    @Test
    void aLibrarysWaysInArePublicMethodsWithABodyNotConstructorsOrHiddenOnes() {
        assertThat(called.find(library, null)).extracting(EntryPoint::label).containsExactlyInAnyOrder(
                "Zone.of(String)", "Labeler.checkChar(String)", "Labeler.next(String)", "Labeler.isValid(String)");
        assertThat(started.find(library, null)).isEmpty();
    }

    @Test
    void aProgramWithoutRoutesIsStartedThroughItsEntry() {
        var points = started.find(Map.of("src/main/java/org/x/cli/Main.java", CLI), null);
        assertThat(points).extracting(EntryPoint::label).containsExactly("program Main");
        assertThat(points.get(0).kind()).isEqualTo(EntryKind.COMMAND_STYLE);
        assertThat(points.get(0).method()).isEqualTo("main");
    }

    @Test
    void aLibraryClaimIsWrittenAsATestClassInTheHomePackageWithTheOtherPackagesImported() {
        String src = called.source("org.x.label", "import org.y.Other;\n", "RevClaimK1Test", "assertTrue(true);");
        assertThat(src).startsWith("package org.x.label;").contains("import org.y.Other;")
                .contains("class RevClaimK1Test").contains("        assertTrue(true);");
        assertThat(LibraryStyleAdapter.packageOf("org.x.label.Labeler", "Labeler")).isEqualTo("org.x.label");
        assertThat(LibraryStyleAdapter.packageOf("Top", "Top")).isEmpty();
        assertThat(called.source("", "", "C", "x();")).doesNotContain("package");
        assertThat(LibraryStyleAdapter.homePackage(called.find(library, null))).isEqualTo("org.x.label");
    }
}
