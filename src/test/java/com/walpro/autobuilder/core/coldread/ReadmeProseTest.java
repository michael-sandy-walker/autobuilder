package com.walpro.autobuilder.core.coldread;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T1-GK: a README's words, not its markup. */
class ReadmeProseTest {

    /** The opening of the e-commerce project's README as found (2026-11-10), its badge row shortened to two badges. */
    private static final String ECOMMERCE = """
            <a href="https://trendshift.io/repositories/151" target="_blank"><img src="https://trendshift.io/api/badge/repositories/151" alt="x | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

            # E-commerce Spring Boot (JSP + Hibernate)

            Production-oriented Java e-commerce web application built with Spring Boot, JSP, Spring Security, and Hibernate SessionFactory.

            This project follows a layered MVC architecture and supports role-based access for admin and customer workflows.

            <br/><br/>

            <h1>🛒 E-Commerce Spring Boot</h1>
            \s
            <p>A production-oriented Java e-commerce web application built with Spring Boot, JSP, Spring Security, and Hibernate — featuring role-based access for admin and customer workflows.</p>
            <br/>
            <!-- Badges Row 1: Community -->
            <p>
              <a href="https://github.com/x/y/stargazers">
                <img src="https://img.shields.io/github/stars/x/y?style=flat-square&logo=github&label=Stars" alt="Stars"/>
              </a>
              <a href="https://github.com/x/y/network/members">
                <img src="https://img.shields.io/github/forks/x/y?style=flat-square&label=Forks" alt="Forks"/>
              </a>
            </p>

            <br/>

            ## Highlights

            - Server-rendered e-commerce app (JSP views)
            - Spring Security authentication and role-based authorization
            """;

    @Test
    void theEcommerceReadmeIsQuotedByItsWordsNotItsBadges() {
        List<String> prose = ReadmeProse.of(ECOMMERCE, 40);
        assertThat(prose).containsExactly(
                "# E-commerce Spring Boot (JSP + Hibernate)",
                "Production-oriented Java e-commerce web application built with Spring Boot, JSP, Spring Security, and Hibernate SessionFactory.",
                "This project follows a layered MVC architecture and supports role-based access for admin and customer workflows.",
                "🛒 E-Commerce Spring Boot",
                "A production-oriented Java e-commerce web application built with Spring Boot, JSP, Spring Security, and Hibernate — featuring role-based access for admin and customer workflows.",
                "## Highlights",
                "- Server-rendered e-commerce app (JSP views)",
                "- Spring Security authentication and role-based authorization");
    }

    @Test
    void markdownBadgesAndLinksAreReducedToTheirWords() {
        String readme = """
                [![Build](https://ci.example/badge.svg)](https://ci.example) [![License: MIT](https://img.shields.io/badge/License-MIT.svg)](LICENSE)
                # tool
                A tool that reads [CSV files](https://en.wikipedia.org/wiki/CSV) and prints totals.
                ![screenshot](docs/shot.png)
                [docs]: https://example.org/docs
                Use it with care &amp; read the docs.
                """;
        assertThat(ReadmeProse.of(readme, 40)).containsExactly(
                "# tool",
                "A tool that reads CSV files and prints totals.",
                "Use it with care & read the docs.");
    }

    @Test
    void codeIsKeptAsItIsAndProseIsNeverReworded() {
        String readme = """
                Add the dependency:
                ```xml
                <dependency><artifactId>lib</artifactId></dependency>
                ```
                Values a < b are compared, and x > y too.
                """;
        assertThat(ReadmeProse.of(readme, 40)).containsExactly(
                "Add the dependency:",
                "```xml",
                "<dependency><artifactId>lib</artifactId></dependency>",
                "```",
                "Values a < b are compared, and x > y too.");
    }

    @Test
    void aReadmeOfOnlyMarkupSaysNothingInWords() {
        assertThat(ReadmeProse.of("<p align=\"center\">\n  <img src=\"logo.png\"/>\n</p>\n<!-- todo -->\n[![CI](b.svg)](ci)\n", 40)).isEmpty();
    }

    @Test
    void atMostTheGivenNumberOfLines() {
        assertThat(ReadmeProse.of("one\ntwo\nthree\n", 2)).containsExactly("one", "two");
    }
}
