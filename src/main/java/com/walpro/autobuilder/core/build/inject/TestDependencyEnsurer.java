package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * T4-BN-3 (deterministic slice, 2026-06-23): ensures the project's test framework dependency is
 * present in {@code pom.xml} when test sources fail to compile on missing test-framework symbols
 * (the dominant real test-compile error in the 06-17→06-23 runs: {@code cannot find symbol: Test /
 * Mock / ExtendWith / BeforeEach}, i.e. the test deps are simply not declared).
 *
 * <p>Discovery, not a frozen table ([[feedback-no-hardcoded-ecosystem-data]] + the user's
 * 2026-06-23 javax→jakarta guidance: discover the coordinate from the project's own declarations;
 * a fixed coordinate is a last-resort fallback that must file a discovery follow-up). The only
 * ecosystem fact read here comes from the project's OWN pom: if it declares a Spring Boot ecosystem
 * (a {@code spring-boot-starter-parent} or any {@code spring-boot-starter-*} dependency), then the
 * matching test dependency is that ecosystem's umbrella test starter, at the version the project's
 * parent already manages (none pinned here). Non-Spring-Boot poms are left untouched — resolving an
 * arbitrary test import to a Maven coordinate is the filed discovery follow-up (Maven Central
 * {@code fc:} class search), not this slice.
 *
 * <p>Idempotent and conservative: no-op when the pom already declares the test starter, when no
 * Spring Boot signal is present, or when there is no {@code <dependencies>} block to extend.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestDependencyEnsurer {

    /** The project's own declaration that it is a Spring Boot project (parent or any starter). */
    private static final Pattern SPRING_BOOT_SIGNAL = Pattern.compile("spring-boot-starter", Pattern.CASE_INSENSITIVE);
    /** Already-present test starter — idempotency guard. */
    private static final Pattern TEST_STARTER_PRESENT = Pattern.compile("spring-boot-starter-test", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSE_DEPENDENCIES = Pattern.compile("</dependencies>");

    private final FileService fileService;

    private static final String POM_PATH = "output/pom.xml";

    /**
     * Reads {@code output/pom.xml}, injects the test starter if needed, and writes it back.
     * Returns true when the pom was modified. Best-effort: any IO/parse problem is swallowed
     * (this is a steering aid, never allowed to wedge the loop).
     */
    public boolean ensure() {
        if (false) return false;
        try {
            if (!fileService.exists(POM_PATH)) return false;
            String pom = fileService.readFile(POM_PATH);
            Optional<String> updated = injectTestStarterIfNeeded(pom);
            if (updated.isEmpty()) return false;
            fileService.writeFile(POM_PATH, updated.get());
            log.info("T4-BN-3: added the Spring Boot test starter to pom.xml (test sources need it)");
            return true;
        } catch (Exception e) {
            log.debug("T4-BN-3: test-dep ensure skipped ({})", e.getMessage());
            return false;
        }
    }

    /**
     * Pure core: returns the pom with the test starter added before {@code </dependencies>}, or
     * empty when no change is warranted (not Spring Boot / already present / no dependencies block).
     */
    public static Optional<String> injectTestStarterIfNeeded(String pomXml) {
        if (pomXml == null || pomXml.isBlank()) return Optional.empty();
        if (!SPRING_BOOT_SIGNAL.matcher(pomXml).find()) return Optional.empty();      // not our ecosystem
        if (TEST_STARTER_PRESENT.matcher(pomXml).find()) return Optional.empty();     // already declared
        var closer = CLOSE_DEPENDENCIES.matcher(pomXml);
        if (!closer.find()) return Optional.empty();                                  // nothing to extend

        // Indent to match the closing tag's column so the edit reads naturally.
        int tagStart = closer.start();
        int lineStart = pomXml.lastIndexOf('\n', tagStart) + 1;
        String indent = pomXml.substring(lineStart, tagStart);
        String dep = indent + "    <dependency>\n"
                + indent + "        <groupId>org.springframework.boot</groupId>\n"
                + indent + "        <artifactId>spring-boot-starter-test</artifactId>\n"
                + indent + "        <scope>test</scope>\n"
                + indent + "    </dependency>\n";
        String updated = pomXml.substring(0, lineStart) + dep + pomXml.substring(lineStart);
        return Optional.of(updated);
    }
}
