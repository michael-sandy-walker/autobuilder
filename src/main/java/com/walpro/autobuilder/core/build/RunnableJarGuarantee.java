package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.inject.BootstrapPomResolver;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-DO: guarantees that a packaged artifact can actually RUN.
 *
 * <p>A build that compiles is not a build that runs, and nothing owned that difference. It was an
 * accident of the bootstrap template: while the injector always wrote one ecosystem's build plugin,
 * every artifact came out runnable whether or not the loop understood packaging — {@code
 * boot-failed} was 2 of 26 archived runs. Removing the template (T4-DI, correctly — it named a
 * framework in framework code) did not break packaging, it made it a COIN-FLIP: across three runs
 * on one seed the packaging plugin appeared 0 / 1 / 1 times, and {@code boot-failed} occurred in
 * exactly the run that lacked it.
 *
 * <p><b>The jar is the authority, not the pom.</b> Asking "does this pom configure packaging?"
 * needs a list of plugin names that produce executable artifacts — an ecosystem table, the very
 * debt T4-DI removed, and wrong anyway (a framework plugin may auto-detect an entry point and
 * declare no {@code mainClass} at all). Asking the BUILT JAR whether it carries a {@code Main-Class}
 * manifest entry needs no list and cannot be fooled: it is the same question {@code java -jar} asks.
 *
 * <p><b>It abstains in every case where the project has already answered</b> — a jar that declares
 * an entry point, sources that declare no entry point (a library owes none), or no jar to inspect.
 * The repair is emitted only when the artifact demonstrably cannot run and the sources say what it
 * should run.
 *
 * <p>What it writes names no framework: {@code maven-jar-plugin} for the manifest entry and, when
 * the project declares dependencies, {@code maven-dependency-plugin} to place them beside the jar
 * with a {@code Class-Path} entry pointing at them. Both are build-tool mechanics — this project
 * already assumes Maven everywhere — rather than any target's ecosystem.
 */
@Slf4j
public final class RunnableJarGuarantee {

    private static final Pattern HAS_DEPENDENCY = Pattern.compile("<dependency>\\s*<groupId>", Pattern.DOTALL);
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern BUILD_OPEN = Pattern.compile("<build>\\s*<plugins>", Pattern.DOTALL);
    private static final Pattern MAIN_CLASS_ELEMENT = Pattern.compile("<mainClass>(.*?)</mainClass>", Pattern.DOTALL);

    private RunnableJarGuarantee() {}

    /** The pom this project needs in order to produce a runnable jar, or empty when it already does. */
    public static Optional<String> repairedPom(Path artifactRoot, String pom, Map<String, String> sources) {
        if (artifactRoot == null || pom == null || pom.isBlank()) return Optional.empty();

        Optional<Path> jar = archiveNeedingEntryPoint(artifactRoot);
        if (jar.isEmpty()) return Optional.empty();

        String mainClass = BootstrapPomResolver.soleMainClassOf(sources);
        if (mainClass == null) {
            // A library, or several candidates. Neither is this check's business: it can say the
            // jar will not run, and cannot say what it should have run.
            log.info("T4-DO: {} declares no Main-Class and the sources name no single entry point — "
                    + "abstaining rather than choosing one", jar.get().getFileName());
            return Optional.empty();
        }

        // T4-DO (routing, 2026-09-21): the jar stays the authority for "is packaging configured"
        // — that is the question needing a plugin-name table, and it is still not asked here. This
        // asks a different and much narrower one: is THIS repair already in the pom, waiting for a
        // rebuild? Between writing the pom and the next package the jar still carries no
        // Main-Class, so every caller in that window gets the same non-empty answer. At the
        // closure edge that never showed, because the one call site returned immediately. On the
        // per-cycle path it does: the T1-GD guard fired five times in run 2026-09-21_00-32-59, and
        // freshness does not stop it — artifactOlderThanSources walks src/ and never reads
        // pom.xml, so a written pom leaves the gate believing the artifact is current.
        if (declaresEntryPointInPom(pom, mainClass)) {
            log.debug("T4-DO: the pom already declares entry point {} — the repair is written and "
                    + "waiting for a rebuild, abstaining rather than writing it twice", mainClass);
            return Optional.empty();
        }

        boolean bundled = HAS_DEPENDENCY.matcher(pom).find();
        String repaired = withRunnablePackaging(pom, mainClass, bundled);
        if (repaired.equals(pom)) return Optional.empty();

        log.info("T4-DO: {} carries no Main-Class — declaring entry point {}{}",
                jar.get().getFileName(), mainClass,
                bundled ? " and placing declared dependencies beside the jar" : "");
        return Optional.of(repaired);
    }

    /** True when the jar can be handed to {@code java -jar} — the same question the launcher asks. */
    static boolean declaresEntryPoint(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            if (manifest == null) return false;
            String main = manifest.getMainAttributes().getValue("Main-Class");
            return main != null && !main.isBlank();
        } catch (IOException e) {
            log.debug("T4-DO: unreadable jar {} — {}", jar, e.getMessage());
            return true;   // unreadable is not evidence of absence; abstain by claiming nothing is wrong
        }
    }

    /**
     * T1-GD: whether the project's own sources say it is meant to be LAUNCHED at all — exactly the
     * question {@link #repairedPom} asks before it offers a repair, exposed so a caller that only
     * wants the verdict need not ask it a second way.
     *
     * <p><b>Why a launchability check is incomplete without it.</b> "This archive declares no entry
     * point" is true of a library, and a library owes none. Acting on the first question alone
     * turns a correct artifact into a defect and demands a completion model the specification never
     * asked for — which is the asymmetry T4-DD item 3 removed one level down, where an artifact
     * that terminated by design was reported as one that crashed at startup. Two main methods
     * answer {@code false} for the same reason they do above: the check can say the jar will not
     * run and cannot say what it should have run.
     */
    static boolean sourcesNameSoleEntryPoint(Path artifactRoot) {
        if (artifactRoot == null) return false;
        return BootstrapPomResolver.soleMainClassOf(readSources(artifactRoot)) != null;
    }

    /**
     * Whether this class's own repair is already present in the pom for {@code mainClass}. Not the
     * "is packaging configured" question — that one needs a table of plugin names and the jar
     * answers it instead. This one only prevents writing the same block twice while the jar has
     * not yet been rebuilt against it.
     */
    static boolean declaresEntryPointInPom(String pom, String mainClass) {
        if (pom == null || mainClass == null) return false;
        return MAIN_CLASS_ELEMENT.matcher(pom).results()
                .anyMatch(r -> mainClass.equals(r.group(1).trim()));
    }

    /** The project's own {@code .java} sources, keyed by their path relative to the root. */
    private static Map<String, String> readSources(Path artifactRoot) {
        Path sourceRoot = artifactRoot.resolve("src");
        if (!Files.isDirectory(sourceRoot)) return Map.of();
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(sourceRoot)) {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    .toList()) {
                sources.put(artifactRoot.relativize(p).toString(), Files.readString(p));
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable sources are not evidence that the project owes an entry point.
            log.debug("T4-DO: could not read sources under {} — {}", sourceRoot, e.getMessage());
            return Map.of();
        }
        return sources;
    }

    /**
     * T4-DO (routing, 2026-09-21): applies the repair to the project on disk, reading the pom and
     * the sources from the tree itself. Returns true when the pom was changed — the caller's next
     * package rebuilds against it.
     *
     * <p><b>Why this exists as a second entry point.</b> The repair had exactly one caller, inside
     * {@code autoCompleteRootIfSatisfied}, which is the CLOSURE edge. A run whose artifact cannot
     * run is precisely the run that stalls before closure, so the repair stood behind a milestone
     * the defect prevents the run from reaching: run 2026-09-21_00-32-59 spent thirty minutes with
     * an unlaunchable jar, {@code closure_attempts=0}, and this repair firing zero times. That was
     * recorded as fixed in August by adding a second call "after the package gate" — but both call
     * sites sit inside the same method, so the call moved within the unreachable region rather
     * than out of it. This overload is callable from a gate that runs every cycle, where the jar
     * first exists.
     *
     * <p>It decides nothing the in-memory form does not: same questions, same abstentions.
     */
    public static boolean repairOnDisk(Path artifactRoot) {
        if (artifactRoot == null) return false;
        Path pomPath = artifactRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pomPath)) return false;
        // T2-FK cost gate. Asked before the source tree is read so the per-cycle caller pays a
        // directory listing and a manifest read to abstain, not a full tree walk. The same
        // question is asked again inside repairedPom, which keeps ONE home for the decision
        // ([[feedback-one-invariant-one-home]]) at the price of a second cheap read.
        if (archiveNeedingEntryPoint(artifactRoot).isEmpty()) return false;
        try {
            String pom = Files.readString(pomPath);
            Optional<String> repaired = repairedPom(artifactRoot, pom, readSources(artifactRoot));
            if (repaired.isEmpty()) return false;
            Files.writeString(pomPath, repaired.get());
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("T4-DO: could not apply the packaging repair under {} — {}", artifactRoot, e.toString());
            return false;
        }
    }

    /**
     * T4-DW: positive evidence that the archive's entry point is declared, for retiring the work
     * item raised when it was not ({@code BlockerTypes.CLAIM_ARCHIVE_ENTRY_POINT}).
     *
     * <p>True when the built archive already declares a {@code Main-Class}, or when the build
     * config declares exactly the one entry point the sources name — the instruction the item
     * itself carries, satisfied even while the tree does not compile and no rebuild can confirm
     * it. The second half is a file fact, which is why it can answer on the cycles a launch never
     * reaches.
     *
     * <p>Never true by abstention: no pom, no sole entry point in the sources, a declaration of a
     * DIFFERENT class, or anything unreadable all answer false. An unreadable jar counts as no
     * evidence here even though {@link #declaresEntryPoint} treats it as "nothing wrong" — that
     * method guards against raising a defect, this one against retiring one.
     */
    public static boolean entryPointDeclared(Path artifactRoot) {
        if (artifactRoot == null) return false;
        Optional<Path> jar = builtJar(artifactRoot);
        if (jar.isPresent() && manifestNamesMainClass(jar.get())) return true;
        Path pomPath = artifactRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pomPath)) return false;
        try {
            String mainClass = BootstrapPomResolver.soleMainClassOf(readSources(artifactRoot));
            return mainClass != null && declaresEntryPointInPom(Files.readString(pomPath), mainClass);
        } catch (IOException | RuntimeException e) {
            log.debug("T4-DW: could not read {} — no evidence the entry point is declared", pomPath);
            return false;
        }
    }

    /** The manifest names a Main-Class; unreadable or absent answers false. */
    private static boolean manifestNamesMainClass(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            String main = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
            return main != null && !main.isBlank();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The half of the question answerable WITHOUT reading a single source file: is there exactly
     * one built archive, and does it already declare an entry point?
     *
     * <p>T2-FK: extracted so {@link #repairOnDisk} can ask it before {@code readSources()} walks
     * and reads the whole source tree. That ordering is what makes the repair safe to call every
     * cycle: when it abstains — which is the common case, since a correct artifact declares its
     * entry point — the cost is a directory listing and one manifest read. Reading the tree first
     * and abstaining afterwards is the shape that made a per-cycle call look expensive.
     */
    private static Optional<Path> archiveNeedingEntryPoint(Path artifactRoot) {
        Optional<Path> jar = builtJar(artifactRoot);
        if (jar.isEmpty()) {
            log.debug("T4-DO: no built jar to inspect — abstaining");
            return Optional.empty();
        }
        if (declaresEntryPoint(jar.get())) {
            log.debug("T4-DO: {} already declares Main-Class — abstaining", jar.get().getFileName());
            return Optional.empty();
        }
        return jar;
    }

    private static Optional<Path> builtJar(Path artifactRoot) {
        Path target = artifactRoot.resolve("target");
        if (!Files.isDirectory(target)) return Optional.empty();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(target, "*.jar")) {
            List<Path> found = new ArrayList<>();
            for (Path p : jars) if (!p.getFileName().toString().endsWith("-sources.jar")) found.add(p);
            return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Adds the manifest entry, and the dependency placement when the project declares any. Written
     * into the existing {@code <build><plugins>} when there is one so a project's other build
     * configuration survives.
     */
    static String withRunnablePackaging(String pom, String mainClass, boolean bundleDependencies) {
        StringBuilder plugins = new StringBuilder();
        plugins.append("""
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-jar-plugin</artifactId>
                            <configuration>
                                <archive>
                                    <manifest>
                """);
        if (bundleDependencies) {
            plugins.append("                                <addClasspath>true</addClasspath>\n");
            plugins.append("                                <classpathPrefix>lib/</classpathPrefix>\n");
        }
        plugins.append("                                <mainClass>").append(mainClass).append("</mainClass>\n");
        plugins.append("""
                                    </manifest>
                                </archive>
                            </configuration>
                        </plugin>
                """);
        if (bundleDependencies) {
            plugins.append("""
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-dependency-plugin</artifactId>
                            <executions>
                                <execution>
                                    <phase>package</phase>
                                    <goals><goal>copy-dependencies</goal></goals>
                                    <configuration>
                                        <outputDirectory>${project.build.directory}/lib</outputDirectory>
                                    </configuration>
                                </execution>
                            </executions>
                        </plugin>
                """);
        }

        Matcher build = BUILD_OPEN.matcher(pom);
        if (build.find()) {
            return pom.substring(0, build.end()) + "\n" + plugins + pom.substring(build.end());
        }
        int close = pom.lastIndexOf("</project>");
        if (close < 0) return pom;
        return pom.substring(0, close) + "    <build>\n        <plugins>\n" + plugins
                + "        </plugins>\n    </build>\n" + pom.substring(close);
    }
}
