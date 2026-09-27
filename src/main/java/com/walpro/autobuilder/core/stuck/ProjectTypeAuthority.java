package com.walpro.autobuilder.core.stuck;

import com.walpro.autobuilder.core.build.JdkPlatformTypeIndex;
import com.walpro.autobuilder.core.build.ProjectClasspathService;
import com.walpro.autobuilder.core.build.StuckQuestions;
import com.walpro.autobuilder.core.build.inject.PackageDiscoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The stuck router's type authority in the loop: the running JDK AND the project's own resolved
 * dependency classpath, under the one rule {@link StuckQuestions#resolveAgainst} states (a written package
 * is exact; the project's own types win; java.lang; otherwise the one candidate, or the one the project's
 * imports name).
 *
 * <p>Port review 2026-09-27: the terminal agent was asked three times about JavaParser types
 * ("RecordDeclaration has no getComponents()"), a fact that lives in a dependency jar no structural
 * question could read. The classpath comes from {@link ProjectClasspathService} -- the same resolved
 * classpath the compile probe and package discovery read -- and the jar scan is discovery's own
 * ({@link PackageDiscoveryService#scanJarsForType}), so there is one answer to "what is on this project's
 * classpath" in the loop, not three.
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "a class loader over the resolved classpath; a restored tree re-resolves it")
public class ProjectTypeAuthority {

    private static final long CLASSPATH_TIMEOUT_MS = 30_000L;

    @Autowired(required = false)
    private ProjectClasspathService classpathService;

    private String cachedClasspath;
    private URLClassLoader cachedLoader;
    private List<String> cachedJars = List.of();

    /** The authority for the project at {@code projectRoot}; the JDK alone when its classpath cannot be resolved. */
    public StuckQuestions.TypeAuthority forProject(Path projectRoot) {
        if (classpathService == null || projectRoot == null) return StuckQuestions.TypeAuthority.platform();
        String classpath;
        try {
            classpath = classpathService.resolveClasspath(projectRoot, CLASSPATH_TIMEOUT_MS);
        } catch (RuntimeException e) {
            log.debug("T4-DY: classpath unresolvable for the type authority: {}", e.getMessage());
            return StuckQuestions.TypeAuthority.platform();
        }
        if (classpath == null || classpath.isBlank()) return StuckQuestions.TypeAuthority.platform();
        URLClassLoader loader;
        List<String> jars;
        synchronized (this) {
            if (!classpath.equals(cachedClasspath)) {
                if (cachedLoader != null) {
                    try {
                        cachedLoader.close();
                    } catch (java.io.IOException ignored) {
                        // the jars are only read; a failed close leaks nothing that matters
                    }
                }
                cachedJars = jars(classpath);
                cachedLoader = loader(cachedJars);
                cachedClasspath = classpath;
            }
            loader = cachedLoader;
            jars = cachedJars;
        }
        return (name, sources) -> StuckQuestions.resolveAgainst(name, sources, loader, simple -> {
            Set<String> candidates = new LinkedHashSet<>(JdkPlatformTypeIndex.exportedTypes(simple));
            candidates.addAll(PackageDiscoveryService.scanJarsForType(jars, simple).keySet());
            return candidates;
        });
    }

    private static List<String> jars(String classpath) {
        List<String> out = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isBlank() && entry.endsWith(".jar")) out.add(entry);
        }
        return out;
    }

    /** Platform parent: the project's dependencies resolve against its own classpath, never Autobuilder's. */
    private static URLClassLoader loader(List<String> jars) {
        List<URL> urls = new ArrayList<>();
        for (String jar : jars) {
            try {
                urls.add(new File(jar).toURI().toURL());
            } catch (MalformedURLException e) {
                // an unusable entry is skipped
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }
}
