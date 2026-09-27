package com.walpro.autobuilder.core.build;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * T4-BM: the JDK platform as a discovery source. The classpath jar scan cannot see the JDK —
 * platform types live in the runtime image, not in dependency jars — so a missing {@code Optional}
 * used to resolve to whatever jar happened to republish the simple name (field-observed 2026-07-08/09:
 * {@code org.apache.el.stream.Optional} from a transitive {@code tomcat-embed-el}, injected across
 * six files plus a pom dependency, churning until an unrelated rewrite dropped it). This index makes
 * the platform's own vocabulary visible to {@code PackageDiscoveryService} the same way the resolved
 * classpath is: discovered live from the running JDK ({@link ModuleFinder#ofSystem()}), never a
 * hardcoded package list.
 *
 * <p>Indexed once per JVM: every top-level class in an unconditionally-exported package of a system
 * module, keyed by simple name. {@code java.base} is tracked as its own view because it is the one
 * module every Java program requires implicitly (JPMS: all modules read {@code java.base}) — its
 * names are the language's core vocabulary, which is what makes a unique {@code java.base} match
 * strong enough to act on while other modules' names only participate as an ambiguity observation.
 *
 * <p>2026-09-27: moved from {@code core.build.inject} and made public, so the stuck router's type
 * authority ({@link StuckQuestions.TypeAuthority}) reads the SAME index instead of its own second scan of
 * the runtime image. Logging through {@link System.Logger}, so the class has no dependency beyond the JDK.
 */
public final class JdkPlatformTypeIndex {

    private static final System.Logger LOG = System.getLogger(JdkPlatformTypeIndex.class.getName());

    private record Index(Map<String, Set<String>> all, Map<String, Set<String>> base,
                         Map<String, Set<String>> byPackage) {}

    private static volatile Index index;

    private JdkPlatformTypeIndex() {}

    /** FQNs of exported top-level platform types with this simple name, across all system modules. */
    public static Set<String> exportedTypes(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) return Set.of();
        return index().all().getOrDefault(simpleName, Set.of());
    }

    /** The {@code java.base} subset of {@link #exportedTypes(String)}. */
    public static Set<String> javaBaseTypes(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) return Set.of();
        return index().base().getOrDefault(simpleName, Set.of());
    }

    /**
     * T4-DF: FQNs of every exported top-level type in one platform package. The member-level
     * lookup needs the package's whole membership, which the simple-name maps cannot answer;
     * it comes from the same module walk rather than a second pass over the runtime image.
     */
    public static Set<String> typesInPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) return Set.of();
        return index().byPackage().getOrDefault(packageName, Set.of());
    }

    private static Index index() {
        Index i = index;
        if (i == null) {
            synchronized (JdkPlatformTypeIndex.class) {
                i = index;
                if (i == null) {
                    i = build();
                    index = i;
                }
            }
        }
        return i;
    }

    private static Index build() {
        long start = System.currentTimeMillis();
        Map<String, Set<String>> all = new HashMap<>();
        Map<String, Set<String>> base = new HashMap<>();
        Map<String, Set<String>> byPackage = new HashMap<>();
        for (ModuleReference ref : ModuleFinder.ofSystem().findAll()) {
            ModuleDescriptor descriptor = ref.descriptor();
            Set<String> exportedPackages = descriptor.exports().stream()
                    .filter(e -> !e.isQualified())
                    .map(ModuleDescriptor.Exports::source)
                    .collect(Collectors.toSet());
            if (exportedPackages.isEmpty()) continue;
            boolean isJavaBase = "java.base".equals(descriptor.name());
            try (ModuleReader reader = ref.open()) {
                reader.list().forEach(resource -> {
                    if (!resource.endsWith(".class")) return;
                    if (resource.indexOf('$') >= 0) return;             // nested types
                    if (resource.endsWith("module-info.class")) return;
                    String fqn = resource.substring(0, resource.length() - ".class".length())
                            .replace('/', '.');
                    int lastDot = fqn.lastIndexOf('.');
                    if (lastDot <= 0) return;
                    if (!exportedPackages.contains(fqn.substring(0, lastDot))) return;
                    String simple = fqn.substring(lastDot + 1);
                    all.computeIfAbsent(simple, k -> new LinkedHashSet<>()).add(fqn);
                    byPackage.computeIfAbsent(fqn.substring(0, lastDot), k -> new LinkedHashSet<>()).add(fqn);
                    if (isJavaBase) {
                        base.computeIfAbsent(simple, k -> new LinkedHashSet<>()).add(fqn);
                    }
                });
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "T4-BM: unreadable system module {0} -- {1}", descriptor.name(), e.getMessage());
            }
        }
        all.replaceAll((k, v) -> Set.copyOf(v));
        base.replaceAll((k, v) -> Set.copyOf(v));
        byPackage.replaceAll((k, v) -> Set.copyOf(v));
        LOG.log(System.Logger.Level.INFO, "T4-BM: JDK platform type index built ({0} simple names, {1} in java.base, {2}ms)",
                all.size(), base.size(), System.currentTimeMillis() - start);
        return new Index(Map.copyOf(all), Map.copyOf(base), Map.copyOf(byPackage));
    }
}
