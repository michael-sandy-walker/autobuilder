package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.JdkPlatformTypeIndex;

import com.walpro.autobuilder.core.build.ProjectClasspathService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * T4-DF: member-level classpath discovery. Answers <i>which type on the resolved classpath
 * declares this member</i> — the question that arises when the code addressed a type that does
 * not have it.
 *
 * <p>Type-level discovery already exists ({@link PackageDiscoveryService}), and it cannot answer
 * this: it matches jar entry NAMES, so it knows which type a simple name refers to and never
 * opens a class file. The distinction is not academic. Measured on the flight-simulator run
 * ({@code lineage-76eda78f}), 12 of 16 missing-member blockers were a member declared on a
 * sibling type in the receiver's own package, and the only move available was to add a
 * dependency that was already declared. A library search would not have fixed one of them.
 *
 * <p>The search is deliberately narrow — one package, the receiver's own:
 * <ol>
 *   <li>The diagnostic already names the receiver type, so the search space is bounded without
 *       indexing every jar.</li>
 *   <li>It self-gates: no match means no opinion, and the caller keeps whatever it would have
 *       done otherwise.</li>
 *   <li>It needs no network and no model.</li>
 * </ol>
 *
 * <p><b>Disambiguation is structural, not nominal.</b> A package often has several claimants —
 * on the field corpus {@code glBindVertexArray} is declared by three types in
 * {@code org.lwjgl.opengl}. The rule that separates them is that the receiver is a SUPERTYPE of
 * the type that declares the member: the code addressed a base and the member lives further down
 * that same hierarchy. That relation is expressible without knowing anything about the library,
 * and it resolves every ambiguous case in the corpus to exactly one answer.
 *
 * <p>Classes are read with {@code Class.forName(name, false, loader)}, which gives declared
 * members exactly and runs no static initializer. The loader's parent is the platform loader, so
 * the target's types resolve against the target's own classpath and never against autobuilder's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClasspathMemberDeclarationService {

    private static final long CLASSPATH_TIMEOUT_MS = 30_000L;

    private final ProjectClasspathService classpathService;

    /**
     * Where a member the receiver does not declare actually lives.
     *
     * @param receiverType  the type the code addressed, as the diagnostic named it
     * @param memberName    the member that would not resolve on it
     * @param declaringType the classpath type that declares it
     */
    public record Declaration(String receiverType, String memberName, String declaringType) {}

    /**
     * Reports the type in the receiver's own package that declares {@code memberName}, or empty
     * when the classpath does not answer the question. Empty covers every uncertain case —
     * no claimant, several claimants that the supertype relation cannot separate, an
     * unresolvable receiver, or an unreadable classpath.
     */
    public Optional<Declaration> declaringSibling(String receiverFqn, String memberName, Path projectRoot) {
        if (receiverFqn == null || memberName == null || memberName.isBlank()) return Optional.empty();
        int lastDot = receiverFqn.lastIndexOf('.');
        if (lastDot <= 0) return Optional.empty();               // no package, nothing to search
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return Optional.empty();

        String packageName = receiverFqn.substring(0, lastDot);
        List<String> jars = classpathJars(projectRoot);
        Set<String> siblings = typesInPackage(packageName, jars);
        if (siblings.isEmpty()) {
            log.info("T4-DF: {}#{} — package {} is not on the resolved classpath; abstaining",
                    receiverFqn, memberName, packageName);
            return Optional.empty();
        }

        try (URLClassLoader loader = loaderFor(jars)) {
            List<String> claimants = new ArrayList<>();
            for (String type : siblings) {
                if (type.equals(receiverFqn)) continue;
                if (declaresStatic(type, memberName, loader)) claimants.add(type);
            }
            if (claimants.isEmpty()) {
                // INFO, not DEBUG: a run logs at INFO, so an abstention recorded below that level
                // is indistinguishable from never having been consulted. Field run
                // 2026-08-28_00-33-32 had exactly one opportunity, abstained correctly, and said
                // nothing — the outcome had to be re-derived offline from the archived stderr.
                log.info("T4-DF: {}#{} — no type in {} declares it; abstaining",
                        receiverFqn, memberName, packageName);
                return Optional.empty();
            }

            String declaring = claimants.size() == 1
                    ? claimants.get(0)
                    : uniqueSubtypeOf(receiverFqn, claimants, loader).orElse(null);
            if (declaring == null) {
                log.info("T4-DF: {}#{} — {} claimants ({}), none uniquely below the receiver; abstaining",
                        receiverFqn, memberName, claimants.size(), claimants);
                return Optional.empty();
            }
            log.info("T4-DF: {}#{} is declared by {}", receiverFqn, memberName, declaring);
            return Optional.of(new Declaration(receiverFqn, memberName, declaring));
        } catch (IOException e) {
            log.debug("T4-DF: classpath loader failed for {} — {}", receiverFqn, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A call written as {@code Type.member(...)} can only bind to a static member, so a claimant
     * that declares an instance member of the same name is not an answer to this question.
     */
    private static boolean declaresStatic(String type, String memberName, ClassLoader loader) {
        try {
            for (Method m : Class.forName(type, false, loader).getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (m.getName().equals(memberName) && Modifier.isStatic(mod) && Modifier.isPublic(mod)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // A type that will not load contributes nothing. Linkage errors are ordinary here:
            // a classpath jar may reference an optional dependency the target never declared.
            log.trace("T4-DF: {} not readable — {}", type, t.toString());
        }
        return false;
    }

    /** The one claimant the receiver is a supertype of, when exactly one claimant qualifies. */
    private static Optional<String> uniqueSubtypeOf(String receiverFqn, List<String> claimants,
                                                    ClassLoader loader) {
        Class<?> receiver;
        try {
            receiver = Class.forName(receiverFqn, false, loader);
        } catch (Throwable t) {
            return Optional.empty();
        }
        List<String> below = new ArrayList<>();
        for (String claimant : claimants) {
            try {
                if (receiver.isAssignableFrom(Class.forName(claimant, false, loader))) below.add(claimant);
            } catch (Throwable ignored) {
                // Same as above: an unreadable claimant simply does not qualify.
            }
        }
        return below.size() == 1 ? Optional.of(below.get(0)) : Optional.empty();
    }

    private List<String> classpathJars(Path projectRoot) {
        String classpath = classpathService.resolveClasspath(projectRoot, CLASSPATH_TIMEOUT_MS);
        if (classpath == null || classpath.isBlank()) return List.of();
        List<String> jars = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isBlank() && entry.endsWith(".jar")) jars.add(entry);
        }
        return jars;
    }

    private static URLClassLoader loaderFor(List<String> jars) {
        List<URL> urls = new ArrayList<>();
        for (String jar : jars) {
            try {
                urls.add(new File(jar).toURI().toURL());
            } catch (MalformedURLException e) {
                log.debug("T4-DF: unusable classpath entry {} — {}", jar, e.getMessage());
            }
        }
        // Platform parent, not the application loader: the target's dependencies must resolve
        // against the target's own classpath. Delegating to autobuilder's would answer with
        // autobuilder's version of a shared library.
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    /**
     * Top-level types in one package. Platform packages come from the module index — they live in
     * the runtime image, not in a classpath jar, and the corpus needs them
     * ({@code FloatBuffer.allocateDirect} is {@code ByteBuffer.allocateDirect}).
     */
    private static Set<String> typesInPackage(String packageName, List<String> jars) {
        Set<String> platform = JdkPlatformTypeIndex.typesInPackage(packageName);
        if (!platform.isEmpty()) return platform;

        Set<String> types = new TreeSet<>();
        String prefix = packageName.replace('.', '/') + "/";
        for (String jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith(prefix) || !name.endsWith(".class")) continue;
                    String rest = name.substring(prefix.length(), name.length() - ".class".length());
                    if (rest.indexOf('/') >= 0) continue;   // a sub-package, not this one
                    if (rest.indexOf('$') >= 0) continue;   // nested types are not addressable here
                    types.add(packageName + "." + rest);
                }
            } catch (IOException e) {
                log.debug("T4-DF: unreadable classpath jar {} — {}", jarPath, e.getMessage());
            }
        }
        return types;
    }
}
