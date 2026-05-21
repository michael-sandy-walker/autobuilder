package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnownStarterResolverTest {

    private FileService fileService;
    private KnownStarterResolver resolver;
    private CycleContextData ctx;

    private static final String BASE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        resolver = new KnownStarterResolver(fileService);
        ctx = CycleContextData.builder().cycleName("c-1").outputPath("output").build();
    }

    @Test
    void resolve_jakartaPersistence_addsJpaStarter() throws IOException {
        when(fileService.exists(eq("output/pom.xml"))).thenReturn(true);
        when(fileService.readString(eq("output/pom.xml"))).thenReturn(BASE_POM);

        Optional<FixProposal> out = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "jakarta.persistence", ctx);

        assertThat(out).isPresent();
        FixProposal proposal = out.get();
        assertThat(proposal.mutations().get(0).path()).isEqualTo("pom.xml");
        assertThat(proposal.mutations().get(0).content())
                .contains("<artifactId>spring-boot-starter-data-jpa</artifactId>")
                .contains("<artifactId>spring-boot-starter-web</artifactId>"); // existing preserved
        assertThat(proposal.record().rule()).isEqualTo("known-starter:spring-boot-starter-data-jpa");
        assertThat(proposal.record().missingToken()).isEqualTo("jakarta.persistence");
    }

    @Test
    void resolve_subpackageOfKnown_stillResolves() throws IOException {
        when(fileService.exists(eq("output/pom.xml"))).thenReturn(true);
        when(fileService.readString(eq("output/pom.xml"))).thenReturn(BASE_POM);

        Optional<FixProposal> out = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST,
                "jakarta.persistence.criteria", ctx);

        assertThat(out).isPresent();
        assertThat(out.get().record().rule()).isEqualTo("known-starter:spring-boot-starter-data-jpa");
    }

    @Test
    void resolve_unknownPackage_returnsEmpty() throws IOException {
        when(fileService.exists(eq("output/pom.xml"))).thenReturn(true);
        when(fileService.readString(eq("output/pom.xml"))).thenReturn(BASE_POM);

        Optional<FixProposal> out = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "com.acme.notarealthing", ctx);
        assertThat(out).isEmpty();
    }

    @Test
    void resolve_depAlreadyPresent_returnsEmpty() throws IOException {
        String pomWithJpa = BASE_POM.replace("</dependencies>", """
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-data-jpa</artifactId>
                    </dependency>
                </dependencies>""");
        when(fileService.exists(eq("output/pom.xml"))).thenReturn(true);
        when(fileService.readString(eq("output/pom.xml"))).thenReturn(pomWithJpa);

        Optional<FixProposal> out = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "jakarta.persistence", ctx);
        assertThat(out).isEmpty();
    }

    @Test
    void resolve_pomMissing_returnsEmpty() throws IOException {
        when(fileService.exists(eq("output/pom.xml"))).thenReturn(false);

        Optional<FixProposal> out = resolver.resolve(Kind.PACKAGE_DOES_NOT_EXIST, "jakarta.persistence", ctx);
        // BootstrapPomResolver handles this case; KnownStarterResolver defers.
        assertThat(out).isEmpty();
    }

    @Test
    void resolve_wrongKind_returnsEmpty() {
        assertThat(resolver.resolve(Kind.MISSING_POM, null, ctx)).isEmpty();
        assertThat(resolver.resolve(Kind.CANNOT_FIND_SYMBOL, "Foo", ctx)).isEmpty();
        assertThat(resolver.resolve(Kind.UNKNOWN, null, ctx)).isEmpty();
    }

}
