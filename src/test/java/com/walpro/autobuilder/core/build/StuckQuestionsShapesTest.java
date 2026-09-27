package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.StuckQuestions.Answer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Declaration shapes the run archive never held, found by the independent review of the router's first version
 * (2026-09-27, process/ports/stuck-questions): the same simple name in two packages, a generic declaring type,
 * an interface, a package-private overload beside a varargs one, a record whose canonical constructor is written
 * out, and a type declared in both src/main and src/test. Invented coordinates.
 */
class StuckQuestionsShapesTest {

    private static Map<String, String> tree(String... pathsAndSources) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndSources.length; i += 2) out.put(pathsAndSources[i], pathsAndSources[i + 1]);
        return out;
    }

    @Test
    void aQualifiedTypeIsTheOneJavacNamedNotTheFirstOfThatSimpleName() {
        Map<String, String> t = tree("src/main/java/a/x/Foo.java", "package a.x;\npublic record Foo(int n) {}\n",
                "src/main/java/b/y/Foo.java", "package b.y;\npublic record Foo(String s) {}\n");
        Answer a = StuckQuestions.answer("the build fails: T.java: constructor Foo in record b.y.Foo cannot be applied to given types;", t).orElseThrow();
        assertThat(a.declared()).containsExactly("Foo(String s)");
    }

    @Test
    void aGenericDeclaringTypeAndAnInterfaceAreFound() {
        Map<String, String> box = tree("src/main/java/a/Box.java",
                "package a;\npublic class Box<T> {\n  public void put(T t) {}\n  public void put(T t, int i) {}\n}\n");
        assertThat(StuckQuestions.answer("the build fails: T.java: method put in class a.Box<T> cannot be applied to given types;", box)
                .orElseThrow().declared()).containsExactly("put(T t)", "put(T t, int i)");
        Map<String, String> shape = tree("src/main/java/a/Shape.java", "package a;\npublic interface Shape {\n  void m(int x);\n}\n");
        assertThat(StuckQuestions.answer("the build fails: T.java: method m in interface a.Shape cannot be applied to given types;", shape)
                .orElseThrow().declared()).containsExactly("m(int x)");
    }

    @Test
    void everyOverloadIsListedWithVarargsAndPrivateOnesMarked() {
        Map<String, String> t = tree("src/main/java/a/Foo.java",
                "package a;\npublic class Foo {\n  void bar(int a) {}\n  public void bar(String... s) {}\n  private void bar(long l) {}\n}\n");
        assertThat(StuckQuestions.answer("the build fails: T.java: method bar in class a.Foo cannot be applied to given types;", t)
                .orElseThrow().declared()).containsExactly("bar(int a)", "bar(String... s)", "bar(long l) (private)");
    }

    @Test
    void aWrittenOutCanonicalConstructorIsListedOnce() {
        Map<String, String> t = tree("src/main/java/a/P.java", "package a;\npublic record P(int x) {\n  public P(int x) { this.x = x; }\n}\n");
        assertThat(StuckQuestions.answer("the build fails: T.java: constructor P in record a.P cannot be applied to given types;", t)
                .orElseThrow().declared()).containsExactly("P(int x)");
    }

    @Test
    void theRealTypeIsPreferredToATestDoubleAndTwoRealOnesAreAmbiguous() {
        Map<String, String> t = tree("src/test/java/a/Foo.java", "package a;\npublic class Foo {\n  public int qux() { return 2; }\n}\n",
                "src/main/java/a/Foo.java", "package a;\npublic class Foo {\n  public int baz() { return 1; }\n}\n");
        assertThat(StuckQuestions.answer("the build fails: Foo has no bar()", t).orElseThrow().declared()).containsExactly("baz()");
        Map<String, String> two = tree("src/main/java/a/x/Foo.java", "package a.x;\npublic class Foo { public int baz() { return 1; } }\n",
                "src/main/java/b/y/Foo.java", "package b.y;\npublic class Foo { public int qux() { return 2; } }\n");
        assertThat(StuckQuestions.answer("the build fails: Foo has no bar()", two)).as("which Foo is not said").isEmpty();
    }
}
