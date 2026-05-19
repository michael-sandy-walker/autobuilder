package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Safety-net resolver for {@link Kind#PACKAGE_DOES_NOT_EXIST}. Looks the missing package up in a
 * small hardcoded table of well-known Spring Boot starter dependencies and appends the matching
 * {@code <dependency>} block to {@code pom.xml}.
 *
 * <p>Stop-gap by design: the {@link CompileFixResolver} SPI exists so this table can be replaced
 * (or fronted) by a richer resolver — Maven Central search, local {@code ~/.m2} index, etc. —
 * without touching the injector or the probe. The hardcoded entries cover the head of the
 * empirical distribution (JPA / validation / data-repository / web).
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class KnownStarterResolver implements CompileFixResolver {

    private final FileService fileService;

    /** Maps the longest-matching package prefix to the Maven coordinates that provide it. */
    static final Map<String, String[]> KNOWN = buildTable();

    private static Map<String, String[]> buildTable() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("jakarta.persistence",                new String[]{"org.springframework.boot", "spring-boot-starter-data-jpa", null});
        m.put("javax.persistence",                  new String[]{"org.springframework.boot", "spring-boot-starter-data-jpa", null});
        m.put("jakarta.validation",                 new String[]{"org.springframework.boot", "spring-boot-starter-validation", null});
        m.put("javax.validation",                   new String[]{"org.springframework.boot", "spring-boot-starter-validation", null});
        m.put("org.springframework.data.repository",new String[]{"org.springframework.boot", "spring-boot-starter-data-jpa", null});
        m.put("org.springframework.data.jpa",       new String[]{"org.springframework.boot", "spring-boot-starter-data-jpa", null});
        m.put("org.springframework.web",            new String[]{"org.springframework.boot", "spring-boot-starter-web", null});
        return Map.copyOf(m);
    }

    @Override
    public Optional<FixProposal> resolve(Kind kind, String missingToken, CycleContextData context) {
        if (kind != Kind.PACKAGE_DOES_NOT_EXIST) return Optional.empty();
        if (missingToken == null || missingToken.isBlank()) return Optional.empty();
        if (context == null || context.outputPath() == null) return Optional.empty();

        String[] coords = lookup(missingToken);
        if (coords == null) return Optional.empty();

        String pomRel = context.outputPath() + "/pom.xml";
        String pom;
        try {
            if (!fileService.exists(pomRel)) {
                // BootstrapPomResolver should have run first; bail rather than create a half pom here.
                return Optional.empty();
            }
            pom = fileService.readString(pomRel);
        } catch (IOException e) {
            log.warn("KnownStarterResolver: cannot read {}: {}", pomRel, e.getMessage());
            return Optional.empty();
        }

        if (containsDependency(pom, coords[0], coords[1])) {
            // Already present — adding again won't help, and resolver shouldn't no-op a cycle.
            return Optional.empty();
        }

        String patched = appendDependency(pom, coords[0], coords[1], coords[2]);
        if (patched == null) {
            log.warn("KnownStarterResolver: could not splice <dependencies> block in pom.xml");
            return Optional.empty();
        }

        String rule = "known-starter:" + coords[1];
        FileMutation mutation = new FileMutation("pom.xml", patched);
        InjectorFixRecord record = new InjectorFixRecord(
                rule,
                Kind.PACKAGE_DOES_NOT_EXIST,
                missingToken,
                List.of("pom.xml"),
                "Added " + coords[0] + ":" + coords[1] + " to pom.xml (package " + missingToken + ")"
        );
        log.info("CompileFixInjector: {} — added {}:{} for missing package {}",
                rule, coords[0], coords[1], missingToken);
        return Optional.of(new FixProposal(List.of(mutation), record));
    }

    private static String[] lookup(String missingPackage) {
        String pkg = missingPackage.toLowerCase(Locale.ROOT);
        // Longest-prefix match so e.g. "jakarta.persistence.criteria" still finds JPA.
        String best = null;
        for (String key : KNOWN.keySet()) {
            if (pkg.equals(key) || pkg.startsWith(key + ".")) {
                if (best == null || key.length() > best.length()) best = key;
            }
        }
        return best == null ? null : KNOWN.get(best);
    }

    static boolean containsDependency(String pom, String groupId, String artifactId) {
        // Coarse but sufficient: a present-artifact check; we don't promise to detect every
        // edge-case packaging. False negatives just mean a duplicate <dependency> block — Maven
        // tolerates it; false positives would silently skip a needed addition, which is worse.
        String needle = "<artifactId>" + artifactId + "</artifactId>";
        return pom.contains(needle);
    }

    static String appendDependency(String pom, String groupId, String artifactId, String version) {
        int closeIdx = pom.lastIndexOf("</dependencies>");
        if (closeIdx < 0) return null;
        StringBuilder dep = new StringBuilder();
        dep.append("        <dependency>\n");
        dep.append("            <groupId>").append(groupId).append("</groupId>\n");
        dep.append("            <artifactId>").append(artifactId).append("</artifactId>\n");
        if (version != null && !version.isBlank()) {
            dep.append("            <version>").append(version).append("</version>\n");
        }
        dep.append("        </dependency>\n");
        return pom.substring(0, closeIdx) + dep + pom.substring(closeIdx);
    }
}
