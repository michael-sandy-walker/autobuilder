package com.walpro.autobuilder.analysis.structure;

import static org.assertj.core.api.Assertions.assertThat;

import com.walpro.autobuilder.testsupport.HollowVsRealFixtures;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WiringEvidenceAnalyzerTest {

    private final WiringEvidenceAnalyzer analyzer = new WiringEvidenceAnalyzer();

    @Test
    void hollowCheckout_flagsStubBodyAndNeverAssignedFields() {
        List<String> obs = analyzer.analyze(HollowVsRealFixtures.computedPricing().hollow());

        assertThat(obs).anyMatch(o -> o.contains("checkout") && o.contains("stub"));
        // The empty PriceBreakdown's fields are never assigned — the core hollowness signal.
        assertThat(obs).anyMatch(o -> o.contains("subtotal") && o.contains("never assigned"));
        assertThat(obs).anyMatch(o -> o.contains("total") && o.contains("never assigned"));
    }

    @Test
    void realCheckout_doesNotFlagStubOrUnassignedPricing() {
        List<String> obs = analyzer.analyze(HollowVsRealFixtures.computedPricing().real());

        assertThat(obs).noneMatch(o -> o.contains("checkout") && o.contains("stub"));
        assertThat(obs).noneMatch(o -> o.contains("subtotal") && o.contains("never assigned"));
    }

    @Test
    void thrownButUnmapped_flagsMissingStatusMapping() {
        Map<String, String> files = Map.of("src/main/java/A.java", """
                package a;
                public class A {
                    public String find(Long id) {
                        if (id == null) throw new IllegalArgumentException("bad");
                        return "ok";
                    }
                }
                """);
        assertThat(analyzer.analyze(files))
                .anyMatch(o -> o.contains("no HTTP status mapping"));
    }

    @Test
    void thrownWithControllerAdvice_doesNotFlagStatusMapping() {
        Map<String, String> files = Map.of(
                "src/main/java/A.java", """
                        package a;
                        public class A {
                            public String find(Long id) {
                                if (id == null) throw new IllegalArgumentException("bad");
                                return "ok";
                            }
                        }
                        """,
                "src/main/java/H.java", """
                        package a;
                        import org.springframework.web.bind.annotation.*;
                        @RestControllerAdvice
                        public class H {
                            @ExceptionHandler(IllegalArgumentException.class)
                            public String h(IllegalArgumentException e) { return e.getMessage(); }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("no HTTP status mapping"));
    }

    @Test
    void entityWithoutPersistenceCall_flagsMissingPersistence() {
        Map<String, String> files = Map.of(
                "src/main/java/Order.java", """
                        package a;
                        import jakarta.persistence.Entity;
                        @Entity
                        public class Order { private Long id; public Long getId() { return id; } }
                        """,
                "src/main/java/Svc.java", """
                        package a;
                        public class Svc {
                            public Order make() { return new Order(); } // never saved
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .anyMatch(o -> o.contains("no save/persist call"));
    }

    @Test
    void entityWithSaveCall_doesNotFlagMissingPersistence() {
        Map<String, String> files = Map.of(
                "src/main/java/Order.java", """
                        package a;
                        import jakarta.persistence.Entity;
                        @Entity
                        public class Order { private Long id; }
                        """,
                "src/main/java/Svc.java", """
                        package a;
                        public class Svc {
                            private final OrderRepository repo;
                            public Svc(OrderRepository repo) { this.repo = repo; }
                            public Order make() { return repo.save(new Order()); }
                        }
                        """);
        assertThat(analyzer.analyze(files))
                .noneMatch(o -> o.contains("no save/persist call"));
    }

    @Test
    void unreadParameter_isFlagged() {
        Map<String, String> files = Map.of("src/main/java/A.java", """
                package a;
                public class A {
                    public int doubleIt(int value) { return 2; } // value ignored
                }
                """);
        assertThat(analyzer.analyze(files))
                .anyMatch(o -> o.contains("value") && o.contains("never reads"));
    }

    @Test
    void emptyOrNonJava_returnsEmpty() {
        assertThat(analyzer.analyze(null)).isEmpty();
        assertThat(analyzer.analyze(Map.of())).isEmpty();
        assertThat(analyzer.analyze(Map.of("README.md", "not java"))).isEmpty();
    }
}
