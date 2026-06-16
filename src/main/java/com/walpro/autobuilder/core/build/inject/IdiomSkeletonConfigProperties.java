package com.walpro.autobuilder.core.build.inject;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * T4-AY refactor (2026-05-21): config-driven skeleton idiom registry. Each entry teaches the
 * deterministic resolver how to materialise a missing-class file when the compile-probe stderr
 * names a symbol whose name matches {@link Idiom#getPattern() pattern}.
 *
 * <p>The four bundled defaults (Repository / Exception / Service / Controller) ship as Java
 * literals so the autobuilder runs out of the box for Spring-Boot/Java targets. Adding a new
 * idiom — {@code *Mapper}, {@code *Validator}, {@code *Configuration}, project-specific
 * conventions — is a YAML edit: append an entry under {@code autobuilder.build.skeletons.idioms}.
 * Providing the {@code idioms:} key in YAML REPLACES the defaults (Spring binding semantics);
 * preserve the bundled four explicitly when extending.
 *
 * <p>Templates support two substitution tokens: {@code {className}} (the missing symbol) and
 * {@code {packageDecl}} (the fully-qualified target package, computed as
 * {@code <establishedBasePackage>.<subPackage>}). Substitution is plain string replacement —
 * no Spring property interpolation runs at render time, so {@code {x}} is safe.
 */
@Configuration
@ConfigurationProperties(prefix = "autobuilder.build.skeletons")
@Getter
@Setter
public class IdiomSkeletonConfigProperties {

    /**
     * Resolver registration order. Lower wins ties in the {@link CompileFixResolver} chain — the
     * existing {@link BootstrapPomResolver} is 10, {@link SymbolImportResolver} is 30 (also
     * handles PACKAGE_DOES_NOT_EXIST via T4-BG's registry), so 50 leaves room for future
     * deterministic resolvers in between.
     */
    private int resolverOrder = 50;

    /** Levenshtein cutoff for the similarity-abort guard. */
    private int similarityAbortThreshold = 2;

    /**
     * T4-BQ: when {@code true}, a freshly-created skeleton is populated with the member signatures
     * its call sites prove must exist (usage-derived synthesis), so the whole missing-type closure
     * lands in one pass instead of one missing-<i>member</i> probe failure at a time. Code default
     * {@code false}; {@code application.yml} enables it (validate-in-run pattern).
     */
    private boolean synthesizeMembers = false;

    /** Fallback base package when {@code establishedBasePackage} is not known yet. */
    private String defaultBasePackage = "com.example.demo";

    /**
     * Ordered list of idiom registrations. First matching pattern wins, so order ambiguous
     * suffixes carefully — {@code *Exception} should precede {@code *Service} in case a
     * project ever spawns a {@code WhateverServiceException} type (though current entries are
     * mutually exclusive by suffix).
     */
    private List<Idiom> idioms = defaultIdioms();

    private static List<Idiom> defaultIdioms() {
        List<Idiom> out = new ArrayList<>();
        out.add(new Idiom(
                "skeleton-repository",
                "\\w+Repository",
                "repository",
                "Created {className} skeleton (JpaRepository<Object, Long>)",
                """
                package {packageDecl};

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface {className} extends JpaRepository<Object, Long> {
                }
                """));
        out.add(new Idiom(
                "skeleton-exception",
                "\\w+Exception",
                "exception",
                "Created {className} skeleton (extends RuntimeException)",
                """
                package {packageDecl};

                public class {className} extends RuntimeException {
                    public {className}(String message) {
                        super(message);
                    }
                }
                """));
        out.add(new Idiom(
                "skeleton-service",
                "\\w+Service",
                "service",
                "Created {className} skeleton (@Service)",
                """
                package {packageDecl};

                import org.springframework.stereotype.Service;

                @Service
                public class {className} {
                }
                """));
        out.add(new Idiom(
                "skeleton-controller",
                "\\w+Controller",
                "controller",
                "Created {className} skeleton (@RestController)",
                """
                package {packageDecl};

                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class {className} {
                }
                """));
        return out;
    }

    @Getter
    @Setter
    public static class Idiom {

        /** Stable resolver-rule name surfaced into logs and provenance records. */
        private String rule;

        /** Java regex matched against the missing symbol name in full (anchored). */
        private String pattern;

        /** Sub-package suffix appended to the base package (e.g. {@code repository}). */
        private String subPackage;

        /** One-line description used in {@link InjectorFixRecord#description()}. */
        private String description;

        /** Skeleton body template; substitutes {@code {className}} and {@code {packageDecl}}. */
        private String template;

        public Idiom() {}

        public Idiom(String rule, String pattern, String subPackage, String description, String template) {
            this.rule = rule;
            this.pattern = pattern;
            this.subPackage = subPackage;
            this.description = description;
            this.template = template;
        }
    }
}
