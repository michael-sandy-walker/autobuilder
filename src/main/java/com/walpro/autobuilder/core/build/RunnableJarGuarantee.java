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

    private RunnableJarGuarantee() {}

    /** The pom this project needs in order to produce a runnable jar, or empty when it already does. */
    public static Optional<String> repairedPom(Path artifactRoot, String pom, Map<String, String> sources) {
        if (artifactRoot == null || pom == null || pom.isBlank()) return Optional.empty();

        Optional<Path> jar = builtJar(artifactRoot);
        if (jar.isEmpty()) {
            log.debug("T4-DO: no built jar to inspect — abstaining");
            return Optional.empty();
        }
        if (declaresEntryPoint(jar.get())) {
            log.debug("T4-DO: {} already declares Main-Class — abstaining", jar.get().getFileName());
            return Optional.empty();
        }

        String mainClass = BootstrapPomResolver.soleMainClassOf(sources);
        if (mainClass == null) {
            // A library, or several candidates. Neither is this check's business: it can say the
            // jar will not run, and cannot say what it should have run.
            log.info("T4-DO: {} declares no Main-Class and the sources name no single entry point — "
                    + "abstaining rather than choosing one", jar.get().getFileName());
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
