package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Resolves {@link Kind#MISSING_POM} by writing a minimal Spring Boot 3.x starter
 * {@code pom.xml} at the project root. Triggered when the LLM has produced Java sources but the
 * target project has no Maven descriptor yet — a chicken-and-egg bootstrap step the LLM rarely
 * gets right in one shot.
 *
 * <p>Defaults to {@code com.example:demo:0.0.1-SNAPSHOT}, Java 21, parent
 * {@code spring-boot-starter-parent:3.3.0}. Deps included by default cover the common starters
 * needed by any web/JPA/validation Spring Boot project so the first compile probe has the best
 * chance of clearing without a second injector pass.
 */
@Slf4j
@Component
@Order(10)
public class BootstrapPomResolver implements CompileFixResolver {

    static final String POM_PATH = "pom.xml";

    static final String DEFAULT_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>demo</artifactId>
                <version>0.0.1-SNAPSHOT</version>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.0</version>
                    <relativePath/>
                </parent>
                <properties>
                    <java.version>21</java.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-validation</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <optional>true</optional>
                    </dependency>
                </dependencies>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    @Override
    public Optional<FixProposal> resolve(Kind kind, String missingToken, CycleContextData context) {
        if (kind != Kind.MISSING_POM) return Optional.empty();
        FileMutation mutation = new FileMutation(POM_PATH, DEFAULT_POM);
        InjectorFixRecord record = new InjectorFixRecord(
                "bootstrap-pom",
                Kind.MISSING_POM,
                "",
                List.of(POM_PATH),
                "Bootstrapped pom.xml (Spring Boot 3.3.0 parent, Java 21, starter-web/-validation/lombok)"
        );
        log.info("CompileFixInjector: bootstrap-pom — writing default Spring Boot pom.xml");
        return Optional.of(new FixProposal(List.of(mutation), record));
    }
}
