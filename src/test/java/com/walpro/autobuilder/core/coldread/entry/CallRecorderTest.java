package com.walpro.autobuilder.core.coldread.entry;

import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Call;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-EQ: the calls a claim's test makes into a project are recorded with what each answered. The project here is
 * compiled and its "claim" run for real -- a record that is only right on paper would be no record.
 */
class CallRecorderTest {

    private static final String DIAL = """
            package org.x.dial;

            import java.util.List;

            public class Dial {
                private int turns;

                public static Setting of(String digit) {
                    if (digit == null || digit.length() != 1) throw new IllegalArgumentException("one digit, not " + digit);
                    return check(digit) < 5 ? Setting.LOW : Setting.HIGH;
                }

                public static int check(String digit) {
                    return Integer.parseInt(digit);
                }

                public void turn() {
                    turns++;
                }

                public int turns() {
                    return turns;
                }

                public <T> List<T> twice(T one, int... more) throws java.io.IOException {
                    if (more.length > 2) throw new java.io.IOException("too many");
                    return List.of(one, one);
                }

                public Mark mark() {
                    return new Mark();
                }

                @Override
                public boolean equals(Object o) {
                    return o instanceof Dial d && d.turns == turns;
                }

                @Override
                public int hashCode() {
                    return turns;
                }
            }
            """;

    private static final String SETTING = "package org.x.dial;\npublic enum Setting { LOW, HIGH }\n";
    private static final String MARK = "package org.x.dial;\npublic class Mark { }\n";

    /** A claim's test as the adapter names it; no test framework is needed to call the project. */
    private static final String CLAIM = """
            package org.x.dial;

            public class RevClaimK1Test {
                public static void claim() throws Throwable {
                    Dial.of("3");
                    try {
                        Dial.of(null);
                    } catch (IllegalArgumentException expected) {
                        // the claim is that this is refused
                    }
                    Dial d = new Dial();
                    d.turn();
                    d.turns();
                    d.twice("a", 1, 2);
                    Runnable later = () -> Dial.check("7");
                    later.run();
                    d.mark();
                    d.equals(new Dial());
                }
            }
            """;

    private static final String OTHER = """
            package org.x.dial;

            public class Elsewhere {
                public static void use() {
                    Dial.of("9");
                }
            }
            """;

    private static void compileAndRun(Path tree, Path classes, String... run) throws Exception {
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(tree.resolve("src"))) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString()));
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-nowarn"));
        args.addAll(files);
        assertThat(javac.run(null, null, null, args.toArray(String[]::new))).as("the tree compiles").isZero();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) {
            for (String r : run) {
                String cls = r.substring(0, r.lastIndexOf('.'));
                loader.loadClass(cls).getMethod(r.substring(r.lastIndexOf('.') + 1)).invoke(null);
            }
        }
    }

    private static Path project(Path tree) throws Exception {
        Path main = tree.resolve("src/main/java/org/x/dial");
        Files.createDirectories(main);
        Files.writeString(main.resolve("Dial.java"), DIAL);
        Files.writeString(main.resolve("Setting.java"), SETTING);
        Files.writeString(main.resolve("Mark.java"), MARK);
        Path test = tree.resolve("src/test/java/org/x/dial");
        Files.createDirectories(test);
        Files.writeString(test.resolve("RevClaimK1Test.java"), CLAIM);
        Files.writeString(test.resolve("Elsewhere.java"), OTHER);
        return main;
    }

    @Test
    void theCallsAClaimsTestMakesAreRecordedWithWhatEachAnswered(@TempDir Path tree, @TempDir Path classes) throws Exception {
        project(tree);
        Path calls = tree.resolve(".recorded-calls");

        Map<Path, String> before = CallRecorder.instrument(tree, calls, "RevClaim");
        compileAndRun(tree, classes, "org.x.dial.RevClaimK1Test.claim", "org.x.dial.Elsewhere.use");

        assertThat(before).isNotEmpty();
        assertThat(CallRecorder.read(calls, "RevClaimK1Test")).extracting(Call::said).containsExactly(
                // what a way in calls on its way (check, inside of) is not a call of the claim
                "Dial.of(\"3\") returned LOW",
                "Dial.of(null) raised IllegalArgumentException: one digit, not null",
                "Dial.turn() returns nothing",
                "Dial.turns() returned 1",
                "Dial.twice(\"a\", [1, 2]) returned [a, a]",
                // a call from a lambda of the claim's test is the claim's call
                "Dial.check(\"7\") returned 7",
                // an object that says nothing of itself is named by its type
                "Dial.mark() returned Mark",
                "Dial.equals(Dial) returned false");
        assertThat(CallRecorder.read(calls, "RevClaimK1Test").get(0).answer()).isEqualTo("Dial.of returned LOW");
        // a call made from anywhere else is nobody's claim
        assertThat(CallRecorder.read(calls, "Elsewhere")).isEmpty();
        assertThat(CallRecorder.read(calls, "RevClaimK2Test")).isEmpty();
    }

    @Test
    void theSourcesArePutBackAsTheyWere(@TempDir Path tree) throws Exception {
        Path main = project(tree);

        Map<Path, String> before = CallRecorder.instrument(tree, tree.resolve(".recorded-calls"), "RevClaim");
        assertThat(Files.readString(main.resolve("Dial.java"))).contains(CallRecorder.BEHIND);
        CallRecorder.restore(before);

        assertThat(Files.readString(main.resolve("Dial.java"))).isEqualTo(DIAL);
        assertThat(Files.readString(main.resolve("Setting.java"))).isEqualTo(SETTING);
        assertThat(tree.resolve("src/main/java/revcalls/Recorded.java")).doesNotExist();
    }

    @Test
    void aTreeWithNoWayInIsLeftAlone(@TempDir Path tree) throws Exception {
        Path main = tree.resolve("src/main/java/org/x");
        Files.createDirectories(main);
        Files.writeString(main.resolve("Setting.java"), "package org.x;\npublic enum Setting { LOW, HIGH }\n");

        assertThat(CallRecorder.instrument(tree, tree.resolve(".recorded-calls"), "RevClaim")).isEmpty();
        assertThat(tree.resolve("src/main/java/revcalls")).doesNotExist();
    }
}
