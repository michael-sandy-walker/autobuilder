package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.merge.CapabilityCut;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStubTest {

    private static final String SERVICE = """
            package p;
            public class BookService {
                private final java.util.List<String> books = new java.util.ArrayList<>();
                public BookService() { books.add("x"); }
                public String create(String t) { books.add(t); return t; }
                public int count() { return books.size(); }
                @Bean public Object helper() { return new Object(); }
            }
            """;
    private static final String APP = """
            package p;
            public class App {
                public static void main(String[] a) { System.out.println("up"); }
                public String name() { return "app"; }
            }
            """;
    private final Map<String, String> source = Map.of(
            "src/main/java/p/BookService.java", SERVICE,
            "src/main/java/p/App.java", APP);

    @Test
    void aTypeIdStubsEveryMethodButConstructorsAndBeanMethods() {
        var inventory = CapabilityCut.inventory(source);
        CapabilityStub.Result r = CapabilityStub.stub(source, List.of("BookService"), inventory);

        assertThat(r.methods()).containsExactlyInAnyOrder("BookService.create", "BookService.count");
        String out = r.stubbed().get("src/main/java/p/BookService.java");
        assertThat(out).contains("books.add(\"x\")")                      // constructor kept
                .contains("return new Object()")                          // @Bean kept
                .doesNotContain("return books.size()")
                .contains("UnsupportedOperationException");
        assertThat(r.stubbed()).doesNotContainKey("src/main/java/p/App.java");
    }

    @Test
    void aMemberIdStubsOnlyThatMemberAndMainIsNeverStubbed() {
        var inventory = CapabilityCut.inventory(source);
        CapabilityStub.Result r = CapabilityStub.stub(source, List.of("BookService.count", "App"), inventory);

        assertThat(r.methods()).containsExactlyInAnyOrder("BookService.count", "App.name");
        assertThat(r.stubbed().get("src/main/java/p/BookService.java")).contains("books.add(t)");
        assertThat(r.stubbed().get("src/main/java/p/App.java")).contains("System.out.println(\"up\")");
    }

    @Test
    void fieldIdsAndUnknownIdsStubNothing() {
        var inventory = CapabilityCut.inventory(source);
        CapabilityStub.Result r = CapabilityStub.stub(source, List.of("BookService.books", "Nope"), inventory);
        assertThat(r.stubbed()).isEmpty();
        assertThat(r.methods()).isEmpty();
    }
}
