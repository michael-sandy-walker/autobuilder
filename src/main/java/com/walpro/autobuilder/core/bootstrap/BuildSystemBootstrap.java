package com.walpro.autobuilder.core.bootstrap;

import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * T3-BT: seeds the build system into output/ at bootstrap time so the first evolution
 * cycle always has a valid build descriptor ({@code pom.xml}, {@code build.gradle}, etc.)
 * and {@code mvn compile} succeeds rather than failing with "no POM in this directory".
 *
 * <p>Two paths:
 * <ol>
 *   <li><b>Explicit skeleton (preferred):</b> if {@code bootstrap/build/} exists under
 *       {@code file.base-path}, copy its contents into {@code output/}. Zero LLM cost.</li>
 *   <li><b>No skeleton:</b> nothing is done; the LLM-detected path requires T4-AN-5 and is
 *       not yet implemented.</li>
 * </ol>
 *
 * <p>Guarded by {@code meta/build-bootstrap.done} — idempotent across restarts.
 * Controlled by {@code autobuilder.bootstrap.build-system} ({@code auto} | {@code none}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildSystemBootstrap {

    private final FileService fileService;

    /** {@code auto} = try explicit skeleton first (bootstrap/build/); {@code none} = skip entirely. */
    @Value("${autobuilder.bootstrap.build-system:auto}")
    private String buildSystemMode;

    public void seedBuildSystemIfNecessary() throws IOException {
        if ("none".equalsIgnoreCase(buildSystemMode)) {
            log.debug("T3-BT: build-system seeding disabled (build-system=none)");
            return;
        }

        Path buildFlag = fileService.resolveSecurePath("meta/build-bootstrap.done");
        if (fileService.existsResolved(buildFlag)) {
            log.debug("T3-BT: build-system bootstrap already completed — skipping");
            return;
        }

        Path buildSeedDir = fileService.resolveSecurePath("bootstrap/build");
        if (!fileService.existsResolved(buildSeedDir) || !Files.isDirectory(buildSeedDir)) {
            log.debug("T3-BT: no explicit build skeleton at bootstrap/build/ — skipping (LLM-detected path requires T4-AN-5)");
            return;
        }

        Path outputDir = fileService.resolveSecurePath("output");
        try (Stream<Path> walker = Files.walk(buildSeedDir)) {
            List<Path> seedFiles = walker.filter(Files::isRegularFile).collect(Collectors.toList());
            for (Path seedFile : seedFiles) {
                Path relative = buildSeedDir.relativize(seedFile);
                Path target = outputDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(seedFile, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("T3-BT: seeded build file → output/{}", relative);
            }
            fileService.writeFileResolved(buildFlag, Instant.now().toString());
            log.info("T3-BT: build-system seeding complete ({} file(s) copied to output/)", seedFiles.size());
        } catch (IOException e) {
            log.error("T3-BT: build-system seeding failed — first cycle may fail with missing build descriptor", e);
        }
    }
}
