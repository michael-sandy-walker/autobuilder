package com.walpro.autobuilder.core.coldread.entry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-EQ: what a project's source declares about how it is used -- whether a method is called on the type or on an
 * instance, how a type is created, the constants of an enum in order, and the values written into the code. A small
 * invented library stands for the kind.
 */
class DeclarationsTest {

    @TempDir
    Path root;

    private void write(String path, String text) throws Exception {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private void library() throws Exception {
        write("src/main/java/org/dial/Level.java", """
                package org.dial;
                public enum Level { LOW, MID, HIGH;
                    public static Level of(int n) { return values()[n % 3]; }
                }
                """);
        write("src/main/java/org/dial/Dial.java", """
                package org.dial;
                import java.util.List;
                public class Dial {
                    private static final String LETTERS = "ACEGK";
                    private static final int[] STEPS = {2, -1, 4};
                    static final long LIMIT = -9L;
                    private static final List<String> NAMES = List.of("a");
                    private static final String NOTHING = null;
                    private final int start = 3;
                    private int count;
                    public static char code(String body) { return LETTERS.charAt(body.length() % 5); }
                    public String next(String key) { count++; return key + count; }
                    private void helper() { }
                    public static void main(String[] args) { }
                }
                """);
        write("src/main/java/org/dial/Meter.java", """
                package org.dial;
                public class Meter {
                    public Meter(int size) { }
                    Meter() { }
                    public int size() { return 1; }
                }
                """);
        write("src/main/java/org/dial/Hidden.java", """
                package org.dial;
                class Hidden {
                    static final String KEY = "k";
                    public void run() { }
                }
                """);
        write("src/test/java/org/dial/DialTest.java", """
                package org.dial;
                class DialTest { static final String IGNORED = "x"; }
                """);
    }

    @Test
    void aMethodSaysWhetherItIsCalledOnTheTypeOrOnAnInstance_andATypeHowItIsCreated() throws Exception {
        library();
        assertThat(Declarations.of(root, true)).contains(
                "class Dial -- created with new Dial()",
                "static char Dial.code(String body) -- called on Dial itself",
                "String Dial.next(String key) -- called on an instance of Dial, not on Dial itself",
                "class Meter -- created with new Meter(int size)",
                "int Meter.size() -- called on an instance of Meter, not on Meter itself",
                "enum Level",
                "static Level Level.of(int n) -- called on Level itself");
        // a private method, a program's main, and a type its users cannot reach are not how it is used
        assertThat(Declarations.of(root, true)).noneMatch(l -> l.contains("helper") || l.contains("main(") || l.contains("Hidden.run"));
    }

    @Test
    void theValuesWrittenIntoTheCodeAreStated_whateverTheirVisibility_andNothingComputed() throws Exception {
        library();
        assertThat(Declarations.of(root, false)).containsExactlyInAnyOrder(
                "constant Dial.LETTERS = \"ACEGK\"",
                "constant Dial.STEPS = { 2, -1, 4 }",
                "constant Dial.LIMIT = -9L",
                "constant Hidden.KEY = \"k\"",
                "enum Level -- its constants, in this order: LOW, MID, HIGH");
    }

    @Test
    void noSourceDeclaresNothing() {
        assertThat(Declarations.of(root.resolve("absent"), true)).isEmpty();
        assertThat(Declarations.of(null, true)).isEmpty();
    }
}
