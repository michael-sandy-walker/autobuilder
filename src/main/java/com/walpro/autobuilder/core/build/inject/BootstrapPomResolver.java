package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@link Kind#MISSING_POM} by writing a MINIMAL {@code pom.xml} derived from the
 * project's own sources. Triggered when sources exist but the target has no Maven descriptor yet —
 * a chicken-and-egg bootstrap the model rarely gets right in one shot.
 *
 * <p><b>T4-DI: it used to write one ecosystem's project into every target.</b> The template was a
 * {@code spring-boot-starter-parent} pom with {@code starter-web}, {@code starter-validation},
 * lombok and the Spring Boot build plugin, under {@code com.example:demo}. On run
 * 2026-08-28_05-27-49 that fired ONCE against a 3D/OpenGL target and set the rest of the run's
 * agenda: packaging then failed with {@code spring-boot-maven-plugin:repackage … Unable to find
 * main class}, the loop injected a work item to satisfy Spring Boot packaging, the analysis agent
 * authored the problem "the project is configured as a Spring Boot application but no
 * {@code @SpringBootApplication} main class", and the model wrote a {@code FlightSimApplication}.
 * The target's specification never mentioned a web framework
 * ([[feedback-no-target-domain-in-framework-strings]], [[feedback-no-hardcoded-ecosystem-data]]).
 *
 * <p><b>What it derives, all from the sources in front of it and none of it a guess:</b>
 * coordinates from the base package the sources already declare; the compiler release from the
 * running JVM (a measurable environmental fact, not an ecosystem opinion); and a jar manifest
 * entry only when exactly one class declares {@code public static void main} — which is what
 * makes the artifact runnable for a CLI, a desktop app or a service alike.
 *
 * <p><b>What it deliberately does NOT do:</b> add dependencies. It cannot know which artifacts the
 * imports need, and it does not have to — a missing package raises
 * {@link Kind#PACKAGE_DOES_NOT_EXIST}, which the discovery chain and {@code T4-DH}'s repository
 * interface already own. Bootstrapping creates a VALID build file; deciding what belongs in it is
 * a different question with a different authority.
 */
@Slf4j
@Component
@Order(10)
public class BootstrapPomResolver implements CompileFixResolver {

    static final String POM_PATH = "pom.xml";

    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern MAIN_METHOD = Pattern.compile(
            "public\\s+static\\s+void\\s+main\\s*\\(\\s*(?:final\\s+)?String\\s*(?:\\[\\s*\\]\\s*\\w+|\\w+\\s*\\[\\s*\\]|\\.\\.\\.\\s*\\w+)\\s*\\)");
    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");

    /** A pom that names no framework, and no dependency it cannot justify from the sources. */
    static String minimalPom(String groupId, String artifactId, String mainClass, int release) {
        StringBuilder pom = new StringBuilder();
        pom.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                """);
        pom.append("    <groupId>").append(groupId).append("</groupId>\n");
        pom.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
        pom.append("    <version>0.0.1-SNAPSHOT</version>\n");
        pom.append("    <packaging>jar</packaging>\n\n");
        pom.append("    <properties>\n");
        pom.append("        <maven.compiler.release>").append(release).append("</maven.compiler.release>\n");
        pom.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        pom.append("    </properties>\n\n");
        pom.append("    <dependencies>\n");
        pom.append("        <!-- Declare exactly the artifacts this project's own code imports. -->\n");
        pom.append("    </dependencies>\n");
        if (mainClass != null) {
            pom.append("\n    <build>\n        <plugins>\n            <plugin>\n");
            pom.append("                <groupId>org.apache.maven.plugins</groupId>\n");
            pom.append("                <artifactId>maven-jar-plugin</artifactId>\n");
            pom.append("                <configuration>\n                    <archive>\n");
            pom.append("                        <manifest>\n                            <mainClass>")
               .append(mainClass).append("</mainClass>\n                        </manifest>\n");
            pom.append("                    </archive>\n                </configuration>\n");
            pom.append("            </plugin>\n        </plugins>\n    </build>\n");
        }
        pom.append("</project>\n");
        return pom.toString();
    }

    /** The package prefix the project's own sources agree on, or null when they do not. */
    static String basePackageOf(Map<String, String> sources) {
        if (sources == null || sources.isEmpty()) return null;
        Set<String> packages = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (e.getKey() == null || !e.getKey().endsWith(".java") || e.getValue() == null) continue;
            Matcher m = PACKAGE_DECL.matcher(e.getValue());
            if (m.find()) packages.add(m.group(1));
        }
        if (packages.isEmpty()) return null;
        List<String> common = null;
        for (String pkg : packages) {
            String[] parts = pkg.split("\\.");
            if (common == null) {
                common = new ArrayList<>(List.of(parts));
                continue;
            }
            int keep = 0;
            while (keep < common.size() && keep < parts.length && common.get(keep).equals(parts[keep])) keep++;
            common = new ArrayList<>(common.subList(0, keep));
        }
        return (common == null || common.isEmpty()) ? null : String.join(".", common);
    }

    /** The single class declaring an entry point, or null when there is not exactly one. */
    static String soleMainClassOf(Map<String, String> sources) {
        if (sources == null) return null;
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String path = e.getKey(), body = e.getValue();
            if (path == null || !path.endsWith(".java") || body == null) continue;
            if (!MAIN_METHOD.matcher(body).find()) continue;
            Matcher pm = PACKAGE_DECL.matcher(body);
            String pkg = pm.find() ? pm.group(1) : null;
            String simple = path.substring(path.lastIndexOf('/') + 1, path.length() - ".java".length());
            Matcher tm = TYPE_DECL.matcher(body);
            if (tm.find()) simple = tm.group(1);
            found.add(pkg == null || pkg.isBlank() ? simple : pkg + "." + simple);
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    @Override
    public Optional<FixProposal> resolve(Classification classification, String missingToken, String stderr,
                                         CycleContextData context) {
        // T4-AN-7: MISSING_POM and PACKAGE_DOES_NOT_EXIST both map to CONFIG_FIXABLE, so the
        // universal Category field can't distinguish "no pom" from "missing dependency" — this
        // within-CONFIG split is the one place a deterministic resolver still reads kind().
        if (classification == null || classification.kind() != Kind.MISSING_POM) return Optional.empty();

        Map<String, String> sources = context != null ? context.previousOutput() : null;
        String basePackage = basePackageOf(sources);
        String groupId = basePackage != null ? basePackage : "com.example";
        String artifactId = basePackage != null && basePackage.contains(".")
                ? basePackage.substring(basePackage.lastIndexOf('.') + 1)
                : (basePackage != null ? basePackage : "app");
        String mainClass = soleMainClassOf(sources);
        int release = Runtime.version().feature();

        String pom = minimalPom(groupId, artifactId, mainClass, release);
        String description = "Bootstrapped a minimal pom.xml for " + groupId + ":" + artifactId
                + " (Java " + release + (mainClass != null ? ", entry point " + mainClass : ", no single entry point")
                + ", no dependencies — the sources decide those)";
        FileMutation mutation = new FileMutation(POM_PATH, pom);
        InjectorFixRecord record = new InjectorFixRecord(
                "bootstrap-pom", Kind.MISSING_POM, "", List.of(POM_PATH), description);
        log.info("CompileFixInjector: bootstrap-pom — {}", description);
        return Optional.of(new FixProposal(List.of(mutation), record));
    }
}
