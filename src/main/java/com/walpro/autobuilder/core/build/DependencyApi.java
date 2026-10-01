package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.inject.PackageDiscoveryService;
import com.walpro.autobuilder.protocol.PromptTokenBudget;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-DY, the user's decision 2026-10-01: the API of the dependencies a change uses, shown to the model BEFORE it
 * writes against them, read from the project's own jars.
 *
 * <p>parameterized-rows (2026-10-01): the stuck layer delivered the right fact for every invented JavaParser
 * name -- "ArrayCreationLevel is declared in com.github.javaparser.ast", "no FloatLiteralExpr exists; the
 * package declares DoubleLiteralExpr, IntegerLiteralExpr, ..." -- and the model wrote the next invented name.
 * One answer per failure cannot end a grind whose cause is an API never shown.
 *
 * <p>Three layers, each entry whole or left out within the budget, in this order -- the replay on the real
 * attempt (2026-10-01) showed member listings first spend the budget before the package lists that stop an
 * invented name:
 * <ol>
 *   <li>for each dependency PACKAGE imported (in the tree or the last failed attempt): the classes it
 *       declares -- an invented class is visibly absent, the real ones beside it;</li>
 *   <li>for each dependency CLASS an import names: its public members as {@code name(ParamType, ...): Return},
 *       and what it extends -- without the type-cast family ({@code asX()}, {@code ifX(..)}, {@code isX()},
 *       {@code toX()} where X is a class of the same package), which repeats in every subclass;</li>
 *   <li>for each library the goal's or subgoal's words name (matched against the classpath jars' artifact
 *       names, no library named in this code): its packages.</li>
 * </ol>
 * The project's own packages and the JDK's are never listed: only what the project's jars add.
 */
public final class DependencyApi {

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?!static\\s)([\\w.]+)\\.(\\w+|\\*)\\s*;");
    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    /** {@code .../<artifact>/<version>/<artifact>-<version>.jar} -- the local repository's layout. */
    private static final Pattern ARTIFACT = Pattern.compile("([^/\\\\]+)[/\\\\]([^/\\\\]+)[/\\\\]\\1-\\2[^/\\\\]*\\.jar$");

    private DependencyApi() {
    }

    /**
     * The block, or null when the work uses no dependency the jars declare.
     *
     * @param sources   the files of the tree and of the last failed attempt the imports are read from
     * @param words     the goal's and the subgoal's text, for libraries named by name
     * @param jars      the project's classpath jars
     * @param loader    a loader over exactly those jars (platform parent)
     * @param maxTokens the room the prompt has left for it
     */
    public static String block(Collection<String> sources, String words, List<String> jars, ClassLoader loader,
                               int maxTokens) {
        if (jars == null || jars.isEmpty() || maxTokens <= 0) return null;
        Set<String> own = new TreeSet<>();
        Map<String, Set<String>> importedClasses = new LinkedHashMap<>();
        Set<String> importedPackages = new LinkedHashSet<>();
        for (String s : sources) {
            if (s == null) continue;
            Matcher p = PACKAGE_DECL.matcher(s);
            if (p.find()) own.add(p.group(1));
            Matcher m = IMPORT.matcher(s);
            while (m.find()) {
                importedPackages.add(m.group(1));
                if (!m.group(2).equals("*")) importedClasses.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(m.group(2));
            }
        }
        importedPackages.removeIf(pkg -> own.contains(pkg) || pkg.startsWith("java.") || pkg.startsWith("javax.")
                || !JdkPlatformTypeIndex.typesInPackage(pkg).isEmpty());

        List<String> entries = new ArrayList<>();
        Map<String, Set<String>> packageClasses = new LinkedHashMap<>();
        for (String pkg : importedPackages) {
            Set<String> classes = PackageDiscoveryService.scanJarsForPackage(jars, pkg);
            if (!classes.isEmpty()) packageClasses.put(pkg, classes);
        }
        // 1. the packages imported
        for (var e : packageClasses.entrySet()) {
            Set<String> wanted = importedClasses.getOrDefault(e.getKey(), Set.of());
            List<String> missing = wanted.stream().filter(c -> !e.getValue().contains(e.getKey() + "." + c)).toList();
            entries.add("  package " + e.getKey() + " declares: " + simpleNames(e.getValue())
                    + (missing.isEmpty() ? "" : "\n    (it declares no " + String.join(", no ", missing) + ")"));
        }
        // 2. the classes an import names, that the jars really declare
        for (var e : importedClasses.entrySet()) {
            Set<String> declared = packageClasses.getOrDefault(e.getKey(), Set.of());
            for (String simple : e.getValue()) {
                String fqn = e.getKey() + "." + simple;
                if (declared.contains(fqn)) {
                    String members = members(fqn, loader, packageClasses.values());
                    if (members != null) entries.add(members);
                }
            }
        }
        // 3. the libraries the work names
        for (String jar : jars) {
            String library = libraryNamed(jar, words);
            if (library == null) continue;
            Set<String> packages = packagesOf(jar);
            if (!packages.isEmpty()) entries.add("  " + library + " declares the packages: " + String.join(", ", packages));
        }
        if (entries.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("--- THE DEPENDENCY API THIS WORK USES (read from the project's jars; write against these names) ---\n");
        int left = 0;
        for (String entry : entries) {
            if (PromptTokenBudget.estimateTokens(sb) + PromptTokenBudget.estimateTokens(entry) > maxTokens) {
                left++;
                continue;
            }
            sb.append(entry).append('\n');
        }
        if (left > 0) sb.append("  (").append(left).append(" more left out: no room in this prompt)\n");
        return left == entries.size() ? null : sb.toString().stripTrailing();
    }

    private static final Pattern CAST_FAMILY = Pattern.compile("^(?:as|if|is|to)([A-Z]\\w*)$");

    /**
     * One class's public members, declared in it, and what it extends; null when it cannot be read. A method
     * named as/if/is/to + a class of the listed packages is the type-cast family and is left out.
     */
    static String members(String fqn, ClassLoader loader, Collection<Set<String>> packages) {
        Set<String> known = new java.util.HashSet<>();
        for (Set<String> p : packages) for (String f : p) known.add(f.substring(f.lastIndexOf('.') + 1));
        try {
            Class<?> c = Class.forName(fqn, false, loader);
            Set<String> out = new TreeSet<>();
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic() || m.isBridge()) continue;
                Matcher cast = CAST_FAMILY.matcher(m.getName());
                if (cast.matches() && known.contains(cast.group(1))) continue;
                StringBuilder sig = new StringBuilder(Modifier.isStatic(m.getModifiers()) ? "static " : "")
                        .append(m.getName()).append('(');
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) sig.append(i > 0 ? ", " : "").append(params[i].getSimpleName());
                out.add(sig.append("): ").append(m.getReturnType().getSimpleName()).toString());
            }
            for (var k : c.getDeclaredConstructors()) {
                if (!Modifier.isPublic(k.getModifiers())) continue;
                StringBuilder sig = new StringBuilder("new ").append(c.getSimpleName()).append('(');
                Class<?>[] params = k.getParameterTypes();
                for (int i = 0; i < params.length; i++) sig.append(i > 0 ? ", " : "").append(params[i].getSimpleName());
                out.add(sig.append(')').toString());
            }
            String sup = c.getSuperclass() == null || c.getSuperclass() == Object.class ? ""
                    : " (extends " + c.getSuperclass().getName() + ")";
            return "  " + fqn + sup + ": " + (out.isEmpty() ? "no public members of its own" : String.join("; ", out));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The library a jar is, when the work's words name it: its artifact name, or that name before its first
     * hyphen, appears in the words as a whole word (case ignored). Null otherwise.
     */
    static String libraryNamed(String jarPath, String words) {
        if (words == null || words.isBlank()) return null;
        Matcher m = ARTIFACT.matcher(jarPath.replace('\\', '/'));
        if (!m.find()) return null;
        String artifact = m.group(1);
        String text = " " + words.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", " ") + " ";
        for (String name : List.of(artifact.toLowerCase(Locale.ROOT), artifact.toLowerCase(Locale.ROOT).split("-")[0])) {
            if (name.length() >= 4 && text.contains(" " + name + " ")) return artifact + " (" + m.group(2) + ")";
        }
        return null;
    }

    /** The packages that hold classes in one jar, sorted. */
    static Set<String> packagesOf(String jarPath) {
        Set<String> out = new TreeSet<>();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String n = entries.nextElement().getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.indexOf('/') < 0) continue;
                out.add(n.substring(0, n.lastIndexOf('/')).replace('/', '.'));
            }
        } catch (java.io.IOException e) {
            // an unreadable jar names nothing
        }
        return out;
    }

    private static String simpleNames(Collection<String> fqns) {
        List<String> out = new ArrayList<>();
        for (String f : fqns) out.add(f.substring(f.lastIndexOf('.') + 1));
        return String.join(", ", out);
    }
}
