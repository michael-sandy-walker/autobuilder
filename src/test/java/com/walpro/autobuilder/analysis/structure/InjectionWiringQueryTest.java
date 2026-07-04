package com.walpro.autobuilder.analysis.structure;

import com.walpro.autobuilder.analysis.structure.InjectionWiringQuery.UnresolvedInjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-BW: the injection-wiring query proves, execution-free, that every injection point whose
 * dependency type is project-declared has a bean source. Ground truth is false completion #3
 * (run 13, 2026-07-04): a controller field-injected a service whose container marker a
 * full-file rewrite had dropped — compiled, packaged, passed 170/170 text criteria, and died
 * at context refresh.
 */
class InjectionWiringQueryTest {

    private static final String CONTROLLER_INJECTING_SERVICE = """
            package com.example.app.web;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class ItemController {
                @Autowired
                private ItemService itemService;
            }
            """;

    private static final String SERVICE_WITH_MARKER = """
            package com.example.app.service;
            import org.springframework.stereotype.Service;
            @Service
            public class ItemService {
                public String find(Long id) { return null; }
            }
            """;

    private static final String SERVICE_WITHOUT_MARKER = """
            package com.example.app.service;
            public class ItemService {
                public String find(Long id) { return null; }
            }
            """;

    @Test
    void run13Shape_injectedServiceLostItsMarker_isUnresolved() {
        List<UnresolvedInjection> unresolved = InjectionWiringQuery.unresolvedInjections(Map.of(
                "web/ItemController.java", CONTROLLER_INJECTING_SERVICE,
                "service/ItemService.java", SERVICE_WITHOUT_MARKER));

        assertThat(unresolved).hasSize(1);
        assertThat(unresolved.get(0).injectedType()).isEqualTo("ItemService");
        assertThat(unresolved.get(0).enclosingType()).isEqualTo("ItemController");
        assertThat(unresolved.get(0).describe())
                .contains("ItemService").contains("ItemController")
                .contains("no container-managed source provides it");
    }

    @Test
    void markerPresent_resolves() {
        assertThat(InjectionWiringQuery.unresolvedInjections(Map.of(
                "web/ItemController.java", CONTROLLER_INJECTING_SERVICE,
                "service/ItemService.java", SERVICE_WITH_MARKER))).isEmpty();
    }

    @Test
    void injectedInterface_satisfiedByMarkedImplementation() {
        assertThat(InjectionWiringQuery.unresolvedInjections(Map.of(
                "web/ItemController.java", CONTROLLER_INJECTING_SERVICE,
                "service/ItemService.java", "public interface ItemService {}",
                "service/ItemServiceImpl.java", """
                        import org.springframework.stereotype.Service;
                        @Service
                        public class ItemServiceImpl implements ItemService {}
                        """))).isEmpty();
    }

    @Test
    void injectedInterface_withNoMarkedImplementation_isUnresolved() {
        List<UnresolvedInjection> unresolved = InjectionWiringQuery.unresolvedInjections(Map.of(
                "web/ItemController.java", CONTROLLER_INJECTING_SERVICE,
                "service/ItemService.java", "public interface ItemService {}",
                "service/ItemServiceImpl.java", "public class ItemServiceImpl implements ItemService {}"));

        assertThat(unresolved).extracting(UnresolvedInjection::injectedType).containsExactly("ItemService");
    }

    @Test
    void factoryMethodReturnType_isABeanSource() {
        assertThat(InjectionWiringQuery.unresolvedInjections(Map.of(
                "web/ItemController.java", CONTROLLER_INJECTING_SERVICE,
                "service/ItemService.java", SERVICE_WITHOUT_MARKER,
                "config/AppConfig.java", """
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;
                        @Configuration
                        public class AppConfig {
                            @Bean
                            public ItemService itemService() { return new ItemService(); }
                        }
                        """))).isEmpty();
    }

    @Test
    void singleConstructorOfMarkedClass_paramsAreInjectionPoints() {
        List<UnresolvedInjection> unresolved = InjectionWiringQuery.unresolvedInjections(Map.of(
                "service/LoanService.java", """
                        import org.springframework.stereotype.Service;
                        @Service
                        public class LoanService {
                            private final AuditTrail audit;
                            public LoanService(AuditTrail audit) { this.audit = audit; }
                        }
                        """,
                "service/AuditTrail.java", "public class AuditTrail {}"));

        assertThat(unresolved).extracting(UnresolvedInjection::injectedType).containsExactly("AuditTrail");
        assertThat(unresolved.get(0).kind()).isEqualTo("constructor");
    }

    @Test
    void multipleUnannotatedConstructors_staySilent() {
        // The container picks the no-arg constructor here; flagging params of the other
        // constructor would be a false block.
        assertThat(InjectionWiringQuery.unresolvedInjections(Map.of(
                "service/LoanService.java", """
                        import org.springframework.stereotype.Service;
                        @Service
                        public class LoanService {
                            private AuditTrail audit;
                            public LoanService() {}
                            public LoanService(AuditTrail audit) { this.audit = audit; }
                        }
                        """,
                "service/AuditTrail.java", "public class AuditTrail {}"))).isEmpty();
    }

    @Test
    void genericInjectionTargets_staySilent() {
        // Collections/providers of beans are container-resolved shapes this query does not model.
        assertThat(InjectionWiringQuery.unresolvedInjections(Map.of(
                "web/ItemController.java", """
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.web.bind.annotation.RestController;
                        import java.util.List;
                        @RestController
                        public class ItemController {
                            @Autowired
                            private List<ItemHandler> handlers;
                        }
                        """,
                "web/ItemHandler.java", "public class ItemHandler {}"))).isEmpty();
    }

    @Test
    void externallyDeclaredTypes_staySilent() {
        // The injected type is not in the file set — the container may provide it from
        // configuration the source doesn't show (auto-configured beans).
        assertThat(InjectionWiringQuery.unresolvedInjections(Map.of(
                "web/ItemController.java", """
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class ItemController {
                            @Autowired
                            private ObjectMapper mapper;
                        }
                        """))).isEmpty();
    }

    @Test
    void markerFreeProject_getsNoOpinion() {
        // Self-gate: no container markers anywhere → the ecosystem rule does not apply.
        assertThat(InjectionWiringQuery.unresolvedInjections(Map.of(
                "app/Main.java", """
                        public class Main {
                            private Helper helper;
                            public Main(Helper helper) { this.helper = helper; }
                        }
                        """,
                "app/Helper.java", "public class Helper {}"))).isEmpty();
    }

    @Test
    void injectionMarkerInUnmanagedClass_staysSilent() {
        // The container never instantiates a marker-free class, so its @Autowired field is
        // inert — not a startup failure.
        assertThat(InjectionWiringQuery.unresolvedInjections(Map.of(
                "app/PlainHelper.java", """
                        import org.springframework.beans.factory.annotation.Autowired;
                        public class PlainHelper {
                            @Autowired
                            private ItemService itemService;
                        }
                        """,
                "service/ItemService.java", SERVICE_WITHOUT_MARKER,
                "service/OtherService.java", """
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OtherService {}
                        """))).isEmpty();
    }

    @Test
    void setterInjection_isChecked() {
        List<UnresolvedInjection> unresolved = InjectionWiringQuery.unresolvedInjections(Map.of(
                "web/ItemController.java", """
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class ItemController {
                            private ItemService itemService;
                            @Autowired
                            public void setItemService(ItemService itemService) { this.itemService = itemService; }
                        }
                        """,
                "service/ItemService.java", SERVICE_WITHOUT_MARKER));

        assertThat(unresolved).extracting(UnresolvedInjection::injectedType).containsExactly("ItemService");
        assertThat(unresolved.get(0).kind()).isEqualTo("setter");
    }

    @Test
    void fullyQualifiedMarkerAnnotation_resolves() {
        // `@org.springframework.stereotype.Service` parses to the qualified name — found in the
        // field on the run-13 probe copy: the marker must match on its last segment.
        assertThat(InjectionWiringQuery.unresolvedInjections(Map.of(
                "web/ItemController.java", CONTROLLER_INJECTING_SERVICE,
                "service/ItemService.java", """
                        @org.springframework.stereotype.Service
                        public class ItemService {}
                        """))).isEmpty();
    }

    @Test
    void unparseableAndEmptyFiles_areSkippedNotFatal() {
        assertThat(InjectionWiringQuery.unresolvedInjections(Map.of(
                "broken/Broken.java", "class {{{ not java",
                "empty/Empty.java", "",
                "web/ItemController.java", CONTROLLER_INJECTING_SERVICE,
                "service/ItemService.java", SERVICE_WITH_MARKER))).isEmpty();
    }
}
