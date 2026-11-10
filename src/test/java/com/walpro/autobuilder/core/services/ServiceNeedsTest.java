package com.walpro.autobuilder.core.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceNeedsTest {

    @TempDir
    Path tree;

    /** The shape of the e-commerce project that hit the wall: its own key names, commented alternatives, a README. */
    private void ecommerce() throws Exception {
        Files.createDirectories(tree.resolve("src/main/resources"));
        Files.writeString(tree.resolve("src/main/resources/application.properties"), """
                server.port=8080
                #database.url=jdbc:mysql://localhost:3307/old
                hibernate.dialect= org.hibernate.dialect.MySQL5Dialect
                db.driver= com.mysql.cj.jdbc.Driver
                db.url= jdbc:mysql://localhost:3306/ecommjava?createDatabaseIfNotExist=true
                db.username= root
                db.password=
                """);
        Files.writeString(tree.resolve("README.md"), """
                # Shop
                <img src="https://img.shields.io/badge/MySQL-8.x" alt="MySQL"/>
                - MySQL 8 connector
                Run `basedata.sql` against your database.
                """);
        Files.writeString(tree.resolve("basedata.sql"), "CREATE DATABASE IF NOT EXISTS ecommjava;");
        Files.createDirectories(tree.resolve("src/test/resources"));
        Files.writeString(tree.resolve("src/test/resources/fixture.sql"), "-- a test's own");
    }

    @Test
    void readsTheAddressesOfConfigurationLinesAndSkipsComments() throws Exception {
        ecommerce();
        List<ServiceNeeds.Address> found = ServiceNeeds.addresses(tree);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).port()).isEqualTo(3306);
        assertThat(found.get(0).scheme()).isEqualTo("mysql");
        assertThat(found.get(0).file()).isEqualTo("src/main/resources/application.properties");
        assertThat(found.get(0).line()).isEqualTo(5);
    }

    @Test
    void anAddressThatAnswersIsNotANeed() throws Exception {
        ecommerce();
        assertThat(new ServiceNeeds(p -> null, port -> true).unanswered(tree)).isEmpty();
        assertThat(new ServiceNeeds(p -> null, port -> false).unanswered(tree)).hasSize(1);
    }

    @Test
    void theEvidenceIsTheKeysBesideTheAddressAndTheReadmeLinesNamingItsScheme() throws Exception {
        ecommerce();
        ServiceNeeds.Address a = ServiceNeeds.addresses(tree).get(0);
        List<String> evidence = ServiceNeeds.evidence(tree, a);
        assertThat(evidence).anyMatch(l -> l.contains("db.url= jdbc:mysql://localhost:3306"))
                .anyMatch(l -> l.contains("db.username= root"))
                .anyMatch(l -> l.endsWith("db.password="))
                .anyMatch(l -> l.equals("README.md:3: - MySQL 8 connector"))
                .noneMatch(l -> l.contains("<img"))
                .noneMatch(l -> l.contains("server.port"));
        assertThat(ServiceNeeds.scripts(tree)).containsExactly("basedata.sql");
    }

    @Test
    void theModelWritesTheProposalFromTheQuotedLinesAndThePortStaysTheConfiguredOne() throws Exception {
        ecommerce();
        List<String> prompts = new ArrayList<>();
        ServiceNeeds needs = new ServiceNeeds(p -> {
            prompts.add(p);
            return """
                    IMAGE: mysql:8.0
                    CONTAINER_PORT: 3306
                    ENV: MYSQL_ALLOW_EMPTY_PASSWORD=yes
                    ENV: MYSQL_DATABASE=ecommjava
                    INIT_DIR: /docker-entrypoint-initdb.d
                    """;
        }, port -> false);
        List<ServiceProposal> p = needs.propose(tree, needs.unanswered(tree), "CJCommunicationsException: Communications link failure");
        assertThat(p).hasSize(1);
        ServiceProposal s = p.get(0);
        assertThat(s.name()).isEqualTo("mysql");
        assertThat(s.image()).isEqualTo("mysql:8.0");
        assertThat(s.hostPort()).isEqualTo(3306);
        assertThat(s.env()).containsEntry("MYSQL_ALLOW_EMPTY_PASSWORD", "yes").containsEntry("MYSQL_DATABASE", "ecommjava");
        assertThat(s.initScripts()).containsExactly("basedata.sql");
        assertThat(s.initScript()).as("decided: offered, unticked").isNull();
        assertThat(s.source()).isEqualTo("model");
        assertThat(prompts.get(0)).contains("db.url= jdbc:mysql://localhost:3306").contains("Communications link failure");
    }

    @Test
    void aProposalThatDidNotComeUpIsReadAgainWithWhatItsContainerSaid() {
        List<String> prompts = new ArrayList<>();
        ServiceNeeds needs = new ServiceNeeds(p -> {
            prompts.add(p);
            return "IMAGE: mysql:8\nCONTAINER_PORT: 3306\nENV: MYSQL_ALLOW_EMPTY_PASSWORD=yes\nINIT_DIR: /docker-entrypoint-initdb.d";
        }, port -> false);
        ServiceProposal first = new ServiceProposal("mysql", "mysql:8", 3306, 3306, java.util.Map.of("MYSQL_ROOT_PASSWORD", ""),
                "/docker-entrypoint-initdb.d", List.of("basedata.sql"), null, "model", List.of("a:1: db.password="), "link failure");
        ServiceProposal again = needs.revise(first, "You need to specify one of ... MYSQL_ALLOW_EMPTY_PASSWORD", "mysql stopped");
        assertThat(prompts.get(0)).contains("ENV: MYSQL_ROOT_PASSWORD=").contains("You need to specify one of");
        assertThat(again.env()).containsEntry("MYSQL_ALLOW_EMPTY_PASSWORD", "yes").doesNotContainKey("MYSQL_ROOT_PASSWORD");
        assertThat(again.failure()).isEqualTo("mysql stopped");
        assertThat(again.initScripts()).containsExactly("basedata.sql");
        // The project's own compose file is its answer: shown again with the failure, never rewritten.
        ServiceProposal composed = new ServiceProposal("db", "mariadb:10", 3306, 3306, java.util.Map.of(), null, List.of(), null,
                "compose", List.of(), "x");
        assertThat(needs.revise(composed, "said", "why").image()).isEqualTo("mariadb:10");
        assertThat(prompts).hasSize(1);
    }

    @Test
    void anAnswerWithoutTheFormIsNoProposal() throws Exception {
        ecommerce();
        ServiceNeeds needs = new ServiceNeeds(p -> "You should install MySQL.", port -> false);
        assertThat(needs.propose(tree, needs.unanswered(tree), "x")).isEmpty();
    }

    @Test
    void theProjectsOwnComposeFileIsTakenAsWrittenNotTheModelsReading() throws Exception {
        ecommerce();
        Files.writeString(tree.resolve("docker-compose.yml"), """
                services:
                  app:
                    build: .
                    ports: ["8080:8080"]
                  db:
                    image: mariadb:10.11
                    ports:
                      - "3306:3306"
                    environment:
                      MARIADB_ALLOW_EMPTY_ROOT_PASSWORD: "yes"
                      MARIADB_DATABASE: ecommjava
                    volumes:
                      - ./basedata.sql:/docker-entrypoint-initdb.d/basedata.sql
                """);
        List<String> asked = new ArrayList<>();
        ServiceNeeds needs = new ServiceNeeds(p -> { asked.add(p); return "IMAGE: mysql:8.0\nCONTAINER_PORT: 3306"; }, port -> false);
        List<ServiceProposal> p = needs.propose(tree, needs.unanswered(tree), "link failure");
        assertThat(asked).isEmpty();
        assertThat(p).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("db");
            assertThat(s.image()).isEqualTo("mariadb:10.11");
            assertThat(s.source()).isEqualTo("compose");
            assertThat(s.env()).containsEntry("MARIADB_DATABASE", "ecommjava");
            assertThat(s.initDir()).isEqualTo("/docker-entrypoint-initdb.d");
            assertThat(s.initScript()).isEqualTo("basedata.sql");
            assertThat(s.invalid()).isNull();
        });
    }

    @Test
    void composePortForms() {
        assertThat(ServiceNeeds.ports(List.of("127.0.0.1:5433:5432"))).containsExactly(5433, 5432);
        assertThat(ServiceNeeds.ports(List.of("6379"))).containsExactly(6379, 6379);
        assertThat(ServiceNeeds.ports(List.of(java.util.Map.of("target", 5672, "published", 5673)))).containsExactly(5673, 5672);
        assertThat(ServiceNeeds.ports(null)).isNull();
    }
}
